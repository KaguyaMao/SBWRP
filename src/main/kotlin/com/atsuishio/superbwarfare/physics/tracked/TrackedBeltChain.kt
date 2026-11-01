package com.atsuishio.superbwarfare.physics.tracked

import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Candidate parameters of the anchor-driven belt chain. Not frozen limits.
 *
 * Every value is a proposal for review. They are inputs so a prototype can
 * sweep them; none is baked into the compiler.
 */
data class BeltChainPolicy(
    /** Length of one bottom unit in visible link pitches. */
    val unitPitches: Double = 2.0,
    /**
     * How far one pin may bend away from its rest angle, degrees, either
     * direction. The limit is relative: a corner that rests at 90 degrees may
     * move between 50 and 130, a straight pin between -40 and 40.
     */
    val maxBendDegrees: Double = 40.0,
    /** Number of equal pieces the top return run is cut into so it can sag. */
    val topPieces: Int = 3,
)

enum class BeltSegmentKind { FRONT_UP, FRONT_LOW, BOTTOM, REAR_LOW, REAR_UP, TOP }

/**
 * One rigid piece of the chain. Both ends are pins shared with a neighbour, so
 * the chain has no gap by construction: segment `i` ends exactly where segment
 * `i + 1` starts.
 */
data class BeltChainSegment(
    val index: Int,
    val kind: BeltSegmentKind,
    /** Pin positions on the belt centre line, chassis frame, metres. */
    val start: Vec3,
    val end: Vec3,
    /** Wheel that carries the start pin, or -1 for a free interior pin. */
    val startWheel: Int,
    val endWheel: Int,
    val massKg: Double,
    /** Box half extents: lateral, thickness, along the segment (with end overlap). */
    val halfExtents: Vec3,
) {
    val lengthM: Double get() = hypot(end.y - start.y, end.z - start.z)
    val center: Vec3 get() = Vec3(
        (start.x + end.x) * 0.5, (start.y + end.y) * 0.5, (start.z + end.z) * 0.5,
    )

    /** Rotation about the lateral axis that turns +Z onto the segment direction. */
    val pitchRad: Double get() = atan2(-(end.y - start.y), end.z - start.z)
}

data class CompiledBeltChain(
    val side: TrackedSide,
    val segments: List<BeltChainSegment>,
    val policy: BeltChainPolicy,
    val restLengthM: Double,
    val bottomUnits: Int,
    val bottomUnitLengthM: Double,
) {
    /** Cyclic neighbour: the segment that starts where [index] ends. */
    fun next(index: Int): BeltChainSegment = segments[(index + 1) % segments.size]

    /** Rest bend at the pin where [index] ends, radians; zero for a straight pin. */
    fun restBendRad(index: Int): Double = TrackedBeltChainCompiler.bendRad(segments[index], next(index))

    /** Allowed bend range at the pin where [index] ends, radians, as (min, max). */
    fun bendRange(index: Int): Pair<Double, Double> {
        val rest = restBendRad(index)
        val limit = Math.toRadians(policy.maxBendDegrees)
        return (rest - limit) to (rest + limit)
    }

    /** Largest distance between a segment's end pin and its neighbour's start pin. Zero by construction. */
    val worstPinGapM: Double
        get() = segments.indices.maxOf { index ->
            val a = segments[index].end
            val b = next(index).start
            hypot(hypot(a.x - b.x, a.y - b.y), a.z - b.z)
        }

    val massKg: Double get() = segments.sumOf { it.massKg }
}

/**
 * Builds the belt chain from the authored envelope anchors.
 *
 * The anchors give one closed belt outline. This pass only decides where the
 * pins go; it does not know about terrain or suspension, which is what lets the
 * runtime fold the chain wherever contact asks for it without changing bodies.
 *
 * Layout, front to rear along the bottom (chassis +Z is forward):
 *
 * ```
 *   idlerTop ----------- TOP (n pieces) ----------- driveTop
 *      |                                                |
 *   FRONT_UP                                         REAR_UP
 *      |                                                |
 *   idlerLow                                         driveLow
 *       \                                              /
 *    FRONT_LOW                                    REAR_LOW
 *         \                                          /
 *       bottomFront -- BOTTOM units (equal) -- bottomRear
 * ```
 */
object TrackedBeltChainCompiler {

    fun compile(
        side: CompiledTrackSide,
        pitchM: Double,
        policy: BeltChainPolicy = BeltChainPolicy(),
        massBudgetKg: Double = side.runMassTotalKg,
    ): CompiledBeltChain {
        require(side.anchors.size == 6) {
            "${side.side} needs exactly six envelope anchors for a belt chain, has ${side.anchors.size}"
        }
        require(pitchM > 1.0e-4) { "pitch must be positive, was $pitchM" }
        require(policy.unitPitches > 0.0 && policy.topPieces >= 1 && policy.maxBendDegrees in 1.0..89.0) {
            "invalid belt chain policy $policy"
        }
        val wheels = side.wheels.associateBy { it.index }
        fun owner(anchor: CompiledEnvelopeAnchor) = wheels.getValue(anchor.ownerWheel)

        val idler = side.anchors.filter { owner(it).role == TrackWheelRole.IDLER_GUIDE }.sortedBy { it.bindPoint.y }
        val drive = side.anchors.filter { owner(it).role == TrackWheelRole.DRIVE_GUIDE }.sortedBy { it.bindPoint.y }
        val road = side.anchors.filter { owner(it).role == TrackWheelRole.SUPPORT }.sortedByDescending { it.bindPoint.z }
        require(idler.size == 2 && drive.size == 2 && road.size == 2) {
            "${side.side} anchors must be two per idler, drive and road end, got " +
                "${idler.size}/${drive.size}/${road.size}"
        }
        val idlerLow = idler[0]
        val idlerTop = idler[1]
        val driveLow = drive[0]
        val driveTop = drive[1]
        val bottomFront = road[0]
        val bottomRear = road[1]

        fun pt(anchor: CompiledEnvelopeAnchor) = anchor.bindPoint

        val bottomLength = hypot(
            pt(bottomRear).y - pt(bottomFront).y, pt(bottomRear).z - pt(bottomFront).z,
        )
        val units = max(1, (bottomLength / (policy.unitPitches * pitchM)).roundToInt())

        // Raw pieces: (kind, start, end, startWheel, endWheel)
        class Raw(val kind: BeltSegmentKind, val start: Vec3, val end: Vec3, val startWheel: Int, val endWheel: Int)
        val raw = ArrayList<Raw>()
        fun lerp(a: Vec3, b: Vec3, t: Double) =
            Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t)

        raw += Raw(BeltSegmentKind.FRONT_UP, pt(idlerTop), pt(idlerLow), idlerTop.ownerWheel, idlerLow.ownerWheel)
        raw += Raw(BeltSegmentKind.FRONT_LOW, pt(idlerLow), pt(bottomFront), idlerLow.ownerWheel, bottomFront.ownerWheel)
        for (unit in 0 until units) {
            raw += Raw(
                BeltSegmentKind.BOTTOM,
                lerp(pt(bottomFront), pt(bottomRear), unit.toDouble() / units),
                lerp(pt(bottomFront), pt(bottomRear), (unit + 1.0) / units),
                if (unit == 0) bottomFront.ownerWheel else -1,
                if (unit == units - 1) bottomRear.ownerWheel else -1,
            )
        }
        raw += Raw(BeltSegmentKind.REAR_LOW, pt(bottomRear), pt(driveLow), bottomRear.ownerWheel, driveLow.ownerWheel)
        raw += Raw(BeltSegmentKind.REAR_UP, pt(driveLow), pt(driveTop), driveLow.ownerWheel, driveTop.ownerWheel)
        for (piece in 0 until policy.topPieces) {
            raw += Raw(
                BeltSegmentKind.TOP,
                lerp(pt(driveTop), pt(idlerTop), piece.toDouble() / policy.topPieces),
                lerp(pt(driveTop), pt(idlerTop), (piece + 1.0) / policy.topPieces),
                if (piece == 0) driveTop.ownerWheel else -1,
                if (piece == policy.topPieces - 1) idlerTop.ownerWheel else -1,
            )
        }

        val total = raw.sumOf { hypot(it.end.y - it.start.y, it.end.z - it.start.z) }
        val thickness = side.belt.thicknessM
        fun direction(piece: Raw) = atan2(piece.end.y - piece.start.y, piece.end.z - piece.start.z)
        fun restBend(a: Raw, b: Raw): Double {
            var delta = direction(b) - direction(a)
            while (delta > PI) delta -= 2.0 * PI
            while (delta < -PI) delta += 2.0 * PI
            return delta
        }
        // At a bend of `angle` the outside of a square-ended box opens a wedge of
        // t * tan(angle / 2). Each end is extended by half of the wedge for the
        // largest angle its pin can reach, so the outer surface stays closed.
        // Neighbours never collide with each other, so the overlap is harmless.
        fun overlapAt(rest: Double): Double {
            val reach = abs(rest) + Math.toRadians(policy.maxBendDegrees)
            return thickness * 0.5 * tan(reach.coerceAtMost(Math.toRadians(150.0)) * 0.5)
        }
        val segments = raw.mapIndexed { index, piece ->
            val length = hypot(piece.end.y - piece.start.y, piece.end.z - piece.start.z)
            val before = raw[(index - 1 + raw.size) % raw.size]
            val after = raw[(index + 1) % raw.size]
            val overlap = max(overlapAt(restBend(before, piece)), overlapAt(restBend(piece, after)))
            BeltChainSegment(
                index = index,
                kind = piece.kind,
                start = piece.start,
                end = piece.end,
                startWheel = piece.startWheel,
                endWheel = piece.endWheel,
                massKg = massBudgetKg * length / total,
                halfExtents = Vec3(side.belt.widthM * 0.5, thickness * 0.5, length * 0.5 + overlap),
            )
        }
        return CompiledBeltChain(
            side = side.side,
            segments = segments,
            policy = policy,
            restLengthM = total,
            bottomUnits = units,
            bottomUnitLengthM = bottomLength / units,
        )
    }

    /** Signed bend at the pin between [a] and [b], radians; zero when they are collinear. */
    fun bendRad(a: BeltChainSegment, b: BeltChainSegment): Double {
        val angleA = atan2(a.end.y - a.start.y, a.end.z - a.start.z)
        val angleB = atan2(b.end.y - b.start.y, b.end.z - b.start.z)
        var delta = angleB - angleA
        while (delta > PI) delta -= 2.0 * PI
        while (delta < -PI) delta += 2.0 * PI
        return delta
    }

    /** Largest rest bend over all pins, radians. */
    fun worstRestBendRad(chain: CompiledBeltChain): Double =
        chain.segments.indices.maxOf { abs(chain.restBendRad(it)) }
}

/**
 * Turns a [CompiledBeltChain] into the physical runs the runtime assembles.
 *
 * - The chain is cut at every pin carried by a wheel on both sides; each piece
 *   between two such pins becomes one core run (a serial chain), so the bottom
 *   units are one run joined by hinges and every wheel-wrapped piece is its own.
 * - A carried pin becomes a joint from each neighbouring link to that wheel: all
 *   translation locked, bend about the lateral axis limited relative to rest.
 * - A road wheel that does not carry a pin holds the unit beneath it along the
 *   unit's normal only; the foot slides along the unit.
 * - A thickened bottom unit grows toward the belt interior. Its body origin moves
 *   inward and every pivot carries the offset, so pins stay on the centre line.
 *
 * Measured on the native prototype (TrackedBeltChainNativeTest) before use.
 */
object TrackedBeltChainPhysics {

    fun compile(
        side: CompiledTrackSide,
        chain: CompiledBeltChain,
        bottomCollisionThickness: Double?,
        inertiaScale: Double,
    ): List<CompiledPhysicalRun> {
        val segments = chain.segments
        val count = segments.size
        val limit = Math.toRadians(chain.policy.maxBendDegrees)
        val thickness = side.belt.thicknessM
        val wheels = side.wheels.associateBy { it.index }
        fun carried(index: Int) = segments[index].endWheel >= 0 && chain.next(index).startWheel >= 0
        val loopCenter = segments.fold(Vec3.ZERO) { sum, s -> sum.add(s.center) }.scale(1.0 / count)

        fun rotation(segment: BeltChainSegment): CompiledRotation = aboutX(segment.pitchRad)
        fun frame(segment: BeltChainSegment): CompiledRotation = aboutX(-segment.pitchRad)

        /** Body-frame offset of the link origin from the centre line, along local Y. */
        fun offsetY(segment: BeltChainSegment): Double {
            val thick = bottomCollisionThickness ?: return 0.0
            if (segment.kind != BeltSegmentKind.BOTTOM || thick <= thickness) return 0.0
            val localY = Vec3(0.0, kotlin.math.cos(segment.pitchRad), kotlin.math.sin(segment.pitchRad))
            val inward = loopCenter.subtract(segment.center)
            val sign = if (localY.y * inward.y + localY.z * inward.z >= 0.0) 1.0 else -1.0
            return sign * (thick - thickness) * 0.5
        }

        fun link(segment: BeltChainSegment): CompiledPhysicalLink {
            val offset = offsetY(segment)
            val localY = Vec3(0.0, kotlin.math.cos(segment.pitchRad), kotlin.math.sin(segment.pitchRad))
            val thick = if (offset != 0.0) bottomCollisionThickness!! else thickness
            return CompiledPhysicalLink(
                center = segment.center.add(localY.scale(offset)),
                rotation = rotation(segment),
                halfExtents = Vec3(segment.halfExtents.x, thick * 0.5, segment.halfExtents.z),
                massKg = segment.massKg,
                startPin = Vec3(0.0, -offset, -segment.lengthM * 0.5),
                endPin = Vec3(0.0, -offset, segment.lengthM * 0.5),
            )
        }

        fun startPivot(segment: BeltChainSegment) = Vec3(0.0, -offsetY(segment), -segment.lengthM * 0.5)
        fun endPivot(segment: BeltChainSegment) = Vec3(0.0, -offsetY(segment), segment.lengthM * 0.5)

        // Group start: the segment after a carried pin. Rotate so groups never wrap.
        val firstStart = (0 until count).firstOrNull { carried((it - 1 + count) % count) } ?: 0
        val order = (0 until count).map { (firstStart + it) % count }
        val groups = ArrayList<MutableList<Int>>()
        for (index in order) {
            val previous = (index - 1 + count) % count
            if (groups.isEmpty() || carried(previous)) groups.add(ArrayList())
            groups.last().add(index)
        }

        val runs = ArrayList<CompiledPhysicalRun>(groups.size)
        for ((runIndex, group) in groups.withIndex()) {
            val links = group.map { link(segments[it]) }
            val hinges = (0 until group.size - 1).map { local ->
                val a = segments[group[local]]
                val b = segments[group[local + 1]]
                CompiledPhysicalHinge(
                    previous = local,
                    next = local + 1,
                    pivotInPrevious = endPivot(a),
                    pivotInNext = startPivot(b),
                    frameInPrevious = frame(a),
                    frameInNext = frame(b),
                    halfAngleRad = limit,
                )
            }
            val externals = ArrayList<CompiledExternalJoint>()
            fun pin(localLink: Int, segment: BeltChainSegment, atEnd: Boolean) {
                val wheelIndex = if (atEnd) segment.endWheel else segment.startWheel
                val wheel = wheels.getValue(wheelIndex)
                val point = if (atEnd) segment.end else segment.start
                externals += CompiledExternalJoint(
                    localLink,
                    CompiledJointPlan(
                        name = "${side.side.name.lowercase()}_chain_${runIndex}_${localLink}_${if (atEnd) "end" else "start"}_pin",
                        bodyA = CompiledBodyRef(CompiledBodyKind.RUN, side.side, localLink),
                        bodyB = CompiledBodyRef(CompiledBodyKind.WHEEL, side.side, wheelIndex),
                        frameInA = CompiledConstraintFrame(if (atEnd) endPivot(segment) else startPivot(segment), frame(segment)),
                        frameInB = CompiledConstraintFrame(point.subtract(wheel.assemblyCenter)),
                        linear = List(3) { CompiledAxisLimit.Locked },
                        angular = listOf(
                            CompiledAxisLimit.Limited(-limit, limit),
                            CompiledAxisLimit.Locked,
                            CompiledAxisLimit.Locked,
                        ),
                    ),
                )
            }
            val first = segments[group.first()]
            val last = segments[group.last()]
            if (first.startWheel >= 0) pin(0, first, atEnd = false)
            if (last.endWheel >= 0) pin(group.size - 1, last, atEnd = true)

            // Road wheels over this run that do not carry one of its pins.
            for ((localLink, segmentIndex) in group.withIndex()) {
                val segment = segments[segmentIndex]
                if (segment.kind != BeltSegmentKind.BOTTOM) continue
                for (wheel in side.wheels.filter { it.isSuspended }) {
                    val z0 = minOf(segment.start.z, segment.end.z)
                    val z1 = maxOf(segment.start.z, segment.end.z)
                    if (wheel.assemblyCenter.z < z0 || wheel.assemblyCenter.z > z1) continue
                    if (wheel.index == segment.startWheel || wheel.index == segment.endWheel) continue
                    val s = (wheel.assemblyCenter.z - segment.start.z) / (segment.end.z - segment.start.z)
                    val line = Vec3(
                        wheel.assemblyCenter.x,
                        segment.start.y + (segment.end.y - segment.start.y) * s,
                        wheel.assemblyCenter.z,
                    )
                    val direction = Vec3(0.0, segment.end.y - segment.start.y, segment.end.z - segment.start.z).normalize()
                    val fromCenter = line.subtract(segment.center)
                    val along = fromCenter.y * direction.y + fromCenter.z * direction.z
                    val half = segment.lengthM * 0.5
                    externals += CompiledExternalJoint(
                        localLink,
                        CompiledJointPlan(
                            name = "${side.side.name.lowercase()}_chain_${runIndex}_${localLink}_wheel${wheel.index}_normal",
                            bodyA = CompiledBodyRef(CompiledBodyKind.RUN, side.side, localLink),
                            bodyB = CompiledBodyRef(CompiledBodyKind.WHEEL, side.side, wheel.index),
                            frameInA = CompiledConstraintFrame(Vec3(0.0, -offsetY(segment), along), frame(segment)),
                            frameInB = CompiledConstraintFrame(line.subtract(wheel.assemblyCenter)),
                            linear = listOf(
                                CompiledAxisLimit.Locked,
                                CompiledAxisLimit.Locked,
                                CompiledAxisLimit.Limited(-half, half),
                            ),
                            angular = listOf(CompiledAxisLimit.Free, CompiledAxisLimit.Locked, CompiledAxisLimit.Locked),
                        ),
                    )
                }
            }
            val label = group.joinToString("+") { segments[it].kind.name.lowercase() }.let {
                if (group.size > 2) "${segments[group.first()].kind.name.lowercase()}x${group.size}" else it
            }
            runs += CompiledPhysicalRun(runIndex, label, links, hinges, externals, inertiaScale)
        }
        return runs
    }

    private fun aboutX(angle: Double): CompiledRotation {
        val half = angle * 0.5
        return CompiledRotation(kotlin.math.sin(half).toFloat(), 0f, 0f, kotlin.math.cos(half).toFloat())
    }
}
