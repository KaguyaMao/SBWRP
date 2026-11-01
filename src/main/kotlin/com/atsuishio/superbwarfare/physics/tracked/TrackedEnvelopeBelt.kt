package com.atsuishio.superbwarfare.physics.tracked

import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One rigid free span of the envelope belt at its rest pose, chassis frame.
 *
 * Pins are on the belt centre line. [startWheel]/[endWheel] name the wheel a pin
 * is carried by (a wrapped guide wheel), or -1 when the pin joins two spans.
 */
data class EnvelopeSpan(
    val kind: BeltSegmentKind,
    val start: Vec3,
    val end: Vec3,
    val startWheel: Int,
    val endWheel: Int,
) {
    val lengthM: Double get() = hypot(end.y - start.y, end.z - start.z)

    /** Rotation about chassis X that turns +Z onto start -> end. */
    val pitchRad: Double get() = atan2(-(end.y - start.y), end.z - start.z)
}

/** One chord of a wrap arc, relative to its wheel centre. */
data class EnvelopeWrapPiece(
    val center: Vec3,
    val pitchRad: Double,
    val halfLengthM: Double,
)

/** Where a road wheel presses the belt at rest: its bottom tangent point, chassis frame. */
data class EnvelopeRoadFoot(val wheelIndex: Int, val foot: Vec3)

/** Compiled dynamic-segmentation policy of the envelope belt. Every value is data. */
data class CompiledEnvelopePolicy(
    val minPieceM: Double,
    val maxPieces: Int,
    val maxBendRad: Double,
    val splitRad: Double,
    val mergeRad: Double,
    val holdSeconds: Double,
    val contactBandM: Double,
    val collisionMarginM: Double,
    val inertiaScale: Double,
    val sampleSpacingM: Double,
    val tensionN: Double,
    val tensionDampingNsPerM: Double,
    val tensionTravelM: Double,
)

/**
 * The envelope belt of one side.
 *
 * The belt follows the wheels: tangent lines between consecutive wheels in
 * `BeltOrder`, arcs around the wrapped guide wheels. The rigid free spans are
 * the front incline, one bottom span under every road wheel, the rear incline
 * and the top return. The bottom starts as **one** body; the runtime splits it
 * where terrain or suspension bends it and merges it back when it straightens.
 * Wrap arcs are part of their wheel bodies, so the belt cannot stand off a guide
 * wheel. Anchors are only checked against the envelope, never used as geometry.
 */
data class CompiledEnvelopeBelt(
    val side: TrackedSide,
    val idlerWheel: Int,
    val driveWheel: Int,
    val front: EnvelopeSpan,
    val bottom: EnvelopeSpan,
    val rear: EnvelopeSpan,
    val top: EnvelopeSpan,
    /** Wrap chords per wrapped wheel index, relative to that wheel's centre. */
    val wraps: Map<Int, List<EnvelopeWrapPiece>>,
    val roadFeet: List<EnvelopeRoadFoot>,
    val restLengthM: Double,
    val freeLengthM: Double,
    val widthM: Double,
    val thicknessM: Double,
    /** Mass per metre of the free spans; wrap chords are massless parts of their wheels. */
    val massPerMetreKg: Double,
    val policy: CompiledEnvelopePolicy,
    /** Distance of each envelope anchor from the belt centre line, metres. */
    val anchorDeviationsM: List<Double>,
    /**
     * Wheel centres the envelope was fitted to, chassis frame: road wheels raised
     * by their static sag, guide wheels at their bind pose (the tensioner's
     * centre). The belt is built here, so a settled vehicle starts in tension
     * with the idler mid-travel.
     */
    val wheelCenters: Map<Int, Vec3>,
) {
    val spans: List<EnvelopeSpan> get() = listOf(front, bottom, rear, top)
}

object TrackedEnvelopeBeltCompiler {

    private class Circle(val wheel: CompiledWheel, val z: Double, val y: Double, val r: Double)

    /** Tangent of edge from circle i to circle j: points on each circle and the outward normal used. */
    private class Edge(val from: Circle, val to: Circle, val p1z: Double, val p1y: Double, val p2z: Double, val p2y: Double,
                       val mz: Double, val my: Double)

    fun compile(
        side: CompiledTrackSide,
        definition: TrackEnvelopeDefinition,
        pitchM: Double,
        massBudgetKg: Double,
    ): CompiledEnvelopeBelt {
        val half = side.belt.thicknessM * 0.5
        // Fit the belt to the loaded wheel set: a road wheel sits its static sag
        // up its travel direction. The bind pose is the unloaded model pose.
        val centers = side.wheels.associate { wheel ->
            val station = side.suspension.stations.firstOrNull { it.bone == wheel.bone }
            wheel.index to if (wheel.isSuspended && station != null) {
                wheel.assemblyCenter.add(station.direction.normalize().scale(station.staticSagM))
            } else {
                wheel.assemblyCenter
            }
        }
        val circles = side.beltWheels.map {
            val center = centers.getValue(it.index)
            Circle(it, center.z, center.y, it.radius + half)
        }
        val count = circles.size
        require(count >= 4) { "${side.side} envelope belt needs at least four belt wheels" }
        val support = circles.indices.filter { circles[it].wheel.isSuspended }
        require(support.size >= 2) { "${side.side} envelope belt needs at least two road wheels" }
        // The road wheels must be one consecutive run in belt order; the bottom span is that run.
        val firstSupport = support.first { !circles[(it - 1 + count) % count].wheel.isSuspended }
        val run = (0 until support.size).map { (firstSupport + it) % count }
        require(run.all { circles[it].wheel.isSuspended }) { "${side.side} road wheels are not consecutive in BeltOrder" }
        val guides = circles.indices.filter { !circles[it].wheel.isSuspended }
        require(guides.size == 2) { "${side.side} envelope belt needs exactly two wrapped guide wheels, has ${guides.size}" }
        val before = (run.first() - 1 + count) % count
        val after = (run.last() + 1) % count

        // Loop orientation in the (z, y) plane decides which side is outside.
        var area = 0.0
        for (index in 0 until count) {
            val a = circles[index]
            val b = circles[(index + 1) % count]
            area += a.z * b.y - b.z * a.y
        }
        val ccw = area > 0.0
        val edges = (0 until count).map { index ->
            val a = circles[index]
            val b = circles[(index + 1) % count]
            val dz = b.z - a.z
            val dy = b.y - a.y
            val d = hypot(dz, dy)
            require(d > abs(a.r - b.r) + 1.0e-6) { "${side.side} wheels ${a.wheel.bone} and ${b.wheel.bone} overlap" }
            val uz = dz / d
            val uy = dy / d
            // Outside of a CCW loop is to the right of travel; of a CW loop to the left.
            val nz = if (ccw) uy else -uy
            val ny = if (ccw) -uz else uz
            val k = (a.r - b.r) / d
            val l = sqrt((1.0 - k * k).coerceAtLeast(0.0))
            val mz = k * uz + l * nz
            val my = k * uy + l * ny
            Edge(a, b, a.z + a.r * mz, a.y + a.r * my, b.z + b.r * mz, b.y + b.r * my, mz, my)
        }
        fun edgeInto(index: Int) = edges[(index - 1 + count) % count]
        fun edgeOutOf(index: Int) = edges[index]
        val x = side.belt.centerPlaneX

        /** Where two consecutive tangent lines meet at a road-wheel end, or their shared point when collinear. */
        fun kink(incoming: Edge, outgoing: Edge): Vec3 {
            val d1z = incoming.p2z - incoming.p1z
            val d1y = incoming.p2y - incoming.p1y
            val d2z = outgoing.p2z - outgoing.p1z
            val d2y = outgoing.p2y - outgoing.p1y
            val cross = d1z * d2y - d1y * d2z
            if (abs(cross) < 1.0e-9) return Vec3(x, incoming.p2y, incoming.p2z)
            val t = ((outgoing.p1z - incoming.p1z) * d2y - (outgoing.p1y - incoming.p1y) * d2z) / cross
            return Vec3(x, incoming.p1y + d1y * t, incoming.p1z + d1z * t)
        }

        val frontEdge = edgeInto(run.first())
        val rearEdge = edgeOutOf(run.last())
        val bottomStart = kink(frontEdge, edgeOutOf(run.first()))
        val bottomEnd = kink(edgeInto(run.last()), rearEdge)
        val front = EnvelopeSpan(
            BeltSegmentKind.FRONT_LOW,
            Vec3(x, frontEdge.p1y, frontEdge.p1z), bottomStart,
            circles[before].wheel.index, -1,
        )
        val bottom = EnvelopeSpan(BeltSegmentKind.BOTTOM, bottomStart, bottomEnd, -1, -1)
        val rear = EnvelopeSpan(
            BeltSegmentKind.REAR_LOW,
            bottomEnd, Vec3(x, rearEdge.p2y, rearEdge.p2z),
            -1, circles[after].wheel.index,
        )
        // The top return joins the two guide wheels, from the one after the
        // bottom run to the one before it.
        val topEdge = edgeOutOf(after)
        require(topEdge.to === circles[before]) { "${side.side} top return must join the two guide wheels directly" }
        val top = EnvelopeSpan(
            BeltSegmentKind.TOP,
            Vec3(x, topEdge.p1y, topEdge.p1z), Vec3(x, topEdge.p2y, topEdge.p2z),
            circles[after].wheel.index, circles[before].wheel.index,
        )

        // Wrap arcs on the guide wheels, cut into chords about one pitch long.
        val wraps = LinkedHashMap<Int, List<EnvelopeWrapPiece>>()
        var arcLength = 0.0
        for (guide in guides) {
            val circle = circles[guide]
            val entry = atan2(edgeInto(guide).my, edgeInto(guide).mz)
            val exit = atan2(edgeOutOf(guide).my, edgeOutOf(guide).mz)
            var sweep = exit - entry
            if (ccw) {
                while (sweep < 0.0) sweep += 2.0 * PI
            } else {
                while (sweep > 0.0) sweep -= 2.0 * PI
            }
            arcLength += abs(sweep) * circle.r
            // Half-pitch chords keep the polygon within a few millimetres of the arc.
            val pieces = max(1, ceil(abs(sweep) * circle.r / (pitchM * 0.5)).toInt())
            val step = sweep / pieces
            wraps[circle.wheel.index] = (0 until pieces).map { index ->
                val a0 = entry + step * index
                val a1 = a0 + step
                val z0 = circle.r * cos(a0)
                val y0 = circle.r * sin(a0)
                val z1 = circle.r * cos(a1)
                val y1 = circle.r * sin(a1)
                val chord = hypot(z1 - z0, y1 - y0)
                EnvelopeWrapPiece(
                    center = Vec3(0.0, (y0 + y1) * 0.5, (z0 + z1) * 0.5),
                    pitchRad = atan2(-(y1 - y0), z1 - z0),
                    halfLengthM = chord * 0.5,
                )
            }
        }

        val freeLength = front.lengthM + bottom.lengthM + rear.lengthM + top.lengthM
        val feet = run.map { index ->
            val wheel = circles[index].wheel
            // Foot under the wheel on the bottom line, projected along the bottom normal.
            val dz = bottomEnd.z - bottomStart.z
            val dy = bottomEnd.y - bottomStart.y
            val length = hypot(dz, dy)
            val center = centers.getValue(wheel.index)
            val s = ((center.z - bottomStart.z) * dz + (center.y - bottomStart.y) * dy) / (length * length)
            EnvelopeRoadFoot(wheel.index, Vec3(x, bottomStart.y + dy * s, bottomStart.z + dz * s))
        }
        val deviations = side.anchors.map { anchor -> distanceToBelt(anchor.bindPoint, listOf(front, bottom, rear, top), circles, guides) }
        return CompiledEnvelopeBelt(
            side = side.side,
            idlerWheel = circles[before].wheel.index,
            driveWheel = circles[after].wheel.index,
            front = front,
            bottom = bottom,
            rear = rear,
            top = top,
            wraps = wraps,
            roadFeet = feet,
            restLengthM = freeLength + arcLength,
            freeLengthM = freeLength,
            widthM = side.belt.widthM,
            thicknessM = side.belt.thicknessM,
            massPerMetreKg = massBudgetKg / freeLength,
            policy = CompiledEnvelopePolicy(
                minPieceM = definition.minPiecePitches * pitchM,
                maxPieces = definition.maxPieces.coerceIn(1, 32),
                maxBendRad = Math.toRadians(definition.maxBendDegrees),
                splitRad = Math.toRadians(definition.splitDegrees),
                mergeRad = Math.toRadians(definition.mergeDegrees),
                holdSeconds = definition.holdSeconds,
                contactBandM = definition.contactBand,
                collisionMarginM = definition.collisionMargin.coerceIn(0.0, side.belt.thicknessM * 0.45),
                inertiaScale = definition.unitInertiaScale,
                sampleSpacingM = pitchM,
                tensionN = definition.tension.coerceAtLeast(0.0),
                tensionDampingNsPerM = definition.tensionDamping.coerceAtLeast(0.0),
                tensionTravelM = definition.tensionTravel.coerceAtLeast(0.0),
            ),
            anchorDeviationsM = deviations,
            wheelCenters = centers,
        )
    }

    private fun distanceToBelt(point: Vec3, spans: List<EnvelopeSpan>, circles: List<Circle>, guides: List<Int>): Double {
        var best = Double.POSITIVE_INFINITY
        for (span in spans) {
            val dz = span.end.z - span.start.z
            val dy = span.end.y - span.start.y
            val lengthSq = dz * dz + dy * dy
            val t = if (lengthSq <= 0.0) 0.0 else
                (((point.z - span.start.z) * dz + (point.y - span.start.y) * dy) / lengthSq).coerceIn(0.0, 1.0)
            best = minOf(best, hypot(point.z - (span.start.z + dz * t), point.y - (span.start.y + dy * t)))
        }
        for (guide in guides) {
            val circle = circles[guide]
            best = minOf(best, abs(hypot(point.z - circle.z, point.y - circle.y) - circle.r))
        }
        return best
    }
}
