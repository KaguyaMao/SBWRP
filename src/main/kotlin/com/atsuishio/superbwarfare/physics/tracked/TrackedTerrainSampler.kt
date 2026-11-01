package com.atsuishio.superbwarfare.physics.tracked

import cn.solarmoon.spark_core.physics.terrain.SectionSnapshot
import cn.solarmoon.spark_core.physics.terrain.TerrainRegionSnapshot
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Detached sampler for a tracked vehicle's local terrain mesh.
 *
 * It reads **only** the immutable [TerrainRegionSnapshot] the terrain cache hands
 * out. There is no `Level`, no `BlockState`, no `VoxelShape` and therefore no
 * main-thread block query anywhere in this path: the expensive world read already
 * happened once per section inside `PhysicsChunkManager`, and this sampler is a
 * pure data transform that any thread may run.
 *
 * AABB input is intentional. AABB is *not* installed as collision; the
 * [TrackedTerrainMeshPlanner] turns these boxes into the one mesh a tracked
 * vehicle is allowed to collide with.
 */
object TrackedTerrainSampler {

    /**
     * Grid the sampling window center snaps to, blocks.
     *
     * The window must not follow suspension travel or a few centimetres of hull
     * motion: every rebuild replaces the static mesh body, and Bullet then throws
     * away every contact manifold, which costs the belt its traction for a
     * substep. Snapping to a coarse grid means an idling or slowly rolling
     * vehicle reuses exactly the same geometry.
     */
    const val WINDOW_GRID_BLOCKS = 1

    /**
     * Extra blocks beyond the running-gear envelope.
     *
     * Small on purpose. The window is a *requirement*: every section it covers
     * must be cached and settled before a mesh may be built from it, so an
     * over-sized window is not merely slower, it is unbuildable near an unloaded
     * chunk and would freeze the vehicle instead of protecting it. The compiled
     * envelope already covers every wheel station and belt run; one block of
     * margin absorbs a wheel that is exactly on a section corner.
     */
    private const val ENVELOPE_MARGIN = 0.25

    /**
     * Clear ground ahead of the longest generated ramp, metres.
     *
     * A ramp of length `Step` must already be in the installed mesh before the
     * first wheel reaches its foot. The window therefore reaches `Step` plus this
     * margin beyond the running gear in every horizontal direction.
     */
    const val RAMP_LOOKAHEAD = 1.0

    /** Horizontal travel from the window centre that requests a new mesh, metres. */
    const val REBUILD_DISTANCE = 2.0

    /** Vertical travel from the window centre that requests a new mesh, metres. */
    const val REBUILD_HEIGHT = 1.0

    /**
     * Slack built into the window radius, metres.
     *
     * The installed mesh keeps covering the running gear and the ramp look-ahead
     * until the vehicle has moved this far from the window centre. Rebuilding is
     * requested at [REBUILD_DISTANCE], so the replacement has the difference to
     * arrive while the vehicle keeps driving on the old mesh.
     */
    const val COVER_DISTANCE = 3.0

    /** Vertical slack built into the window, metres; see [COVER_DISTANCE]. */
    const val COVER_HEIGHT = 1.5

    /** Window centre for a vehicle at [center], snapped to the block grid. */
    fun snap(center: Vec3): Vec3 = Vec3(snapCenter(center.x), snapCenter(center.y), snapCenter(center.z))

    /** True while a window centred at [windowCenter] still covers a vehicle at [center]. */
    fun covers(windowCenter: Vec3, center: Vec3): Boolean =
        hypot(center.x - windowCenter.x, center.z - windowCenter.z) <= COVER_DISTANCE &&
            abs(center.y - windowCenter.y) <= COVER_HEIGHT

    /** True when a vehicle at [center] has moved far enough to want a new window. */
    fun wantsRebuild(windowCenter: Vec3, center: Vec3): Boolean =
        hypot(center.x - windowCenter.x, center.z - windowCenter.z) > REBUILD_DISTANCE ||
            abs(center.y - windowCenter.y) > REBUILD_HEIGHT

    /** Horizontal radius of every window for this envelope and policy, metres. */
    fun radius(envelope: TerrainEnvelope, terrain: CompiledTerrain): Double {
        val radialX = maxOf(abs(envelope.minX), abs(envelope.maxX))
        val radialZ = maxOf(abs(envelope.minZ), abs(envelope.maxZ))
        val lookahead = if (terrain.rampsEnabled) terrain.stepM + RAMP_LOOKAHEAD else 0.0
        return hypot(radialX, radialZ + lookahead) + ENVELOPE_MARGIN + COVER_DISTANCE
    }

    /**
     * World-space region to sample for a window centred at [windowCenter].
     *
     * The horizontal extent is the compiled running-gear envelope, plus the
     * ramp look-ahead, plus [COVER_DISTANCE] of slack, bounded by the authored
     * `WindowRadius`. The vertical extent covers the authored band and the whole
     * envelope, so a bridge deck above the hull and a pit below it are both
     * inside the region.
     */
    fun window(windowCenter: Vec3, envelope: TerrainEnvelope, terrain: CompiledTerrain): AABB {
        // A rotation-independent disk bound avoids rebuilding on every turn while
        // still containing every yawed wheel and run.
        val limit = terrain.windowRadiusBlocks.toDouble()
        val radius = radius(envelope, terrain)
        val halfBelow = halfBelow(envelope, terrain)
        val halfAbove = maxOf(terrain.verticalAboveBlocks.toDouble(), envelope.maxY + 1.0) + COVER_HEIGHT
        require(radius <= limit) {
            "running gear and ramp look-ahead exceed terrain WindowRadius=$limit (required=$radius)"
        }
        val center = snap(windowCenter)
        return AABB(
            center.x - radius, center.y - halfBelow, center.z - radius,
            center.x + radius, center.y + halfAbove, center.z + radius,
        )
    }

    private fun halfBelow(envelope: TerrainEnvelope, terrain: CompiledTerrain): Double =
        maxOf(terrain.verticalBelowBlocks.toDouble(), -envelope.minY + 1.0) + COVER_HEIGHT

    /** The window centre that [window] was built around; inverse of [window]. */
    fun centerOf(window: AABB, envelope: TerrainEnvelope, terrain: CompiledTerrain): Vec3 = Vec3(
        (window.minX + window.maxX) * 0.5,
        window.minY + halfBelow(envelope, terrain),
        (window.minZ + window.maxZ) * 0.5,
    )

    /** True when [a] and [b] cover the same snapped window. */
    fun sameWindow(a: AABB, b: AABB): Boolean =
        a.minX == b.minX && a.minY == b.minY && a.minZ == b.minZ &&
            a.maxX == b.maxX && a.maxY == b.maxY && a.maxZ == b.maxZ

    /**
     * Build one mesh plan from a detached region.
     *
     * [snapshot] is complete by construction: `PhysicsChunkManager.snapshotRegion`
     * refuses to hand out a region with a missing, inactive, dirty or
     * revision-lagging section. An unknown area can therefore never become a
     * "known empty" mesh.
     */
    fun sample(
        snapshot: TerrainRegionSnapshot,
        terrain: CompiledTerrain,
        requestId: Long,
        runtimeGeneration: Long,
        rampGate: RampGate? = null,
    ): TrackedTerrainSampleResult {
        val bounds = snapshot.bounds
        val region = TerrainBox(
            bounds.minX, bounds.minY, bounds.minZ,
            bounds.maxX, bounds.maxY, bounds.maxZ,
        )

        val boxes = ArrayList<TerrainBox>()
        var hash = 1125899906842597L

        for (section in snapshot.sections) {
            val origin = section.sectionPos.origin()
            val originX = origin.x
            val originY = origin.y
            val originZ = origin.z
            for (block in section.blocks) {
                if (block.boxes.isEmpty()) continue
                val blockX = originX + block.x
                val blockY = originY + block.y
                val blockZ = originZ + block.z
                for (part in block.boxes) {
                    if (part.maxX - part.minX <= 0.0) continue
                    if (part.maxY - part.minY <= 0.0) continue
                    if (part.maxZ - part.minZ <= 0.0) continue
                    val world = TerrainBox(
                        blockX + part.minX,
                        blockY + part.minY,
                        blockZ + part.minZ,
                        blockX + part.maxX,
                        blockY + part.maxY,
                        blockZ + part.maxZ,
                    )
                    // Sections on the region border carry geometry outside the
                    // window; including it would make the mesh depend on the
                    // section grid instead of the vehicle.
                    if (!world.intersects(region)) continue
                    // Keep the real AABB whole. Clipping it to the mesh window
                    // would manufacture vertical cut faces at the region edge.
                    boxes += world
                    hash = hash * 31 + quantized(world.minX)
                    hash = hash * 31 + quantized(world.minY)
                    hash = hash * 31 + quantized(world.minZ)
                    hash = hash * 31 + quantized(world.maxX)
                    hash = hash * 31 + quantized(world.maxY)
                    hash = hash * 31 + quantized(world.maxZ)
                }
            }
        }

        // The origin and the authored policy are part of the geometry contract:
        // the same boxes with a different local origin or different ramp policy
        // must rebuild the mesh instead of silently reusing the old one.
        hash = hash * 31 + quantized(bounds.minX)
        hash = hash * 31 + quantized(bounds.minY)
        hash = hash * 31 + quantized(bounds.minZ)
        hash = hash * 31 + quantized(bounds.maxX)
        hash = hash * 31 + quantized(bounds.maxY)
        hash = hash * 31 + quantized(bounds.maxZ)
        hash = hash * 31 + terrain.windowRadiusBlocks
        hash = hash * 31 + (if (terrain.rampsEnabled) 1 else 0)
        hash = hash * 31 + quantized(terrain.maxClimbM)
        hash = hash * 31 + quantized(terrain.stepM)
        // Heading is part of the geometry. A plan built for a different nose
        // must not be reused just because the boxes did not move.
        hash = hash * 31 + (rampGate?.headingBucket ?: -1).toLong()
        val plan = TrackedTerrainMeshPlanner.build(
            boxes = boxes,
            terrain = terrain,
            originX = bounds.minX,
            originY = bounds.minY,
            originZ = bounds.minZ,
            contentHash = hash,
            region = region,
            terrainRevisionHash = snapshot.revision.hash,
            requestId = requestId,
            runtimeGeneration = runtimeGeneration,
            terrainRevision = snapshot.revision,
            rampGate = rampGate,
        )
        return TrackedTerrainSampleResult.Ready(plan)
    }

    /** Section snapshots of [snapshot], exposed for diagnostics and tests. */
    fun sectionCount(snapshot: TerrainRegionSnapshot): Int = snapshot.sections.size

    /** Block snapshots of [snapshot], exposed for diagnostics and tests. */
    fun blockCount(snapshot: TerrainRegionSnapshot): Int =
        snapshot.sections.sumOf { it.blocks.size }

    private fun snapCenter(value: Double): Double =
        floor(value / WINDOW_GRID_BLOCKS + 0.5) * WINDOW_GRID_BLOCKS

    private fun quantized(value: Double): Long = (value * 10_000.0).roundToLong()

    /** Kept for callers that only need the raw box list of a region. */
    fun boxesOf(snapshot: TerrainRegionSnapshot, bounds: AABB): List<TerrainBox> {
        val out = ArrayList<TerrainBox>()
        for (section: SectionSnapshot in snapshot.sections) {
            val origin = section.sectionPos.origin()
            val originX = origin.x
            val originY = origin.y
            val originZ = origin.z
            for (block in section.blocks) {
                for (part in block.boxes) {
                    val world = TerrainBox(
                        originX + block.x + part.minX,
                        originY + block.y + part.minY,
                        originZ + block.z + part.minZ,
                        originX + block.x + part.maxX,
                        originY + block.y + part.maxY,
                        originZ + block.z + part.maxZ,
                    )
                    if (world.maxX <= bounds.minX || world.minX >= bounds.maxX) continue
                    if (world.maxY <= bounds.minY || world.minY >= bounds.maxY) continue
                    if (world.maxZ <= bounds.minZ || world.minZ >= bounds.maxZ) continue
                    out += world
                }
            }
        }
        return out
    }
}
