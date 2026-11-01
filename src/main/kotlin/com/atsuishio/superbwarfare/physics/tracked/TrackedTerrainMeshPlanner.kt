package com.atsuishio.superbwarfare.physics.tracked

import cn.solarmoon.spark_core.physics.terrain.TerrainRevisionToken
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Pure geometry planner for a tracked vehicle's local terrain mesh.
 *
 * The output is the only terrain collision a tracked vehicle may use. It is
 * deliberately a triangle mesh:
 *
 * - horizontal tops and bottoms from the detached block collision boxes;
 * - vertical faces for every exposed side, including every rise that is not a
 *   generated ramp;
 * - discrete planar ramp triangles for the rises the vehicle is allowed to
 *   climb.
 *
 * This class knows nothing about Bullet or Minecraft. It consumes detached
 * [TerrainBox] values, so it can be unit-tested on the JVM.
 */
object TrackedTerrainMeshPlanner {

    private const val HEIGHT_EPS = 1.0e-4
    private const val MIN_RISE = 1.0e-4
    private const val QUANT = 10_000.0
    /** Cell size used where two same-layer ramps overlap, metres. */
    private const val COUPLE_CELL = 0.25

    private data class Interval(var minY: Double, var maxY: Double)

    private data class Candidate(
        val direction: RampDirection,
        val face: Double,
        val lowY: Double,
        val highY: Double,
        val step: Double,
        val lateralCell: Int,
    )

    private data class CandidateKey(
        val direction: RampDirection,
        val face: Long,
        val lowY: Long,
        val highY: Long,
        val step: Long,
    )

    private data class BoxFace(
        val p0: Vec3,
        val p1: Vec3,
        val p2: Vec3,
        val p3: Vec3,
        val desiredNormal: Vec3,
        val key: FaceKey,
        val sign: Int,
    )

    private data class FaceKey(
        val axis: Int,
        val plane: Long,
        val minA: Long,
        val maxA: Long,
        val minB: Long,
        val maxB: Long,
    )

    fun build(
        boxes: List<TerrainBox>,
        terrain: CompiledTerrain,
        originX: Double,
        originY: Double,
        originZ: Double,
        contentHash: Long,
        region: TerrainBox = TerrainBox(0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        terrainRevisionHash: Long = Long.MIN_VALUE,
        requestId: Long = -1L,
        runtimeGeneration: Long = -1L,
        complete: Boolean = true,
        terrainRevision: TerrainRevisionToken? = null,
        /**
         * When set, only a rise aligned with the vehicle axis and inside the
         * track corridor may become a ramp. Nose, tail and the ground under
         * the hull are all allowed. A side rise stays a wall. Null keeps the
         * ungated geometry used by shape tests.
         */
        rampGate: RampGate? = null,
    ): TerrainMeshPlan {
        // Ramps depend only on terrain, policy and heading. Never on where the
        // running gear happens to be: a ramp that disappears while a wheel is on
        // it turns its riser back into a wall inside the mechanism.
        val index = BoxIndex(boxes)
        val ramps = if (terrain.rampsEnabled) {
            planRamps(index, terrain, rampGate)
        } else {
            emptyList()
        }
        val corners = if (terrain.rampsEnabled) planCorners(index, ramps) else emptyList()
        val vertices = ArrayList<Float>(boxes.size * 72)
        val indices = ArrayList<Int>(boxes.size * 36)
        var wallQuads = 0

        fun addQuad(p0: Vec3, p1: Vec3, p2: Vec3, p3: Vec3, desiredNormal: Vec3) {
            var a = p1
            var d = p3
            val normal = p1.subtract(p0).cross(p2.subtract(p0))
            if (normal.dot(desiredNormal) < 0.0) {
                a = p3
                d = p1
            }
            val base = vertices.size / 3
            for (p in listOf(p0, a, p2, d)) {
                vertices += (p.x - originX).toFloat()
                vertices += (p.y - originY).toFloat()
                vertices += (p.z - originZ).toFloat()
            }
            indices += base
            indices += base + 1
            indices += base + 2
            indices += base
            indices += base + 2
            indices += base + 3
            if (abs(desiredNormal.y) < 0.5) wallQuads++
        }

        // Tops stay at block scale on purpose. Bullet derives mesh/box contacts
        // from triangle vertices and edges; a large coplanar triangle whose
        // vertices fall outside the footprint generates *no* contact at all, so
        // merging tiles would make a hovering, unsupported vehicle. Keeping one
        // tile per supported block gives the solver vertices under every part
        // of the belt while the surface itself stays exactly planar.
        // Every face plane is indexed by the *opposite* face it would cancel
        // against: a top face disappears under a box that starts on that plane,
        // a bottom face disappears over a box that ends on it, and a wall face
        // disappears inside a neighbour that already covers that span. Exact key
        // equality is not enough: a slab on a block, or a one-block step beside a
        // two-block wall, leaves a face buried in solid geometry that Bullet would
        // otherwise collide with.
        val tops = PlaneGrid()
        val bottoms = PlaneGrid()
        val verticalX = PlaneGrid()
        val verticalZ = PlaneGrid()

        boxes.forEachIndexed { owner, box ->
            if (box.maxX - box.minX <= HEIGHT_EPS || box.maxY - box.minY <= HEIGHT_EPS ||
                box.maxZ - box.minZ <= HEIGHT_EPS
            ) return@forEachIndexed
            val footprint = Rect2(box.minX, box.maxX, box.minZ, box.maxZ, owner)
            tops.add(q(box.maxY), footprint, 0)
            bottoms.add(q(box.minY), footprint, 0)
            verticalX.add(q(box.minX), Rect2(box.minZ, box.maxZ, box.minY, box.maxY, owner), -1)
            verticalX.add(q(box.maxX), Rect2(box.minZ, box.maxZ, box.minY, box.maxY, owner), 1)
            verticalZ.add(q(box.minZ), Rect2(box.minX, box.maxX, box.minY, box.maxY, owner), -1)
            verticalZ.add(q(box.maxZ), Rect2(box.minX, box.maxX, box.minY, box.maxY, owner), 1)
        }

        fun visibleRects(face: Rect2, covers: List<Rect2>, owner: Int): List<Rect2> =
            uncoveredRectangles(face, covers.filter { it.owner != owner })

        /** Coplanar horizontal rectangles near [face]; [earlierOnly] keeps lower owners only. */
        fun horizontalRects(grid: PlaneGrid, plane: Long, face: Rect2, earlierOnly: Boolean): List<Rect2> =
            grid.near(plane, face).filter { !earlierOnly || it.rect.owner < face.owner }.map { it.rect }

        fun sideRects(grid: PlaneGrid, plane: Long, face: Rect2, sign: Int): List<Rect2> =
            grid.near(plane, face).filter { it.sign != sign || it.rect.owner < face.owner }.map { it.rect }

        boxes.forEachIndexed { owner, box ->
            val x0 = box.minX; val x1 = box.maxX
            val y0 = box.minY; val y1 = box.maxY
            val z0 = box.minZ; val z1 = box.maxZ
            if (x1 - x0 <= 0.0 || y1 - y0 <= 0.0 || z1 - z0 <= 0.0) return@forEachIndexed

            // Top: hidden when another box starts on this exact plane over this
            // footprint. That is the face-cancellation rule, not a shrink test.
            val footprint = Rect2(x0, x1, z0, z1, owner)
            val topOccluders = horizontalRects(bottoms, q(y1), footprint, false) +
                horizontalRects(tops, q(y1), footprint, true)
            for (face in visibleRects(footprint, topOccluders, owner)) {
                addQuad(
                    Vec3(face.minA, y1, face.minB), Vec3(face.minA, y1, face.maxB),
                    Vec3(face.maxA, y1, face.maxB), Vec3(face.maxA, y1, face.minB),
                    Vec3(0.0, 1.0, 0.0),
                )
            }
            // Bottom: a deck underside is real collision and must exist. The one
            // bottom that is skipped is the region floor: nothing inside the
            // sampled volume can reach under it, and a skin there would double the
            // floor triangle count for no contact.
            val bottomOccluders = horizontalRects(tops, q(y0), footprint, false) +
                horizontalRects(bottoms, q(y0), footprint, true)
            if (y0 > region.minY + HEIGHT_EPS) for (face in visibleRects(
                footprint, bottomOccluders, owner,
            )) {
                addQuad(
                    Vec3(face.minA, y0, face.maxB), Vec3(face.minA, y0, face.minB),
                    Vec3(face.maxA, y0, face.minB), Vec3(face.maxA, y0, face.maxB),
                    Vec3(0.0, -1.0, 0.0),
                )
            }
            riserTop(ramps, 0, x0, y0, y1, z0, z1, terrain.maxClimbM)?.let { top ->
                val wall = Rect2(z0, z1, y0, top, owner)
                for (face in visibleRects(wall, sideRects(verticalX, q(x0), wall, -1), owner)) {
                    addQuad(
                        Vec3(x0, face.minB, face.minA), Vec3(x0, face.minB, face.maxA),
                        Vec3(x0, face.maxB, face.maxA), Vec3(x0, face.maxB, face.minA),
                        Vec3(-1.0, 0.0, 0.0),
                    )
                }
            }
            riserTop(ramps, 0, x1, y0, y1, z0, z1, terrain.maxClimbM)?.let { top ->
                val wall = Rect2(z0, z1, y0, top, owner)
                for (face in visibleRects(wall, sideRects(verticalX, q(x1), wall, 1), owner)) {
                    addQuad(
                        Vec3(x1, face.minB, face.maxA), Vec3(x1, face.minB, face.minA),
                        Vec3(x1, face.maxB, face.minA), Vec3(x1, face.maxB, face.maxA),
                        Vec3(1.0, 0.0, 0.0),
                    )
                }
            }
            riserTop(ramps, 1, z0, y0, y1, x0, x1, terrain.maxClimbM)?.let { top ->
                val wall = Rect2(x0, x1, y0, top, owner)
                for (face in visibleRects(wall, sideRects(verticalZ, q(z0), wall, -1), owner)) {
                    addQuad(
                        Vec3(face.maxA, face.minB, z0), Vec3(face.minA, face.minB, z0),
                        Vec3(face.minA, face.maxB, z0), Vec3(face.maxA, face.maxB, z0),
                        Vec3(0.0, 0.0, -1.0),
                    )
                }
            }
            riserTop(ramps, 1, z1, y0, y1, x0, x1, terrain.maxClimbM)?.let { top ->
                val wall = Rect2(x0, x1, y0, top, owner)
                for (face in visibleRects(wall, sideRects(verticalZ, q(z1), wall, 1), owner)) {
                    addQuad(
                        Vec3(face.minA, face.minB, z1), Vec3(face.maxA, face.minB, z1),
                        Vec3(face.maxA, face.maxB, z1), Vec3(face.minA, face.maxB, z1),
                        Vec3(0.0, 0.0, 1.0),
                    )
                }
            }
        }

        // Ramps are appended after every block face. The flag array uses that
        // order, so the debug view can colour the exact triangles installed
        // into the Bullet mesh without guessing from the slope.
        val rampTriangleStart = indices.size / 3
        addSurfacePatches(ramps, corners, terrain.maxClimbM, ::addQuad)

        val vertexArray = FloatArray(vertices.size) { vertices[it] }
        val indexArray = IntArray(indices.size) { indices[it] }
        val rampTriangles = BooleanArray(indexArray.size / 3)
        for (triangle in rampTriangleStart until rampTriangles.size) rampTriangles[triangle] = true
        return TerrainMeshPlan(
            originX = originX,
            originY = originY,
            originZ = originZ,
            vertices = vertexArray,
            indices = indexArray,
            ramps = ramps,
            wallQuadCount = wallQuads,
            triangleCount = indexArray.size / 3,
            contentHash = contentHash,
            complete = complete,
            region = region,
            terrainRevisionHash = terrainRevisionHash,
            requestId = requestId,
            runtimeGeneration = runtimeGeneration,
            terrainRevision = terrainRevision,
            rampHeadingBucket = rampGate?.headingBucket ?: -1,
            rampTriangles = rampTriangles,
            cornerPatchCount = corners.size,
        )
    }

    /** A rectangle stored in a [PlaneGrid]; [sign] is the outward side of a vertical face, 0 for horizontal. */
    private class Slab(val rect: Rect2, val sign: Int)

    /**
     * Coplanar rectangles indexed by plane and by 1 m cell.
     *
     * A face only has to compare itself with the rectangles that share a cell
     * with it, instead of with every rectangle on the plane. On a solid layer
     * under a whole window that is the difference between a handful of
     * comparisons per face and several hundred.
     */
    private class PlaneGrid {
        private val planes = HashMap<Long, HashMap<Long, MutableList<Slab>>>()

        fun add(plane: Long, rect: Rect2, sign: Int) {
            val cells = planes.getOrPut(plane) { HashMap() }
            val slab = Slab(rect, sign)
            for (a in cellRange(rect.minA, rect.maxA)) for (b in cellRange(rect.minB, rect.maxB)) {
                cells.getOrPut(cellKey(a, b)) { ArrayList(2) }.add(slab)
            }
        }

        /** Distinct slabs on [plane] that share a cell with [face]. */
        fun near(plane: Long, face: Rect2): List<Slab> {
            val cells = planes[plane] ?: return emptyList()
            val out = ArrayList<Slab>(4)
            for (a in cellRange(face.minA, face.maxA)) for (b in cellRange(face.minB, face.maxB)) {
                val list = cells[cellKey(a, b)] ?: continue
                // A face touches a few cells and each holds a few slabs, so an
                // identity scan beats hashing here.
                for (slab in list) if (out.none { it === slab }) out += slab
            }
            return out
        }

        private fun cellRange(min: Double, max: Double): IntRange {
            val first = floor(min + 1.0e-7).toInt()
            val last = ceil(max - 1.0e-7).toInt() - 1
            return first..maxOf(first, last)
        }

        private fun cellKey(a: Int, b: Int): Long = (a.toLong() shl 32) xor (b.toLong() and 0xffffffffL)
    }

    /** A rectangle in the two coordinates spanning one planar face. */
    private data class Rect2(
        val minA: Double,
        val maxA: Double,
        val minB: Double,
        val maxB: Double,
        val owner: Int,
    )

    /** Return all non-occluded rectangular pieces of [face]. */
    private fun uncoveredRectangles(face: Rect2, covers: List<Rect2>): List<Rect2> {
        if (face.maxA - face.minA <= HEIGHT_EPS || face.maxB - face.minB <= HEIGHT_EPS) return emptyList()
        val relevant = covers.filter {
            it.maxA > face.minA + HEIGHT_EPS && it.minA < face.maxA - HEIGHT_EPS &&
                it.maxB > face.minB + HEIGHT_EPS && it.minB < face.maxB - HEIGHT_EPS
        }
        if (relevant.isEmpty()) return listOf(face)
        // One cover that contains the whole face hides it. This is the common case
        // for a buried face in a solid layer and needs no cut grid at all.
        for (cover in relevant) {
            if (cover.minA <= face.minA + HEIGHT_EPS && cover.maxA >= face.maxA - HEIGHT_EPS &&
                cover.minB <= face.minB + HEIGHT_EPS && cover.maxB >= face.maxB - HEIGHT_EPS
            ) return emptyList()
        }

        val aCuts = (listOf(face.minA, face.maxA) + relevant.flatMap {
            listOf(it.minA.coerceIn(face.minA, face.maxA), it.maxA.coerceIn(face.minA, face.maxA))
        }).distinct().sorted()
        val bCuts = (listOf(face.minB, face.maxB) + relevant.flatMap {
            listOf(it.minB.coerceIn(face.minB, face.maxB), it.maxB.coerceIn(face.minB, face.maxB))
        }).distinct().sorted()
        val visible = ArrayList<Rect2>()
        for (ai in 0 until aCuts.lastIndex) for (bi in 0 until bCuts.lastIndex) {
            val minA = aCuts[ai]; val maxA = aCuts[ai + 1]
            val minB = bCuts[bi]; val maxB = bCuts[bi + 1]
            if (maxA - minA <= HEIGHT_EPS || maxB - minB <= HEIGHT_EPS) continue
            val midA = (minA + maxA) * 0.5
            val midB = (minB + maxB) * 0.5
            if (relevant.any { midA > it.minA - HEIGHT_EPS && midA < it.maxA + HEIGHT_EPS &&
                    midB > it.minB - HEIGHT_EPS && midB < it.maxB + HEIGHT_EPS }) continue
            visible += Rect2(minA, maxA, minB, maxB, face.owner)
        }
        return visible
    }

    /**
     * One piece of generated slope: a planar ramp strip, or the cone that joins
     * two perpendicular strips around an outside corner of a raised block.
     */
    private sealed class Patch {
        abstract val lowY: Double
        abstract val highY: Double
        abstract val minX: Double
        abstract val maxX: Double
        abstract val minZ: Double
        abstract val maxZ: Double

        /** Surface height at a world point, or null outside the footprint. */
        abstract fun heightAt(x: Double, z: Double): Double?

        fun contains(x: Double, z: Double): Boolean =
            x >= minX - HEIGHT_EPS && x <= maxX + HEIGHT_EPS && z >= minZ - HEIGHT_EPS && z <= maxZ + HEIGHT_EPS
    }

    private class StripPatch(val ramp: TerrainRampPlan) : Patch() {
        private val bounds = rampBounds(ramp)
        override val lowY get() = ramp.lowY
        override val highY get() = ramp.highY
        override val minX get() = bounds[0]
        override val maxX get() = bounds[1]
        override val minZ get() = bounds[2]
        override val maxZ get() = bounds[3]
        override fun heightAt(x: Double, z: Double): Double? = rampHeightAt(ramp, x, z)
    }

    /**
     * Elliptic cone around the outside corner ([cornerX], [cornerZ]).
     *
     * [signX] and [signZ] point from the corner into the low quadrant. Along the
     * quadrant's two straight edges the cone has exactly the height of the strip
     * it touches, so the joint is continuous: `h = high - rise * |(dx/runX, dz/runZ)|`.
     */
    private class CornerPatch(
        val cornerX: Double,
        val cornerZ: Double,
        val signX: Int,
        val signZ: Int,
        val runX: Double,
        val runZ: Double,
        override val lowY: Double,
        override val highY: Double,
    ) : Patch() {
        override val minX get() = if (signX > 0) cornerX else cornerX - runX
        override val maxX get() = if (signX > 0) cornerX + runX else cornerX
        override val minZ get() = if (signZ > 0) cornerZ else cornerZ - runZ
        override val maxZ get() = if (signZ > 0) cornerZ + runZ else cornerZ

        override fun heightAt(x: Double, z: Double): Double? {
            if (!contains(x, z)) return null
            val u = abs(x - cornerX) / runX
            val v = abs(z - cornerZ) / runZ
            val distance = kotlin.math.sqrt(u * u + v * v)
            if (distance >= 1.0) return lowY
            return highY - (highY - lowY) * distance
        }
    }

    private fun sameLayer(a: Patch, b: Patch, maxClimb: Double): Boolean =
        max(a.lowY, a.highY) + maxClimb >= min(b.lowY, b.highY) - HEIGHT_EPS &&
            max(b.lowY, b.highY) + maxClimb >= min(a.lowY, a.highY) - HEIGHT_EPS

    private fun overlaps(a: Patch, b: Patch): Boolean =
        a.minX < b.maxX - HEIGHT_EPS && a.maxX > b.minX + HEIGHT_EPS &&
            a.minZ < b.maxZ - HEIGHT_EPS && a.maxZ > b.minZ + HEIGHT_EPS

    /**
     * Triangulate every generated slope as one continuous surface per layer.
     *
     * - Every vertex takes the **highest** same-layer patch at that point, so two
     *   overlapping ramps meet along a continuous crease instead of leaving a
     *   hump or a seam, and the corner cones join perpendicular strips.
     * - A cell is emitted only by the patch that is highest at the cell centre,
     *   so overlapping patches never produce two coincident surfaces.
     * - Strips that overlap nothing keep ~1 block quads (Bullet needs vertices
     *   under the wheels, see [build]); overlapping strips and corners use
     *   [COUPLE_CELL] so the crease is followed closely.
     * - Patches from another layer (a deck several blocks up) are ignored.
     */
    private fun addSurfacePatches(
        ramps: List<TerrainRampPlan>,
        corners: List<Patch>,
        maxClimb: Double,
        addQuad: (Vec3, Vec3, Vec3, Vec3, Vec3) -> Unit,
    ) {
        val patches: List<Patch> = ramps.map { StripPatch(it) } + corners
        if (patches.isEmpty()) return
        val up = Vec3(0.0, 1.0, 0.0)
        val layer = patches.indices.map { index ->
            patches.indices.filter { other -> sameLayer(patches[index], patches[other], maxClimb) }
        }

        fun envelope(index: Int, x: Double, z: Double): Double? {
            var best: Double? = null
            for (other in layer[index]) {
                val height = patches[other].heightAt(x, z) ?: continue
                if (best == null || height > best) best = height
            }
            return best
        }

        fun owns(index: Int, x: Double, z: Double): Boolean {
            val own = patches[index].heightAt(x, z) ?: return false
            for (other in layer[index]) {
                if (other == index) continue
                val height = patches[other].heightAt(x, z) ?: continue
                if (height > own + HEIGHT_EPS) return false
                if (abs(height - own) <= HEIGHT_EPS && other < index) return false
            }
            return true
        }

        for ((index, patch) in patches.withIndex()) {
            val fine = patch is CornerPatch || layer[index].any { it != index && overlaps(patch, patches[it]) }
            val step = if (fine) COUPLE_CELL else 1.0
            var x = patch.minX
            while (x < patch.maxX - 1.0e-9) {
                val x1 = min(patch.maxX, x + step)
                var z = patch.minZ
                while (z < patch.maxZ - 1.0e-9) {
                    val z1 = min(patch.maxZ, z + step)
                    if (owns(index, (x + x1) * 0.5, (z + z1) * 0.5)) {
                        val h00 = envelope(index, x, z)
                        val h01 = envelope(index, x, z1)
                        val h11 = envelope(index, x1, z1)
                        val h10 = envelope(index, x1, z)
                        if (h00 != null && h01 != null && h11 != null && h10 != null) {
                            // A corner cell entirely at floor height would duplicate
                            // the block top that is already in the mesh.
                            val flat = patch is CornerPatch &&
                                maxOf(h00, h01, h11, h10) <= patch.lowY + HEIGHT_EPS
                            if (!flat) {
                                addQuad(Vec3(x, h00, z), Vec3(x, h01, z1), Vec3(x1, h11, z1), Vec3(x1, h10, z), up)
                            }
                        }
                    }
                    z = z1
                }
                x = x1
            }
        }
    }

    /**
     * Outside corners where two perpendicular ramps of the same rise meet.
     *
     * Ramp A climbs along X onto face `x = X0`, ramp B climbs along Z onto face
     * `z = Z0`. They share the outside corner (X0, Z0) of one raised block when
     * A's lateral range starts at Z0 on B's raised side and B's starts at X0 on
     * A's raised side. The low quadrant between them gets a [CornerPatch], but
     * only over real, unobstructed low surface.
     */
    private fun planCorners(boxes: BoxIndex, ramps: List<TerrainRampPlan>): List<Patch> {
        val out = ArrayList<Patch>()
        val seen = HashSet<Long>()
        val alongX = ramps.filter { it.direction == RampDirection.EAST || it.direction == RampDirection.WEST }
        val alongZ = ramps.filter { it.direction == RampDirection.SOUTH || it.direction == RampDirection.NORTH }
        for (a in alongX) for (b in alongZ) {
            if (abs(a.lowY - b.lowY) > HEIGHT_EPS || abs(a.highY - b.highY) > HEIGHT_EPS) continue
            val climbX = if (a.direction == RampDirection.EAST) 1 else -1
            val climbZ = if (b.direction == RampDirection.SOUTH) 1 else -1
            val cornerX = a.highX
            val cornerZ = b.highZ
            // A's strip must start at the corner and run onto B's raised side;
            // B's strip must start at the corner and run onto A's raised side.
            val aStart = if (climbZ > 0) a.lateralMin else a.lateralMax
            val bStart = if (climbX > 0) b.lateralMin else b.lateralMax
            if (abs(aStart - cornerZ) > HEIGHT_EPS || abs(bStart - cornerX) > HEIGHT_EPS) continue
            val key = (q(cornerX) shl 32) xor (q(cornerZ) and 0xffffffffL) xor (q(a.lowY) shl 16)
            if (!seen.add(key)) continue
            val corner = CornerPatch(
                cornerX = cornerX,
                cornerZ = cornerZ,
                signX = -climbX,
                signZ = -climbZ,
                runX = a.runM,
                runZ = b.runM,
                lowY = a.lowY,
                highY = a.highY,
            )
            if (!surfaceCovered(boxes, corner.minX, corner.maxX, corner.minZ, corner.maxZ, a.lowY)) continue
            if (!volumeClear(boxes, corner.minX, corner.maxX, corner.minZ, corner.maxZ, a.lowY, a.highY)) continue
            out += corner
        }
        return out
    }

    /** True when no box occupies the column above [lowY] and below [highY] over the rectangle. */
    private fun volumeClear(
        boxes: BoxIndex,
        minX: Double,
        maxX: Double,
        minZ: Double,
        maxZ: Double,
        lowY: Double,
        highY: Double,
    ): Boolean {
        for (box in boxes.query(minX, maxX, minZ, maxZ)) {
            if (box.maxX <= minX + HEIGHT_EPS || box.minX >= maxX - HEIGHT_EPS) continue
            if (box.maxZ <= minZ + HEIGHT_EPS || box.minZ >= maxZ - HEIGHT_EPS) continue
            if (box.maxY > lowY + HEIGHT_EPS && box.minY < highY - HEIGHT_EPS) return false
        }
        return true
    }

    /**
     * Uniform cell index over the detached boxes. Every geometric query in the
     * planner touches a few cells instead of every box in the window.
     */
    private class BoxIndex(val boxes: List<TerrainBox>) {
        private val cells = HashMap<Long, MutableList<Int>>()

        init {
            boxes.forEachIndexed { index, box ->
                val x0 = floor(box.minX + 1.0e-7).toInt()
                val x1 = ceil(box.maxX - 1.0e-7).toInt() - 1
                val z0 = floor(box.minZ + 1.0e-7).toInt()
                val z1 = ceil(box.maxZ - 1.0e-7).toInt() - 1
                for (x in x0..x1) for (z in z0..z1) cells.getOrPut(key(x, z)) { ArrayList(2) }.add(index)
            }
        }

        fun query(minX: Double, maxX: Double, minZ: Double, maxZ: Double): Sequence<TerrainBox> {
            val x0 = floor(minX - 1.0e-7).toInt()
            val x1 = floor(maxX + 1.0e-7).toInt()
            val z0 = floor(minZ - 1.0e-7).toInt()
            val z1 = floor(maxZ + 1.0e-7).toInt()
            val seen = HashSet<Int>()
            val out = ArrayList<TerrainBox>()
            for (x in x0..x1) for (z in z0..z1) {
                for (index in cells[key(x, z)].orEmpty()) if (seen.add(index)) out += boxes[index]
            }
            return out.asSequence()
        }

        private fun key(x: Int, z: Int): Long = (x.toLong() shl 32) xor (z.toLong() and 0xffffffffL)
    }

    /** Point on the ramp, [along] metres up the climb and [lateral] on the face. */
    private fun rampPoint(ramp: TerrainRampPlan, along: Double, lateral: Double): Vec3 {
        val t = if (ramp.runM <= 1.0e-9) 0.0 else along / ramp.runM
        val y = ramp.lowY + (ramp.highY - ramp.lowY) * t
        return when (ramp.direction) {
            RampDirection.EAST -> Vec3(ramp.lowX + along, y, lateral)
            RampDirection.WEST -> Vec3(ramp.lowX - along, y, lateral)
            RampDirection.SOUTH -> Vec3(lateral, y, ramp.lowZ + along)
            RampDirection.NORTH -> Vec3(lateral, y, ramp.lowZ - along)
        }
    }

    private fun planRamps(
        boxes: BoxIndex,
        terrain: CompiledTerrain,
        gate: RampGate?,
    ): List<TerrainRampPlan> {
        if (terrain.stepM <= MIN_RISE || terrain.maxClimbM <= MIN_RISE) return emptyList()
        val cells = cellTops(boxes.boxes)
        if (cells.isEmpty()) return emptyList()
        val candidates = ArrayList<Candidate>()
        val directions = arrayOf(
            RampDirection.EAST to (1 to 0),
            RampDirection.WEST to (-1 to 0),
            RampDirection.SOUTH to (0 to 1),
            RampDirection.NORTH to (0 to -1),
        )
        for ((cellKey, tops) in cells) {
            val x = cellX(cellKey)
            val z = cellZ(cellKey)
            val sorted = tops.sorted()
            for (lowY in sorted) {
                for ((direction, delta) in directions) {
                    val highX = x + delta.first
                    val highZ = z + delta.second
                    val highTops = cells[cellKey(highX, highZ)] ?: continue
                    val highY = highTops.asSequence()
                        .filter { it > lowY + MIN_RISE && it <= lowY + terrain.maxClimbM + HEIGHT_EPS }
                        .minOrNull() ?: continue
                    val face = when (direction) {
                        RampDirection.EAST -> x + 1.0
                        RampDirection.WEST -> x.toDouble()
                        RampDirection.SOUTH -> z + 1.0
                        RampDirection.NORTH -> z.toDouble()
                    }
                    val lateralCell = if (direction == RampDirection.EAST || direction == RampDirection.WEST) z else x
                    if (gate != null && !gate.allows(direction, face, lateralCell)) continue
                    // Step is the longest ramp, not a minimum. A one-block stair
                    // tread in front of a 3 m authored step still gets the 1 m of
                    // surface that really exists; extending the triangle into air
                    // is what used to leave the riser as a red wall.
                    val available = coveredApproachLength(boxes, direction, face, terrain.stepM, lateralCell, lowY)
                    if (available <= MIN_RISE) continue
                    if (!hasRampClearance(boxes, direction, x, z, face, available, lowY, highY)) continue
                    val foot = rampFootprint(direction, face, available, lateralCell)
                    if (!surfaceCovered(boxes, foot[0], foot[1], foot[2], foot[3], lowY)) continue
                    val high = highFootprint(direction, x, z, lateralCell)
                    if (!surfaceCovered(boxes, high[0], high[1], high[2], high[3], highY)) continue
                    candidates += Candidate(
                        direction = direction,
                        face = face,
                        lowY = lowY,
                        highY = highY,
                        step = available,
                        lateralCell = lateralCell,
                    )
                }
            }
        }
        if (candidates.isEmpty()) return emptyList()

        val grouped = LinkedHashMap<CandidateKey, MutableList<Candidate>>()
        for (candidate in candidates) {
            val key = CandidateKey(
                candidate.direction,
                q(candidate.face),
                q(candidate.lowY),
                q(candidate.highY),
                q(candidate.step),
            )
            grouped.getOrPut(key) { ArrayList() }.add(candidate)
        }

        val ramps = ArrayList<TerrainRampPlan>()
        for ((key, list) in grouped) {
            list.sortBy { it.lateralCell }
            var start = 0
            while (start < list.size) {
                var end = start
                while (end + 1 < list.size && list[end + 1].lateralCell == list[end].lateralCell + 1) end++
                val first = list[start]
                val last = list[end]
                // World coordinates are kept absolute here; the mesh builder
                // subtracts the proxy origin exactly once.
                val worldLatMin = first.lateralCell.toDouble()
                val worldLatMax = last.lateralCell + 1.0
                when (key.direction) {
                    RampDirection.EAST, RampDirection.WEST -> {
                        val lowX = if (key.direction == RampDirection.EAST) first.face - first.step else first.face + first.step
                        ramps += TerrainRampPlan(
                            direction = key.direction,
                            lowX = lowX,
                            lowY = first.lowY,
                            lowZ = (worldLatMin + worldLatMax) * .5,
                            highX = first.face,
                            highY = first.highY,
                            highZ = (worldLatMin + worldLatMax) * .5,
                            lateralMin = worldLatMin,
                            lateralMax = worldLatMax,
                            riseM = first.highY - first.lowY,
                            runM = first.step,
                        )
                    }
                    RampDirection.SOUTH, RampDirection.NORTH -> {
                        val lowZ = if (key.direction == RampDirection.SOUTH) first.face - first.step else first.face + first.step
                        ramps += TerrainRampPlan(
                            direction = key.direction,
                            lowX = (worldLatMin + worldLatMax) * .5,
                            lowY = first.lowY,
                            lowZ = lowZ,
                            highX = (worldLatMin + worldLatMax) * .5,
                            highY = first.highY,
                            highZ = first.face,
                            lateralMin = worldLatMin,
                            lateralMax = worldLatMax,
                            riseM = first.highY - first.lowY,
                            runM = first.step,
                        )
                    }
                }
                start = end + 1
            }
        }
        return ramps
    }

    /**
     * Metres of real [lowY] surface available behind [face], capped by [step].
     *
     * The walk stops at the first slice that is missing or only partly covered.
     * The returned length is the ramp that can be installed without crossing air.
     */
    private fun coveredApproachLength(
        boxes: BoxIndex,
        direction: RampDirection,
        face: Double,
        step: Double,
        lateralCell: Int,
        lowY: Double,
    ): Double {
        if (step <= MIN_RISE) return 0.0
        var covered = 0.0
        while (covered < step - 1.0e-9) {
            val next = min(step, covered + 1.0)
            val slice = approachSlice(direction, face, covered, next, lateralCell)
            if (!surfaceCovered(boxes, slice[0], slice[1], slice[2], slice[3], lowY)) break
            covered = next
        }
        return covered
    }

    /** One slice of the approach, as minX, maxX, minZ, maxZ. [from] is nearer the face. */
    private fun approachSlice(
        direction: RampDirection,
        face: Double,
        from: Double,
        to: Double,
        lateralCell: Int,
    ): DoubleArray {
        val lat0 = lateralCell.toDouble()
        val lat1 = lat0 + 1.0
        return when (direction) {
            RampDirection.EAST -> doubleArrayOf(face - to, face - from, lat0, lat1)
            RampDirection.WEST -> doubleArrayOf(face + from, face + to, lat0, lat1)
            RampDirection.SOUTH -> doubleArrayOf(lat0, lat1, face - to, face - from)
            RampDirection.NORTH -> doubleArrayOf(lat0, lat1, face + from, face + to)
        }
    }

    /**
     * Horizontal footprint of one candidate ramp, as minX, maxX, minZ, maxZ.
     *
     * The whole rectangle must be a real walkable top. A stair or slab that
     * only occupies part of a cell is not that top: ramping across the rest
     * would put a triangle over air, and the road wheel falls through it.
     */
    private fun rampFootprint(
        direction: RampDirection,
        face: Double,
        step: Double,
        lateralCell: Int,
    ): DoubleArray {
        val lat0 = lateralCell.toDouble()
        val lat1 = lat0 + 1.0
        return when (direction) {
            RampDirection.EAST -> doubleArrayOf(face - step, face, lat0, lat1)
            RampDirection.WEST -> doubleArrayOf(face, face + step, lat0, lat1)
            RampDirection.SOUTH -> doubleArrayOf(lat0, lat1, face - step, face)
            RampDirection.NORTH -> doubleArrayOf(lat0, lat1, face, face + step)
        }
    }

    /** The high cell this candidate climbs onto, same axis order as [rampFootprint]. */
    private fun highFootprint(
        direction: RampDirection,
        cellX: Int,
        cellZ: Int,
        lateralCell: Int,
    ): DoubleArray {
        val lat0 = lateralCell.toDouble()
        val lat1 = lat0 + 1.0
        return when (direction) {
            RampDirection.EAST -> doubleArrayOf((cellX + 1).toDouble(), (cellX + 2).toDouble(), lat0, lat1)
            RampDirection.WEST -> doubleArrayOf((cellX - 1).toDouble(), cellX.toDouble(), lat0, lat1)
            RampDirection.SOUTH -> doubleArrayOf(lat0, lat1, (cellZ + 1).toDouble(), (cellZ + 2).toDouble())
            RampDirection.NORTH -> doubleArrayOf(lat0, lat1, (cellZ - 1).toDouble(), cellZ.toDouble())
        }
    }

    /**
     * True when boxes whose top is [topY] cover the whole rectangle.
     *
     * Cell membership is not enough: `cellTops` records a cell as soon as any
     * AABB touches it, including a stair that covers a quarter of the cell.
     */
    private fun surfaceCovered(
        boxes: BoxIndex,
        minX: Double,
        maxX: Double,
        minZ: Double,
        maxZ: Double,
        topY: Double,
    ): Boolean {
        if (maxX - minX <= HEIGHT_EPS || maxZ - minZ <= HEIGHT_EPS) return false
        val covers = ArrayList<Rect2>()
        for (box in boxes.query(minX, maxX, minZ, maxZ)) {
            if (abs(box.maxY - topY) > HEIGHT_EPS) continue
            if (box.maxY - box.minY <= HEIGHT_EPS) continue
            if (box.maxX <= minX + HEIGHT_EPS || box.minX >= maxX - HEIGHT_EPS) continue
            if (box.maxZ <= minZ + HEIGHT_EPS || box.minZ >= maxZ - HEIGHT_EPS) continue
            covers += Rect2(
                box.minX.coerceAtLeast(minX),
                box.maxX.coerceAtMost(maxX),
                box.minZ.coerceAtLeast(minZ),
                box.maxZ.coerceAtMost(maxZ),
                -1,
            )
        }
        if (covers.isEmpty()) return false
        return uncoveredRectangles(Rect2(minX, maxX, minZ, maxZ, -2), covers).isEmpty()
    }

    private fun hasRampClearance(
        boxes: BoxIndex,
        direction: RampDirection,
        cellX: Int,
        cellZ: Int,
        face: Double,
        step: Double,
        lowY: Double,
        highY: Double,
    ): Boolean {
        val range = runCellRange(direction, cellX, cellZ, face, step) ?: return false
        for ((x, z) in range) {
            for (box in boxes.query(x.toDouble(), x + 1.0, z.toDouble(), z + 1.0)) {
                if (box.maxX <= x + HEIGHT_EPS || box.minX >= x + 1.0 - HEIGHT_EPS) continue
                if (box.maxZ <= z + HEIGHT_EPS || box.minZ >= z + 1.0 - HEIGHT_EPS) continue
                if (box.maxY > lowY + HEIGHT_EPS && box.minY < highY - HEIGHT_EPS) return false
            }
        }
        return true
    }

    private fun runCellRange(
        direction: RampDirection,
        cellX: Int,
        cellZ: Int,
        face: Double,
        step: Double,
    ): List<Pair<Int, Int>>? {
        val output = ArrayList<Pair<Int, Int>>()
        when (direction) {
            RampDirection.EAST -> {
                val start = ceil(face - step - .5 - 1.0e-9).toInt()
                for (x in start..cellX) output += x to cellZ
            }
            RampDirection.WEST -> {
                val end = floor(face + step - .5 + 1.0e-9).toInt()
                for (x in cellX..end) output += x to cellZ
            }
            RampDirection.SOUTH -> {
                val start = ceil(face - step - .5 - 1.0e-9).toInt()
                for (z in start..cellZ) output += cellX to z
            }
            RampDirection.NORTH -> {
                val end = floor(face + step - .5 + 1.0e-9).toInt()
                for (z in cellZ..end) output += cellX to z
            }
        }
        return output.ifEmpty { null }
    }


    private fun cellTops(boxes: List<TerrainBox>): Map<Long, List<Double>> {
        val intervals = LinkedHashMap<Long, MutableList<Interval>>()
        for (box in boxes) {
            if (box.maxX - box.minX <= HEIGHT_EPS || box.maxY - box.minY <= HEIGHT_EPS ||
                box.maxZ - box.minZ <= HEIGHT_EPS
            ) continue
            val minX = floor(box.minX + 1.0e-7).toInt()
            val maxX = ceil(box.maxX - 1.0e-7).toInt() - 1
            val minZ = floor(box.minZ + 1.0e-7).toInt()
            val maxZ = ceil(box.maxZ - 1.0e-7).toInt() - 1
            for (x in minX..maxX) for (z in minZ..maxZ) {
                intervals.getOrPut(cellKey(x, z)) { ArrayList() }
                    .add(Interval(box.minY, box.maxY))
            }
        }
        val result = LinkedHashMap<Long, List<Double>>()
        for ((key, value) in intervals) {
            value.sortBy { it.minY }
            val merged = ArrayList<Interval>()
            for (interval in value) {
                val last = merged.lastOrNull()
                if (last != null && interval.minY <= last.maxY + HEIGHT_EPS) {
                    if (interval.maxY > last.maxY) last.maxY = interval.maxY
                } else {
                    merged += Interval(interval.minY, interval.maxY)
                }
            }
            val tops = merged.map { it.maxY }.distinctBy { q(it) }
            if (tops.isNotEmpty()) result[key] = tops
        }
        return result
    }


    /**
     * Top of the vertical wall that remains once a ramp replaces the upper
     * part of a riser. Returns null when the whole face is covered, and the
     * original face top when no ramp applies. Only the covered upper part is
     * removed; the rock below the ramp still has a real vertical side.
     */
    private fun riserTop(
        ramps: List<TerrainRampPlan>,
        axis: Int,
        plane: Double,
        minY: Double,
        maxY: Double,
        minOther: Double,
        maxOther: Double,
        maxClimb: Double,
    ): Double? {
        var top = maxY
        for (ramp in ramps) {
            val matches = when {
                axis == 0 && ramp.direction == RampDirection.EAST -> abs(plane - ramp.highX) <= HEIGHT_EPS
                axis == 0 && ramp.direction == RampDirection.WEST -> abs(plane - ramp.highX) <= HEIGHT_EPS
                axis == 1 && ramp.direction == RampDirection.SOUTH -> abs(plane - ramp.highZ) <= HEIGHT_EPS
                axis == 1 && ramp.direction == RampDirection.NORTH -> abs(plane - ramp.highZ) <= HEIGHT_EPS
                else -> false
            }
            if (!matches) continue
            if (minOther < ramp.lateralMin - HEIGHT_EPS || maxOther > ramp.lateralMax + HEIGHT_EPS) continue
            if (maxY <= ramp.lowY + HEIGHT_EPS || minY >= ramp.highY - HEIGHT_EPS) continue
            // At its own riser a ramp is at its full height. With the upper
            // envelope it is either the surface there or lies under a higher
            // same-layer surface; in both cases the covered riser is not a wall.
            top = min(top, ramp.lowY)
        }
        if (top <= minY + HEIGHT_EPS) return null
        return top
    }

    /** minX, maxX, minZ, maxZ of one ramp footprint. */
    private fun rampBounds(ramp: TerrainRampPlan): DoubleArray = when (ramp.direction) {
        RampDirection.EAST, RampDirection.WEST -> doubleArrayOf(
            min(ramp.lowX, ramp.highX), max(ramp.lowX, ramp.highX), ramp.lateralMin, ramp.lateralMax,
        )
        RampDirection.SOUTH, RampDirection.NORTH -> doubleArrayOf(
            ramp.lateralMin, ramp.lateralMax, min(ramp.lowZ, ramp.highZ), max(ramp.lowZ, ramp.highZ),
        )
    }

    /** Height of [ramp] at a world point, or null outside its footprint. */
    private fun rampHeightAt(ramp: TerrainRampPlan, x: Double, z: Double): Double? {
        val bounds = rampBounds(ramp)
        if (x < bounds[0] - HEIGHT_EPS || x > bounds[1] + HEIGHT_EPS) return null
        if (z < bounds[2] - HEIGHT_EPS || z > bounds[3] + HEIGHT_EPS) return null
        val t = when (ramp.direction) {
            RampDirection.EAST -> if (ramp.runM <= 1.0e-9) 0.0 else (x - ramp.lowX) / ramp.runM
            RampDirection.WEST -> if (ramp.runM <= 1.0e-9) 0.0 else (ramp.lowX - x) / ramp.runM
            RampDirection.SOUTH -> if (ramp.runM <= 1.0e-9) 0.0 else (z - ramp.lowZ) / ramp.runM
            RampDirection.NORTH -> if (ramp.runM <= 1.0e-9) 0.0 else (ramp.lowZ - z) / ramp.runM
        }.coerceIn(0.0, 1.0)
        return ramp.lowY + (ramp.highY - ramp.lowY) * t
    }

    private fun overlapsXZ(a: TerrainRampPlan, b: TerrainRampPlan): Boolean {
        val aa = rampBounds(a)
        val bb = rampBounds(b)
        return aa[0] < bb[1] - HEIGHT_EPS && aa[1] > bb[0] + HEIGHT_EPS &&
            aa[2] < bb[3] - HEIGHT_EPS && aa[3] > bb[2] + HEIGHT_EPS
    }

    private fun heightBandsOverlap(a: TerrainRampPlan, b: TerrainRampPlan, maxClimb: Double): Boolean {
        val a0 = min(a.lowY, a.highY)
        val a1 = max(a.lowY, a.highY)
        val b0 = min(b.lowY, b.highY)
        val b1 = max(b.lowY, b.highY)
        return a1 + maxClimb >= b0 - HEIGHT_EPS && b1 + maxClimb >= a0 - HEIGHT_EPS
    }

    private fun cellKey(x: Int, z: Int): Long =
        (x.toLong() shl 32) xor (z.toLong() and 0xffffffffL)

    private fun cellX(key: Long): Int = (key shr 32).toInt()
    private fun cellZ(key: Long): Int = key.toInt()

    private fun q(value: Double): Long = (value * QUANT).roundToLong()
}
