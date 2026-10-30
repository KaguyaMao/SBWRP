package com.atsuishio.superbwarfare.physics.tracked

import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * S3.2: optional one-block step assist for tracked vehicles.
 *
 * ## Why this exists
 *
 * A real tracked vehicle climbs a kerb by laying its belt over the edge. The
 * physics does that on its own for a shallow lip, but a full one-block step
 * needs the lead wheel to be lifted enough to reach the top surface. Without
 * help the belt simply pushes against the vertical face.
 *
 * ## What this deliberately is not
 *
 * - **Not a height field.** It reads the real `VoxelShape` collision boxes of the
 *   blocks ahead and only ever touches bodies the vehicle already owns.
 * - **Not an impulse.** There is no `liftImpulse` and no force applied outside
 *   the existing run-terrain contact. The only thing this policy may change is
 *   the tangential target on a contact the solver already created.
 * - **Not a two-block climber.** A step taller than [MAX_STEP_HEIGHT] is refused
 *   outright; the vehicle must go around or be blocked.
 * - **Not a normal rewrite.** Terrain contact normals, points, distances and
 *   positions are never modified. MachineMax's `LocalHeightField.solveContact`
 *   path is explicitly rejected.
 *
 * The caller is expected to have already verified basic AABB collision with the
 * assist disabled; this class only classifies real block boxes. Whether a
 * contact actually receives a target is decided by the runtime, which owns the
 * manifold point and the side-specific state.
 */
object TrackedStepAssist {

    /** Highest single vertical step this assist will ever help with, in blocks. */
    const val MAX_STEP_HEIGHT = 1.0

    /**
     * Penetration allowance when measuring a step, metres.
     *
     * The rise is measured from the wheel's lowest point to the block's top.
     * A belt resting on the floor penetrates a few centimetres, so an exact
     * one-block ledge reads as slightly *more* than 1.0 and would be refused by
     * the strict gate. The allowance keeps a real one-block step eligible while
     * still refusing anything that is meaningfully taller (a 1.5-block wall is
     * far outside it).
     */
    const val STEP_TOLERANCE = 0.08

    /**
     * Cumulative rise of one candidate ramp, blocks. MCSP's `MaxClimb`.
     *
     * This is a *separate* bound from [MAX_STEP_HEIGHT]: a two-block vertical
     * wall is refused because no single face may exceed one block, even though
     * its total rise equals this limit.
     */
    const val MAX_CLIMB = 2.0

    /** Shortest usable run, blocks. MCSP's `MinRun`. */
    const val MIN_RUN = 0.25

    /** Longest usable run, blocks. MCSP's `MaxRun`. */
    const val MAX_RUN = 2.5

    /** Speed below which no assist is allowed, m/s. MCSP's `MinSpeed`. */
    const val MIN_SPEED_MPS = 3.0

    /** Speed at which the assist reaches full weight, m/s. MCSP's `FullSpeed`. */
    const val FULL_SPEED_MPS = 10.5

    /** Probe reach ahead of the sampled contact, metres. */
    private const val PROBE_REACH = 0.45

    /** Lateral half width used when a call site cannot supply a real belt width. */
    private const val DEFAULT_LATERAL_HALF_WIDTH = 0.30

    /**
     * Ceiling on the vertical part of a real-contact assist target, m/s.
     *
     * This is an SBW adaptation: the solver gets a tangential target on the
     * existing contact, never a vertical impulse, so the value bounds how fast
     * a belt may crawl up a face rather than how hard the hull is pushed. The
     * earlier 2.5 m/s value was a candidate that lifted the hull off the
     * fixture; 0.8 m/s is a bounded crawl. Still unfrozen, not a 99A value.
     */
    const val MAX_ASSIST_CLIMB_MPS = 0.8

    /** Weight of the upward tangent relative to the forward tangent. */
    private const val CLIMB_TANGENT_BIAS = 1.0

    /** How far a contact point may sit outside a candidate box and still match. */
    private const val CONTACT_MATCH_XZ = 0.35
    private const val CONTACT_MATCH_Y = 0.20

    /** Stable identity of one real block collision box. */
    data class TerrainCandidateKey(
        val minX: Double,
        val minY: Double,
        val minZ: Double,
        val maxX: Double,
        val maxY: Double,
        val maxZ: Double,
    ) {
        companion object {
            fun of(bounds: AABB): TerrainCandidateKey = TerrainCandidateKey(
                minX = bounds.minX,
                minY = bounds.minY,
                minZ = bounds.minZ,
                maxX = bounds.maxX,
                maxY = bounds.maxY,
                maxZ = bounds.maxZ,
            )
        }
    }

    /**
     * Identity of the **step face** the belt actually meets.
     *
     * One one-block step built from nine stone blocks is nine real collision
     * boxes but a single ledge. Keying the per-substep consumption budget by the
     * box would let every run segment pressing a different column write its own
     * target for the same face, multiplying the assist by the number of columns.
     * The face is therefore identified by its position along the approach
     * direction plus the height of the surface it presents.
     *
     * Quantised to millimetres so two boxes of one authored ledge share a key
     * despite float noise, while two successive steps stay distinct.
     */
    data class StepFaceKey(val faceMm: Long, val topMm: Long)

    /**
     * Face identity of [candidate] for an approach along [forward].
     *
     * Only the approach direction and the box itself are used, never a wheel
     * position, so every wheel and every run segment looking at the same ledge
     * produces the same key.
     */
    fun faceKey(candidate: TerrainCandidate, forward: Vec3): StepFaceKey {
        val horizontal = Vec3(forward.x, 0.0, forward.z)
        val direction = if (horizontal.horizontalDistanceSqr() < 1.0e-8) {
            Vec3(0.0, 0.0, 1.0)
        } else {
            horizontal.normalize()
        }
        val bounds = candidate.bounds
        val centerX = (bounds.minX + bounds.maxX) * 0.5
        val centerZ = (bounds.minZ + bounds.maxZ) * 0.5
        val halfExtentX = (bounds.maxX - bounds.minX) * 0.5
        val halfExtentZ = (bounds.maxZ - bounds.minZ) * 0.5
        val halfRun = abs(direction.x) * halfExtentX + abs(direction.z) * halfExtentZ
        val nearFace = centerX * direction.x + centerZ * direction.z - halfRun
        return StepFaceKey(mm(nearFace), mm(bounds.maxY))
    }

    private fun mm(value: Double): Long =
        if (!value.isFinite()) 0L else Math.round(value * 1000.0)

    /**
     * True when two boxes share ground area, so the upper one is the same
     * obstacle continuing rather than a separate block behind it.
     */
    private fun overlapsHorizontally(a: AABB, b: AABB): Boolean =
        a.minX < b.maxX - 1.0e-4 && a.maxX > b.minX + 1.0e-4 &&
            a.minZ < b.maxZ - 1.0e-4 && a.maxZ > b.minZ + 1.0e-4

    /**
     * True when two boxes cover essentially the same ground area.
     *
     * A wall course sits on a block of its own size; a legal step sits on the
     * much wider floor. Comparing the smaller box against the larger one keeps
     * a one-block step on a floor legal while rejecting the second course of a
     * wall.
     */
    private fun similarFootprint(a: AABB, b: AABB): Boolean {
        val ax = a.maxX - a.minX
        val az = a.maxZ - a.minZ
        val bx = b.maxX - b.minX
        val bz = b.maxZ - b.minZ
        if (ax <= 0.0 || az <= 0.0 || bx <= 0.0 || bz <= 0.0) return false
        val ratioX = minOf(ax, bx) / maxOf(ax, bx)
        val ratioZ = minOf(az, bz) / maxOf(az, bz)
        return ratioX > 0.6 && ratioZ > 0.6
    }


    /**
     * One real block box ahead of the running gear.
     *
     * [run] is the box's horizontal extent along the approach direction, not the
     * distance to its near face: it is the usable length of the step surface,
     * which is what MCSP's `MinRun`/`MaxRun` window gates.
     */
    data class TerrainCandidate(
        val bounds: AABB,
        val rise: Double,
        val run: Double,
        val distance: Double,
        /**
         * True when this box sits on another box of essentially the same
         * footprint, i.e. it is one course of a wall rather than a ledge on the
         * ground.
         *
         * Rise alone cannot decide this: after the vehicle climbs the first
         * block of a two-block wall, the upper block measures only one block
         * from the new support and looks exactly like a legal step. A step, by
         * contrast, rests on a much wider floor. The footprint ratio is what
         * separates the two.
         */
        val stackedOnSameFootprint: Boolean = false,
    ) {
        val key: TerrainCandidateKey get() = TerrainCandidateKey.of(bounds)
    }

    /**
     * A tangential target derived from one real contact.
     *
     * There is deliberately no vertical impulse, position or normal field: the
     * only thing the runtime may write is a contact-motion target along this
     * unit tangent.
     */
    data class ContactAssist(
        val tangent: Vec3,
        val targetSpeedMps: Double,
        val weight: Double,
    )

    /** Result of one assist evaluation. */
    data class Assessment(
        /** True when a climbable step is directly ahead. */
        val stepAhead: Boolean,
        /** Measured step rise, metres; zero when there is no step. */
        val stepHeight: Double,
        /** True when the step is too tall to assist and must be refused. */
        val refused: Boolean,
        /** Why the step was refused, for logging; null when not refused. */
        val reason: String? = null,
    ) {
        companion object {
            val NONE = Assessment(stepAhead = false, stepHeight = 0.0, refused = false)
        }
    }

    /**
     * Inspect pre-sampled terrain just ahead of [contactPoint] along [forward].
     *
     * [candidates] are the collision boxes already collected on the game thread.
     * This function must stay free of `Level` access: the physics thread runs
     * while the server thread is inside the level tick, and reading block states
     * there is not thread-safe. Sampling belongs to the caller.
     */
    fun assess(
        contactPoint: Vec3,
        forward: Vec3,
        candidates: List<AABB>,
        lateralHalfWidth: Double = 0.30,
    ): Assessment {
        val found = candidatesAhead(contactPoint, forward, candidates, lateralHalfWidth)
        if (found.isEmpty()) return Assessment.NONE
        val refused = found.firstOrNull { it.rise > MAX_STEP_HEIGHT + STEP_TOLERANCE }
        if (refused != null) {
            return Assessment(
                stepAhead = true,
                stepHeight = refused.rise,
                refused = true,
                reason = "step ${"%.2f".format(refused.rise)} m exceeds $MAX_STEP_HEIGHT m",
            )
        }
        val best = found.maxOf { it.rise }
        return Assessment(true, best, false)
    }

    /**
     * Real block boxes that lie ahead of [contactPoint] and overlap the belt
     * laterally.
     *
     * This is pure geometry over already-sampled AABBs. It never invents a
     * surface and never merges separate boxes, so a two-block wall stays two
     * metres of rise instead of becoming one tall ramp.
     */
    fun candidatesAhead(
        contactPoint: Vec3,
        forward: Vec3,
        boxes: List<AABB>,
        lateralHalfWidth: Double = DEFAULT_LATERAL_HALF_WIDTH,
    ): List<TerrainCandidate> {
        if (!forward.x.isFinite() || !forward.z.isFinite()) return emptyList()
        if (boxes.isEmpty()) return emptyList()
        val horizontal = Vec3(forward.x, 0.0, forward.z)
        if (horizontal.horizontalDistanceSqr() < 1.0e-8) return emptyList()
        val direction = horizontal.normalize()
        val lateralX = -direction.z
        val lateralZ = direction.x
        val halfWidth = if (lateralHalfWidth.isFinite() && lateralHalfWidth > 0.0) {
            lateralHalfWidth
        } else {
            DEFAULT_LATERAL_HALF_WIDTH
        }

        val found = ArrayList<TerrainCandidate>()
        for (bounds in boxes) {
            if (!bounds.minX.isFinite() || !bounds.maxX.isFinite() ||
                !bounds.minY.isFinite() || !bounds.maxY.isFinite() ||
                !bounds.minZ.isFinite() || !bounds.maxZ.isFinite()
            ) {
                continue
            }
            val halfX = (bounds.maxX - bounds.minX) * 0.5
            val halfZ = (bounds.maxZ - bounds.minZ) * 0.5
            val centerX = (bounds.minX + bounds.maxX) * 0.5
            val centerZ = (bounds.minZ + bounds.maxZ) * 0.5
            val offsetX = centerX - contactPoint.x
            val offsetZ = centerZ - contactPoint.z
            val centerForward = offsetX * direction.x + offsetZ * direction.z
            val centerLateral = offsetX * lateralX + offsetZ * lateralZ
            val halfRun = abs(direction.x) * halfX + abs(direction.z) * halfZ
            val halfLateral = abs(lateralX) * halfX + abs(lateralZ) * halfZ
            val nearFace = centerForward - halfRun
            // Entirely behind the contact: not an obstacle the belt can be
            // pressed against.
            if (nearFace < -halfLateral) continue
            if (centerForward - halfRun > MAX_RUN + 1.0e-6) continue
            if (abs(centerLateral) > halfWidth + halfLateral + 1.0e-6) continue
            val run = halfRun * 2.0
            if (run < 1.0e-4) continue
            val rise = bounds.maxY - contactPoint.y
            if (rise <= 0.02) continue
            // A box stacked on an equally-sized box is one course of a wall,
            // not a ledge. After climbing the first block of a two-block wall,
            // the upper block measures exactly one block of rise from the new
            // support, so a rise-only gate would mount it and the vehicle would
            // ratchet up a wall. A real step rests on a wider floor instead.
            val stacked = boxes.any { other ->
                other !== bounds &&
                    abs(other.maxY - bounds.minY) < 0.06 &&
                    overlapsHorizontally(other, bounds) &&
                    similarFootprint(other, bounds)
            }
            found += TerrainCandidate(
                bounds = bounds,
                rise = rise,
                run = run,
                distance = nearFace.coerceAtLeast(0.0),
                stackedOnSameFootprint = stacked,
            )
        }
        found.sortBy { it.distance }
        return found
    }

    /**
     * True when [contactPoint] is on or immediately next to [candidate].
     *
     * This is the ownership check that stops a box sampled in front of a wheel
     * from driving an unrelated contact elsewhere on the vehicle.
     */
    fun contactMatches(contactPoint: Vec3, candidate: TerrainCandidate): Boolean {
        if (!contactPoint.x.isFinite() || !contactPoint.y.isFinite() || !contactPoint.z.isFinite()) {
            return false
        }
        val bounds = candidate.bounds
        return contactPoint.x >= bounds.minX - CONTACT_MATCH_XZ &&
            contactPoint.x <= bounds.maxX + CONTACT_MATCH_XZ &&
            contactPoint.z >= bounds.minZ - CONTACT_MATCH_XZ &&
            contactPoint.z <= bounds.maxZ + CONTACT_MATCH_XZ &&
            contactPoint.y >= bounds.minY - CONTACT_MATCH_Y &&
            contactPoint.y <= bounds.maxY + CONTACT_MATCH_Y
    }

    /**
     * Climbability gate for one already-matched candidate.
     *
     * [totalClimb] is measured by the caller from the boxes it actually grouped
     * into this approach; this function does not accumulate anything itself.
     */
    fun candidateGate(candidate: TerrainCandidate, totalClimb: Double, forwardSpeed: Double): Boolean {
        if (candidate.stackedOnSameFootprint) return false
        if (candidate.rise > MAX_STEP_HEIGHT + STEP_TOLERANCE) return false
        return rampGate(candidate.rise, totalClimb, candidate.run, forwardSpeed)
    }

    /**
     * True when an approach is refused as a whole.
     *
     * A two-block wall is two real boxes. The lower box alone looks like a
     * legal one-block step, so assisting it would mount the first block of a
     * wall that must be refused. Any box above the single-step gate therefore
     * vetoes the entire approach, not just itself.
     */
    fun approachRefused(candidates: List<TerrainCandidate>): Boolean =
        candidates.any { it.rise > MAX_STEP_HEIGHT + STEP_TOLERANCE || it.stackedOnSameFootprint }

    /**
     * Turn one real contact into a bounded tangential target.
     *
     * The returned tangent is the chassis forward direction projected onto the
     * real contact plane, with a bounded upward component so a belt pressed
     * against a riser can crawl up the face. It is **not** a vertical impulse:
     * the runtime writes it only as `contactMotion1` on the contact Bullet
     * created, along `lateralFrictionDir1`.
     *
     * Returns `null` whenever the contact cannot carry a legal target:
     * a non-finite normal, a vertical face with no forward projection, a
     * refused candidate, an absent driver target, or a speed outside the gate.
     */
    fun contactAssist(
        normal: Vec3,
        chassisForward: Vec3,
        candidate: TerrainCandidate,
        totalClimb: Double,
        forwardSpeed: Double,
        baseTargetSpeedMps: Double,
    ): ContactAssist? {
        if (!normal.x.isFinite() || !normal.y.isFinite() || !normal.z.isFinite()) return null
        if (normal.lengthSqr() < 1.0e-8) return null
        val n = normal.normalize()
        if (!candidateGate(candidate, totalClimb, forwardSpeed)) return null
        if (!baseTargetSpeedMps.isFinite() || baseTargetSpeedMps <= 0.0) return null
        if (!chassisForward.x.isFinite() || !chassisForward.y.isFinite() || !chassisForward.z.isFinite()) {
            return null
        }
        val projected = chassisForward.subtract(n.scale(chassisForward.dot(n)))
        // A vertical riser has a horizontal normal, so the chassis forward is
        // perpendicular to the face and projects to zero. An earlier version
        // invented the upward tangent in that case. That is a ladder: it drives
        // the belt straight up a face the vehicle is only pressed against, and
        // in testing it lifted and flipped the hull off the fixture. The assist
        // may only *bias an already usable contact* uphill; it must never
        // create a climb direction on a face that has none.
        if (projected.lengthSqr() < 1.0e-8) return null
        val forwardTangent = projected.normalize()
        val upCandidate = Vec3(0.0, 1.0, 0.0)
            .subtract(n.scale(n.y))
        val climb = if (upCandidate.lengthSqr() < 1.0e-8) {
            Vec3.ZERO
        } else {
            upCandidate.normalize()
        }
        if (climb.y <= 1.0e-6) return null

        val weightGate = speedWeight(forwardSpeed) * forwardSpeedGate(forwardSpeed)
        if (weightGate <= 0.0) return null
        val grade = (candidate.rise / candidate.run.coerceAtLeast(MIN_RUN)).coerceIn(0.0, 1.0)
        val weight = (weightGate * grade).coerceIn(0.0, 1.0)
        if (weight <= 0.0) return null

        val tangent = forwardTangent.add(climb.scale(CLIMB_TANGENT_BIAS))
        if (tangent.lengthSqr() < 1.0e-8) return null
        val unit = tangent.normalize()
        // A target is a speed, not a position: bound the climb component so a
        // fast approach cannot ask the solver to fling the belt up the face.
        val climbSpeed = unit.y * baseTargetSpeedMps
        val scale = if (climbSpeed > MAX_ASSIST_CLIMB_MPS) {
            MAX_ASSIST_CLIMB_MPS / climbSpeed
        } else {
            1.0
        }
        return ContactAssist(
            tangent = unit,
            targetSpeedMps = baseTargetSpeedMps * weight * scale,
            weight = weight,
        )
    }

    /**
     * Top of the real terrain the wheel is standing on, metres.
     *
     * Looks only at boxes under the wheel's own footprint and at or below its
     * bottom, so a step in front cannot raise the reference and make itself
     * disappear. Returns null when no support box is found (the wheel is over a
     * pit), in which case the caller must fall back to the wheel bottom.
     */
    fun supportHeightUnder(
        boxes: List<AABB>,
        centerX: Double,
        centerZ: Double,
        halfWidth: Double,
        halfLength: Double,
        wheelBottomY: Double,
    ): Double? {
        var best: Double? = null
        for (bounds in boxes) {
            if (bounds.maxY > wheelBottomY + 0.12) continue
            if (centerX < bounds.minX - halfWidth || centerX > bounds.maxX + halfWidth) continue
            if (centerZ < bounds.minZ - halfLength || centerZ > bounds.maxZ + halfLength) continue
            if (best == null || bounds.maxY > best!!) best = bounds.maxY
        }
        return best
    }

    /**
     * Sample the real block collision boxes around [center] on the game thread.
     *
     * This is the only part that touches a `Level`. The result is plain AABB data
     * that the physics thread can consume without a level reference.
     */
    fun sampleTerrain(
        level: Level,
        center: Vec3,
        forward: Vec3,
        radius: Double = 1.5,
        context: CollisionContext = CollisionContext.empty(),
    ): List<AABB> {
        if (!forward.x.isFinite() || !forward.z.isFinite()) return emptyList()
        val direction = if (forward.horizontalDistanceSqr() < 1.0e-6) Vec3(0.0, 0.0, 1.0) else forward.normalize()
        val probe = center.add(direction.scale(PROBE_REACH))
        val minX = floor(probe.x - radius).toInt()
        val maxX = floor(probe.x + radius).toInt()
        val minZ = floor(probe.z - radius).toInt()
        val maxZ = floor(probe.z + radius).toInt()
        val minY = floor(center.y - MAX_STEP_HEIGHT - 1.0).toInt()
        val maxY = ceil(center.y + MAX_STEP_HEIGHT + 1.0).toInt()
        val out = ArrayList<AABB>()
        for (x in minX..maxX) {
            for (z in minZ..maxZ) {
                for (y in minY..maxY) {
                    val pos = BlockPos(x, y, z)
                    val state = level.getBlockState(pos)
                    if (state.isAir) continue
                    val shape = state.getCollisionShape(level, pos, context)
                    if (shape.isEmpty) continue
                    // Every collision box of the shape, not its merged bounds: a
                    // stair or a fence would otherwise be flattened into a solid
                    // cuboid whose top is a step face that does not exist.
                    for (box in shape.toAabbs()) {
                        out += box.move(pos)
                    }
                }
            }
        }
        return out
    }

    /**
     * Removing this API is deliberate.
     *
     * The S3.2 design does not permit an independent vertical impulse, a hull
     * lift or a position correction. A step is assisted only through the
     * tangential target returned by [contactAssist] on a contact Bullet already
     * created. Any caller that needs to "lift the vehicle" is asking for the
     * rejected design and must be rewritten, not given a helper here.
     */
    @Deprecated(
        "Independent vertical lift is forbidden; use contactAssist on a real contact",
        level = DeprecationLevel.ERROR,
    )
    fun liftImpulse(
        @Suppress("UNUSED_PARAMETER") assessment: Assessment,
        @Suppress("UNUSED_PARAMETER") bodyMassKg: Float,
        @Suppress("UNUSED_PARAMETER") gravity: Double = 9.81,
    ): Double = error("liftImpulse is removed: S3.2 assists real contacts only")

    /** True when [rise] is within the assisted range. */
    fun isClimbable(rise: Double): Boolean =
        rise > 0.02 && rise <= MAX_STEP_HEIGHT + STEP_TOLERANCE

    /**
     * MCSP `PhysRamps` gate, evaluated against one measured candidate.
     *
     * The semantics are kept explicit because the native source is unavailable:
     * a single face may not exceed [MAX_STEP_HEIGHT], while the accumulated rise
     * of one approach is bounded by [MAX_CLIMB] and its run by
     * [MIN_RUN]..[MAX_RUN]. Speed is gated linearly between [MIN_SPEED_MPS] and
     * [FULL_SPEED_MPS]; the interpolation is an SBW adaptation, not a recovered
     * MCSP formula.
     */
    fun rampGate(
        stepRise: Double,
        totalClimb: Double,
        run: Double,
        forwardSpeed: Double,
    ): Boolean {
        if (!stepRise.isFinite() || !totalClimb.isFinite() || !run.isFinite() || !forwardSpeed.isFinite()) return false
        if (stepRise <= 0.02 || stepRise > MAX_STEP_HEIGHT + STEP_TOLERANCE) return false
        if (totalClimb <= 0.0 || totalClimb > MAX_CLIMB + 1.0e-6) return false
        if (run < MIN_RUN - 1.0e-6 || run > MAX_RUN + 1.0e-6) return false
        return speedWeight(forwardSpeed) > 0.0
    }

    /**
     * Assist weight in `0..1` from forward speed.
     *
     * 0 below [MIN_SPEED_MPS], 1 at or above [FULL_SPEED_MPS], linear between.
     * Reverse is not a separate case: the caller passes the speed along the
     * actual travel direction, so a reverse approach is gated the same way.
     */
    fun speedWeight(forwardSpeed: Double): Double {
        if (!forwardSpeed.isFinite() || forwardSpeed < MIN_SPEED_MPS) return 0.0
        if (forwardSpeed >= FULL_SPEED_MPS) return 1.0
        return (forwardSpeed - MIN_SPEED_MPS) / (FULL_SPEED_MPS - MIN_SPEED_MPS)
    }

    /**
     * How much of the vehicle's forward motion may be turned into a lift.
     *
     * A stationary vehicle must not levitate: the assist only contributes while
     * the belt is actually pushing into the step.
     */
    fun forwardSpeedGate(forwardSpeed: Double): Double {
        if (!forwardSpeed.isFinite() || forwardSpeed <= 0.0) return 0.0
        // Ramp in over the first 0.5 m/s so a crawl still climbs, then stay at 1.
        return min(forwardSpeed / 0.5, 1.0)
    }
}
