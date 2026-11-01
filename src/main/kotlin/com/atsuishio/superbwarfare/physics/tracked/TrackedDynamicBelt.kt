package com.atsuishio.superbwarfare.physics.tracked

import cn.solarmoon.spark_core.physics.body.PhysicsJoints
import cn.solarmoon.spark_core.physics.body.name
import com.jme3.bullet.PhysicsSpace
import com.jme3.bullet.collision.shapes.BoxCollisionShape
import com.jme3.bullet.collision.shapes.CompoundCollisionShape
import com.jme3.bullet.collision.shapes.CylinderCollisionShape
import com.jme3.bullet.joints.New6Dof
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.math.Quaternion
import com.jme3.math.Vector3f
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the dynamic belt creates and removes its native objects.
 *
 * Production routes these through the vehicle's physics level and rollback
 * ledger; the native test routes them to a plain `PhysicsSpace`. Every call is
 * made on the physics thread, between Bullet updates.
 */
interface BeltWorld {
    /**
     * A belt body was just created. Record ownership first, then set owner,
     * collision filtering, friction and gravity. Called before [addBody].
     */
    fun configure(body: PhysicsRigidBody)

    fun addBody(body: PhysicsRigidBody)

    /** Remove from the world and forget it; the body is not used again. */
    fun removeBody(body: PhysicsRigidBody)

    /** A joint was just created, before its limits are set. Record ownership. */
    fun recordJoint(joint: New6Dof)

    fun addJoint(joint: New6Dof)

    /** Remove from the world, destroy and forget it. */
    fun removeJoint(joint: New6Dof)

    /** Fraction along `from -> to` of the first hit on this vehicle's terrain, or null. */
    fun terrainRay(from: Vector3f, to: Vector3f): Float?
}

/**
 * The wheel-fitted envelope belt of one side as live Bullet bodies.
 *
 * - Wrap arcs are compound children of the guide-wheel bodies (see the runtime's
 *   wheel creation), so they cannot stand off their wheel.
 * - Front incline, bottom, rear incline and top return are rigid spans. Each is a
 *   box of the real belt thickness with a lateral cylinder of radius `t/2` at
 *   each pin; neighbours share the pin cylinder, so a bent pin shows neither a
 *   wedge gap outside nor a plate overlap inside.
 * - Adjacent spans are pinned directly to each other. The front span is pinned
 *   to the idler and the rear span to the drive wheel; the top return is pinned
 *   to the drive wheel and slides along its own axis at the idler, so the idler
 *   tensioner, not the top, decides the loop length.
 * - Road wheels hold the bottom piece beneath them along its normal only; the
 *   foot slides along the piece.
 * - The bottom starts as **one** body. [updateSegmentation] splits it where the
 *   ground under it kinks and merges pieces back once a pin has stayed straight
 *   and unneeded for the hold time. Every pose, pin and wheel foot is derived
 *   from rest arc lengths along the bottom line, so the result does not drift.
 *
 * Physics thread only. Not thread-safe.
 */
class TrackedDynamicBelt(
    private val side: CompiledTrackSide,
    val belt: CompiledEnvelopeBelt,
    private val world: BeltWorld,
    private val hull: PhysicsRigidBody,
    private val wheels: Map<Int, PhysicsRigidBody>,
    private val name: String,
) {
    enum class Kind { FRONT, BOTTOM, REAR, TOP }

    /** One live span. [s0]/[s1] are rest arc lengths along its compiled span. */
    class Piece internal constructor(
        val kind: Kind,
        val body: PhysicsRigidBody,
        val s0: Double,
        val s1: Double,
        internal val restCenter: Vec3,
        internal val restPitch: Double,
    ) {
        val lengthM: Double get() = s1 - s0
    }

    private class JointRecord(val joint: New6Dof, val plan: CompiledJointPlan, val a: PhysicsRigidBody, val b: PhysicsRigidBody)

    private class Cut(var straightSeconds: Double = 0.0)

    enum class Change { NONE, MOVE, SPLIT, MERGE }

    private val policy = belt.policy
    private val half = belt.thicknessM * 0.5
    private val bottomDirection: Vec3 = belt.bottom.end.subtract(belt.bottom.start).normalize()

    private var front: Piece? = null
    private var rear: Piece? = null
    private var top: Piece? = null
    private val bottom = ArrayList<Piece>()
    private val cuts = ArrayList<Cut>()
    private val fixedJoints = ArrayList<JointRecord>()
    private val bottomJoints = ArrayList<JointRecord>()

    /** Splits and merges since creation, for diagnostics and tests. */
    var splitCount: Int = 0
        private set
    var mergeCount: Int = 0
        private set
    var moveCount: Int = 0
        private set

    val isBuilt: Boolean get() = front != null

    val bottomPieceCount: Int get() = bottom.size

    /** Every live belt body in loop order: front, bottom pieces, rear, top. */
    val pieces: List<Piece>
        get() = buildList {
            front?.let(::add)
            addAll(bottom)
            rear?.let(::add)
            top?.let(::add)
        }

    val bodyCount: Int get() = pieces.size
    val jointCount: Int get() = fixedJoints.size + bottomJoints.size

    /** Counts for other threads, refreshed after every build, change and destroy. */
    @Volatile var publishedBodyCount: Int = 0
        private set
    @Volatile var publishedJointCount: Int = 0
        private set
    @Volatile var publishedBottomPieces: Int = 0
        private set
    @Volatile var publishedTopology: String = ""
        private set
    @Volatile var publishedRepartitionCounts: String = "splits=0 moves=0 merges=0"
        private set
    /** One line describing the last segmentation pass, for diagnostics. */
    @Volatile var publishedSegmentation: String = ""
        private set

    /** Worst pin gap measured at the last segmentation pass, metres. */
    @Volatile var publishedWorstGapM: Double = 0.0
        private set

    private fun publishCounts() {
        publishedBodyCount = bodyCount
        publishedJointCount = jointCount
        publishedBottomPieces = bottom.size
        publishedTopology = bottom.joinToString("|") { piece ->
            "${"%.6f".format(piece.s0)}..${"%.6f".format(piece.s1)}"
        }
        publishedRepartitionCounts = "splits=$splitCount moves=$moveCount merges=$mergeCount"
    }

    /** Rest arc lengths of the pins between bottom pieces. */
    val cutPositions: List<Double> get() = (0 until bottom.size - 1).map { bottom[it].s1 }

    fun cutDiagnostics(): List<String> = cuts.indices.map { index ->
        val a = bottom[index]
        val b = bottom[index + 1]
        val endpoints = repartitionPoints(listOf(a, b))
        val material = b.s1 - a.s0
        val chord = endpoints.first().distance(endpoints.last()).toDouble()
        "s=${"%.3f".format(a.s1)} bendDeg=${"%.3f".format(Math.toDegrees(pinBend(a, b)))} " +
            "straight=${"%.2f".format(cuts[index].straightSeconds)} lengthErrorMm=${"%.2f".format((material - chord) * 1000.0)}"
    }

    /**
     * Create every span at the rest pose of a hull at [origin]/[rotation] moving
     * with [linear]/[angular], plus all joints. The bottom is one piece.
     */
    fun build(origin: Vector3f, rotation: Quaternion, linear: Vector3f, angular: Vector3f) {
        check(!isBuilt) { "$name is already built" }
        fun place(piece: Piece) {
            val offset = rotation.rotate(piece.restCenter.toBullet())
            piece.body.setPhysicsLocation(origin.add(offset))
            piece.body.setPhysicsRotation(Quaternion(rotation).mult(aboutX(piece.restPitch)))
            piece.body.setLinearVelocity(linear.add(angular.cross(offset, Vector3f())))
            piece.body.setAngularVelocity(angular)
        }
        val f = createPiece(Kind.FRONT, belt.front, 0.0, belt.front.lengthM).also(::place)
        val b = createPiece(Kind.BOTTOM, belt.bottom, 0.0, belt.bottom.lengthM).also(::place)
        val r = createPiece(Kind.REAR, belt.rear, 0.0, belt.rear.lengthM).also(::place)
        val t = createPiece(Kind.TOP, belt.top, 0.0, belt.top.lengthM).also(::place)
        front = f
        rear = r
        top = t
        bottom += b
        for (piece in pieces) world.addBody(piece.body)

        val idler = belt.idlerWheel
        val drive = belt.driveWheel
        fixedJoints += wheelPin("idler_front", f, idler, belt.front.start)
        fixedJoints += driveSlide(r, drive)
        fixedJoints += wheelPin("drive_top", t, drive, belt.top.start)
        fixedJoints += topSlide(t, idler)
        rebuildBottomJoints()
        publishCounts()
    }

    /** Remove every joint, then every body. Safe to call twice. */
    fun destroy() {
        for (record in bottomJoints.asReversed()) world.removeJoint(record.joint)
        bottomJoints.clear()
        for (record in fixedJoints.asReversed()) world.removeJoint(record.joint)
        fixedJoints.clear()
        for (piece in pieces.asReversed()) world.removeBody(piece.body)
        front = null
        rear = null
        top = null
        bottom.clear()
        cuts.clear()
        publishCounts()
    }

    /** Throw the live belt away and build it again at the rest pose. */
    fun rebuild(origin: Vector3f, rotation: Quaternion, linear: Vector3f, angular: Vector3f) {
        destroy()
        build(origin, rotation, linear, angular)
    }

    // ---------------------------------------------------------------- segmentation

    /** One ground sample under the bottom, chassis frame. */
    private class Sample(val s: Double, val y: Double, val z: Double, val gap: Double)

    /**
     * Decide and apply at most one topology change. Call once per game tick,
     * between Bullet updates, with the time since the previous call.
     */
    fun updateSegmentation(dtSeconds: Double): Change {
        if (!isBuilt) return Change.NONE
        publishedWorstGapM = worstPinGapM()
        val samples = sampleGround()
        val count = samples.size
        val spacing = policy.sampleSpacingM
        // How far a hollow may be bridged and still count as under the belt.
        val bridge = max(2, ceil(BRIDGE_M / spacing).toInt())
        val kink = DoubleArray(count)
        val relevant = BooleanArray(count)
        for (k in 0 until count) {
            val here = samples[k] ?: continue
            val back = samples.getOrNull(k - 2)
            val ahead = samples.getOrNull(k + 2)
            if (back == null || ahead == null) continue
            val a = atan2(here.y - back.y, here.z - back.z)
            val b = atan2(ahead.y - here.y, ahead.z - here.z)
            kink[k] = wrap(b - a)
            // A crest matters when the belt is on it. A hollow matters when the
            // belt rests on the ground on both sides of it: a rigid piece bridges
            // a hollow, so the gap right at the kink says nothing.
            val bulge = here.y - (back.y + ahead.y) * 0.5
            relevant[k] = if (bulge >= 0.0) {
                here.gap <= policy.contactBandM
            } else {
                var left = Double.POSITIVE_INFINITY
                var right = Double.POSITIVE_INFINITY
                for (j in max(0, k - bridge) until k) samples[j]?.let { left = min(left, it.gap) }
                for (j in k + 1..min(count - 1, k + bridge)) samples[j]?.let { right = min(right, it.gap) }
                left <= policy.contactBandM && right <= policy.contactBandM
            }
        }

        publishedSegmentation = "samples=${samples.count { it != null }}/$count maxKinkDeg=${"%.1f".format(Math.toDegrees(kink.maxOf { abs(it) }))} " +
            "relevantKinks=${(0 until count).count { relevant[it] && abs(kink[it]) > policy.splitRad }} " +
            "cuts=${cutPositions.map { "%.2f".format(it) }} " +
            "gaps(mm)=${samples.filterNotNull().let { s -> if (s.isEmpty()) "none" else "%.0f..%.0f".format(s.minOf { it.gap } * 1000, s.maxOf { it.gap } * 1000) }}"
        // A ground kink that matters: relevant, a local maximum, above [threshold].
        fun kinks(threshold: Double): List<Int> = (0 until count).filter { k ->
            samples[k] != null && relevant[k] && abs(kink[k]) > threshold &&
                abs(kink[k]) >= abs(kink.getOrElse(k - 1) { 0.0 }) &&
                abs(kink[k]) >= abs(kink.getOrElse(k + 1) { 0.0 })
        }
        var change = Change.NONE

        // The belt does not circulate, so terrain kinks travel along the bottom
        // as the hull moves. A cut follows its kink instead of merging and
        // splitting again behind it.
        // Only a kink strong enough to have caused a split remains an active
        // cut target. Sub-threshold flat-ground ray noise must not keep moving a
        // cut or continually reset its straight-hold timer.
        val held = kinks(policy.splitRad)
        val moveRange = max(1.0, spacing * 4.0)
        var index = 0
        while (index < cuts.size) {
            val position = bottom[index].s1
            val lower = bottom[index].s0 + policy.minPieceM
            val upper = bottom[index + 1].s1 - policy.minPieceM
            val target = held
                .map { samples[it]!!.s }
                .filter { s -> abs(s - position) <= moveRange && s in lower..upper }
                // The kink must be nearer this cut than either neighbour.
                .filter { s -> (index == 0 || abs(s - position) < abs(s - bottom[index].s0)) &&
                    (index == cuts.size - 1 || abs(s - position) < abs(s - bottom[index + 1].s1)) }
                .minByOrNull { abs(it - position) }
            val cut = cuts[index]
            when {
                target != null && abs(target - position) <= spacing * 0.5 -> {
                    val bend = abs(pinBend(bottom[index], bottom[index + 1]))
                    val geometricallyStraight = canRepartition(
                        index,
                        index + 1,
                        listOf(bottom[index].s0, bottom[index + 1].s1),
                    )
                    cut.straightSeconds = if (bend < policy.mergeRad && geometricallyStraight) {
                        cut.straightSeconds + dtSeconds
                    } else {
                        0.0
                    }
                }
                target != null -> {
                    val moved = repartition(index, index + 1, listOf(bottom[index].s0, target, bottom[index + 1].s1))
                    if (moved) {
                        moveCount++
                        publishCounts()
                        if (change == Change.NONE) change = Change.MOVE
                    }
                    cut.straightSeconds = 0.0
                }
                else -> {
                    val bend = abs(pinBend(bottom[index], bottom[index + 1]))
                    val geometricallyStraight = canMergeGeometry(index)
                    cut.straightSeconds = if (bend < policy.mergeRad && geometricallyStraight) {
                        cut.straightSeconds + dtSeconds
                    } else {
                        0.0
                    }
                }
            }
            index++
        }

        // Split at the strongest relevant kink no cut covers yet.
        if (bottom.size < policy.maxPieces) {
            for (k in kinks(policy.splitRad).sortedByDescending { abs(kink[it]) }) {
                val s = samples[k]!!.s
                val at = bottom.indexOfFirst { s > it.s0 && s < it.s1 }
                if (at < 0) continue
                val piece = bottom[at]
                if (s - piece.s0 < policy.minPieceM || piece.s1 - s < policy.minPieceM) continue
                if (repartition(at, at, listOf(piece.s0, s, piece.s1))) {
                    splitCount++
                    publishCounts()
                    return Change.SPLIT
                }
            }
        }

        // A merge is legal only while the pin is straight and the fixed material
        // length still fits between the outer endpoints. Time without a terrain
        // kink cannot make a bent, inextensible span representable.
        val ready = cuts.indices
            .filter { index ->
                val a = bottom[index]
                val b = bottom[index + 1]
                TrackedBeltGeometry.canMerge(
                    straightSeconds = cuts[index].straightSeconds,
                    holdSeconds = policy.holdSeconds,
                    bendRad = pinBend(a, b),
                    mergeRad = policy.mergeRad,
                    materialLengths = listOf(b.s1 - a.s0),
                    points = repartitionPoints(listOf(a, b)),
                    endpointSlackM = measuredOuterPinResidual(listOf(a, b)),
                )
                    && held.none { kinkIndex -> samples[kinkIndex]!!.s in a.s0..b.s1 }
            }
            .maxByOrNull { cuts[it].straightSeconds }
        if (ready != null) {
            val merged = listOf(bottom[ready], bottom[ready + 1])
            if (repartition(
                    ready,
                    ready + 1,
                    listOf(bottom[ready].s0, bottom[ready + 1].s1),
                )
            ) {
                mergeCount++
                publishCounts()
                return Change.MERGE
            }
        }
        return change
    }

    private fun sampleGround(): List<Sample?> {
        val length = belt.bottom.lengthM
        val spacing = policy.sampleSpacingM
        val steps = max(2, ceil(length / spacing).toInt())
        val hullLocation = hull.getPhysicsLocation(null)
        val hullInverse = hull.getPhysicsRotation(null).inverse()
        val lift = 0.5f
        val reach = (half + max(policy.contactBandM * 3.0, 0.6)).toFloat()
        val outward = outwardSign()
        return (0..steps).map { step ->
            val s = min(step * spacing, length)
            val piece = bottom.firstOrNull { s >= it.s0 - 1.0e-9 && s <= it.s1 + 1.0e-9 } ?: return@map null
            val rotation = piece.body.getPhysicsRotation(null)
            val local = (s - (piece.s0 + piece.s1) * 0.5).toFloat()
            val point = piece.body.getPhysicsLocation(null).add(rotation.rotate(Vector3f(0f, 0f, local)))
            val normal = rotation.rotate(Vector3f(0f, outward, 0f))
            val from = point.subtract(normal.mult(lift))
            val to = point.add(normal.mult(reach))
            val fraction = world.terrainRay(from, to) ?: return@map null
            val hit = from.add(to.subtract(from).multLocal(fraction))
            val chassis = hullInverse.rotate(hit.subtract(hullLocation))
            Sample(s, chassis.y.toDouble(), chassis.z.toDouble(), fraction * (lift + reach) - lift - half)
        }
    }

    /** +1 or -1: the bottom piece's local Y that points away from the road wheels. */
    private fun outwardSign(): Float {
        val wheel = side.wheels.first { it.index == belt.roadFeet.first().wheelIndex }
        val local = toPieceLocal(bottomRestCenter(0.0, belt.bottom.lengthM), belt.bottom.pitchRad, belt.wheelCenters.getValue(wheel.index))
        return if (local.y > 0.0) -1f else 1f
    }

    /** Signed bend of the pin between [a] and [b] about [a]'s lateral axis, radians. */
    private fun pinBend(a: Piece, b: Piece): Double {
        val qa = a.body.getPhysicsRotation(null)
        val zb = b.body.getPhysicsRotation(null).rotate(Vector3f(0f, 0f, 1f))
        val local = qa.inverse().rotate(zb)
        return atan2(-local.y.toDouble(), local.z.toDouble())
    }

    /**
     * Replace bottom pieces [first]..[last] with new pieces bounded by the rest
     * arc lengths [bounds] (first and last bound unchanged). Each new piece is
     * the straight chord between the current belt points at its bounds; it takes
     * the velocity of the old material at its centre and the length-weighted
     * spin of the old pieces it covers. Joints are rebuilt from rest geometry.
     */
    private fun repartition(
        first: Int,
        last: Int,
        bounds: List<Double>,
    ): Boolean {
        val olds = bottom.subList(first, last + 1).toList()
        fun holder(s: Double) = olds.firstOrNull { s >= it.s0 - 1.0e-9 && s <= it.s1 + 1.0e-9 } ?: olds.last()
        fun pointOn(s: Double): Vector3f {
            val piece = holder(s)
            return worldOf(piece.body, Vec3(0.0, 0.0, s - (piece.s0 + piece.s1) * 0.5))
        }
        val desiredPoints = bounds.map(::pointOn)
        val materialLengths = bounds.zipWithNext { a, b -> b - a }
        val points = repartitionPoints(olds, desiredPoints)
        val endpointSlack = if (materialLengths.size == 1) measuredOuterPinResidual(olds) else 0.0
        val fittedPoints = TrackedBeltGeometry.fitPolyline(
            materialLengths,
            points,
            policy.maxBendRad,
            endpointSlack,
        ) ?: return false

        val news = (0 until bounds.size - 1).map { k ->
            val s0 = bounds[k]
            val s1 = bounds[k + 1]
            val piece = createPiece(Kind.BOTTOM, belt.bottom, s0, s1)
            val from = fittedPoints[k]
            val to = fittedPoints[k + 1]
            val mid = (s0 + s1) * 0.5
            val reference = holder(mid).body
            val qRef = reference.getPhysicsRotation(null)
            val direction = qRef.inverse().rotate(to.subtract(from))
            val turn = atan2(-direction.y.toDouble(), direction.z.toDouble())
            val center = from.add(to).multLocal(0.5f)
            val linear = reference.getLinearVelocity(null)
                .add(reference.getAngularVelocity(null).cross(center.subtract(reference.getPhysicsLocation(null)), Vector3f()))
            val angular = Vector3f()
            for (old in olds) {
                val overlap = min(s1, old.s1) - max(s0, old.s0)
                if (overlap > 0.0) angular.addLocal(old.body.getAngularVelocity(null).mult((overlap / (s1 - s0)).toFloat()))
            }
            piece.body.setPhysicsLocation(center)
            piece.body.setPhysicsRotation(Quaternion(qRef).mult(aboutX(turn)))
            piece.body.setLinearVelocity(linear)
            piece.body.setAngularVelocity(angular)
            piece
        }
        clearBottomJoints()
        for (old in olds) world.removeBody(old.body)
        for (piece in news) world.addBody(piece.body)
        repeat(olds.size) { bottom.removeAt(first) }
        bottom.addAll(first, news)
        repeat(olds.size - 1) { cuts.removeAt(first) }
        repeat(news.size - 1) { cuts.add(first, Cut()) }
        rebuildBottomJoints()
        publishCounts()
        return true
    }

    private fun canRepartition(first: Int, last: Int, bounds: List<Double>): Boolean {
        val olds = bottom.subList(first, last + 1)
        fun holder(s: Double) = olds.firstOrNull { s >= it.s0 - 1.0e-9 && s <= it.s1 + 1.0e-9 } ?: olds.last()
        val desiredPoints = bounds.map { s ->
            val piece = holder(s)
            worldOf(piece.body, Vec3(0.0, 0.0, s - (piece.s0 + piece.s1) * 0.5))
        }
        val materialLengths = bounds.zipWithNext { a, b -> b - a }
        return TrackedBeltGeometry.fitPolyline(
            materialLengths,
            repartitionPoints(olds, desiredPoints),
            policy.maxBendRad,
            if (materialLengths.size == 1) measuredOuterPinResidual(olds) else 0.0,
        ) != null
    }

    private fun canMergeGeometry(index: Int): Boolean {
        val a = bottom[index]
        val b = bottom[index + 1]
        return TrackedBeltGeometry.fitPolyline(
            materialLengths = listOf(b.s1 - a.s0),
            desiredPoints = repartitionPoints(listOf(a, b)),
            maxBendRad = policy.maxBendRad,
            endpointSlackM = measuredOuterPinResidual(listOf(a, b)),
        ) != null
    }

    private fun measuredOuterPinResidual(pieces: List<Piece>): Double {
        val selected = pieces.map { it.body }.toSet()
        return bottomJoints.sumOf { record ->
            val aInside = record.a in selected
            val bInside = record.b in selected
            if (aInside == bInside) return@sumOf 0.0
            val outside = if (aInside) record.b else record.a
            if (outside in wheels.values) return@sumOf 0.0
            worldOf(record.a, record.plan.frameInA.origin)
                .distance(worldOf(record.b, record.plan.frameInB.origin)).toDouble()
        }
    }

    private fun repartitionPoints(pieces: List<Piece>, desiredPoints: List<Vector3f> = emptyList()): List<Vector3f> {
        require(pieces.isNotEmpty())
        val startFallback = desiredPoints.firstOrNull()
            ?: worldOf(pieces.first().body, Vec3(0.0, 0.0, -pieces.first().lengthM * 0.5))
        val endFallback = desiredPoints.lastOrNull()
            ?: worldOf(pieces.last().body, Vec3(0.0, 0.0, pieces.last().lengthM * 0.5))
        val start = externalPinAnchor(pieces.first(), atStart = true) ?: startFallback
        val end = externalPinAnchor(pieces.last(), atStart = false) ?: endFallback
        if (desiredPoints.isEmpty()) return listOf(start, end)
        return desiredPoints.toMutableList().also {
            it[0] = start
            it[it.lastIndex] = end
        }
    }

    private fun externalPinAnchor(piece: Piece, atStart: Boolean): Vector3f? {
        val record = bottomJoints.firstOrNull { candidate ->
            val hasA = candidate.a === piece.body
            val hasB = candidate.b === piece.body
            if (hasA == hasB) return@firstOrNull false
            val other = if (hasA) candidate.b else candidate.a
            if (other in wheels.values) return@firstOrNull false
            val local = if (hasA) candidate.plan.frameInA.origin else candidate.plan.frameInB.origin
            if (atStart) local.z < 0.0 else local.z > 0.0
        } ?: return null
        return if (record.a === piece.body) {
            worldOf(record.b, record.plan.frameInB.origin)
        } else {
            worldOf(record.a, record.plan.frameInA.origin)
        }
    }

    // ---------------------------------------------------------------- joints

    private fun clearBottomJoints() {
        for (record in bottomJoints.asReversed()) world.removeJoint(record.joint)
        bottomJoints.clear()
    }

    private fun rebuildBottomJoints() {
        val f = front ?: return
        val r = rear ?: return
        bottomJoints += spanPin("front_bottom", f, bottom.first(), belt.bottom.start)
        for (index in 0 until bottom.size - 1) {
            val at = bottomPoint(bottom[index].s1)
            bottomJoints += spanPin("bottom_$index", bottom[index], bottom[index + 1], at)
        }
        bottomJoints += spanPin("bottom_rear", bottom.last(), r, belt.bottom.end)
        // A wheel is held by the piece its centre is over now, not at rest: the
        // bottom slides along itself with the tensioner, and a cut that moved
        // past a foot hands the wheel to its neighbour here.
        for (foot in belt.roadFeet) {
            val centre = wheels.getValue(foot.wheelIndex).getPhysicsLocation(null)
            val piece = bottom.maxBy { piece ->
                val z = currentLocalZ(piece, centre)
                min(z + piece.lengthM * 0.5, piece.lengthM * 0.5 - z)
            }
            bottomJoints += wheelLock(piece, foot.wheelIndex)
        }
    }

    private fun spanPin(label: String, a: Piece, b: Piece, at: Vec3): JointRecord = joint(
        label, a.body, b.body,
        CompiledConstraintFrame(toPieceLocal(a, at), aboutXCompiled(-a.restPitch)),
        CompiledConstraintFrame(toPieceLocal(b, at), aboutXCompiled(-b.restPitch)),
        linear = List(3) { CompiledAxisLimit.Locked },
        angular = bend(),
    )

    private fun wheelPin(label: String, piece: Piece, wheelIndex: Int, at: Vec3): JointRecord {
        val wheel = wheel(wheelIndex)
        return joint(
            label, piece.body, wheels.getValue(wheelIndex),
            CompiledConstraintFrame(toPieceLocal(piece, at), aboutXCompiled(-piece.restPitch)),
            CompiledConstraintFrame(at.subtract(belt.wheelCenters.getValue(wheel.index))),
            linear = List(3) { CompiledAxisLimit.Locked },
            angular = bend(),
        )
    }

    /**
     * The rear span keeps its line through the sprocket's tangent point but
     * slides along itself there; the joint's own spring pulls it onto the sprocket.
     */
    private fun driveSlide(piece: Piece, drive: Int): JointRecord {
        val wheel = wheel(drive)
        val travel = policy.tensionTravelM
        // Joint axes follow the span (frame A is the body frame), so Z is the span
        // axis. The wheel side is rotated by the span's rest pitch to match it.
        return joint(
            "rear_drive", piece.body, wheels.getValue(drive),
            CompiledConstraintFrame(toPieceLocal(piece, belt.rear.end), CompiledRotation.IDENTITY),
            CompiledConstraintFrame(belt.rear.end.subtract(belt.wheelCenters.getValue(wheel.index)), aboutXCompiled(piece.restPitch)),
            linear = listOf(CompiledAxisLimit.Locked, CompiledAxisLimit.Locked, CompiledAxisLimit.Limited(-travel, travel)),
            angular = bend(),
            configure = { joint ->
                if (travel > 0.0) {
                    // Belt tension as a spring row of the slide itself, solved with every
                    // other row, not a force injected from outside. An applied force is
                    // part of each body's own contact rows, so a force pair acting on two
                    // bodies that friction does not see together leaks through the
                    // ground contact and walks the whole vehicle (creep proportional to
                    // tension and step length, see TrackedEnvelopeBeltNativeTest).
                    // Pull is zero at one travel of overshoot and doubles at one travel
                    // of shortfall, so the preload holds across the slide's range.
                    val stiffness = (policy.tensionN / travel).toFloat()
                    joint.enableSpring(PhysicsJoints.LIN_Z, true)
                    joint.setStiffness(PhysicsJoints.LIN_Z, stiffness, false)
                    joint.setDamping(PhysicsJoints.LIN_Z, policy.tensionDampingNsPerM.toFloat(), false)
                    joint.setEquilibriumPoint(PhysicsJoints.LIN_Z, (-travel).toFloat())
                }
            },
        )
    }

    /** Current slide of the rear span along itself at the sprocket, metres; + toward the sprocket. */
    fun tensionSlideM(): Double {
        val r = rear ?: return 0.0
        val record = fixedJoints.firstOrNull { it.plan.name.endsWith("_rear_drive") } ?: return 0.0
        val a = worldOf(record.a, record.plan.frameInA.origin)
        val b = worldOf(record.b, record.plan.frameInB.origin)
        return r.body.getPhysicsRotation(null).rotate(Vector3f(0f, 0f, 1f)).dot(a.subtract(b)).toDouble()
    }

    /** The top return keeps its height and line at the idler but slides along itself. */
    private fun topSlide(piece: Piece, idler: Int): JointRecord {
        val wheel = wheel(idler)
        val travel = (wheel.tensioner?.travel ?: 0.1) * 3.0
        return joint(
            "top_idler", piece.body, wheels.getValue(idler),
            CompiledConstraintFrame(toPieceLocal(piece, belt.top.end), aboutXCompiled(-piece.restPitch)),
            CompiledConstraintFrame(belt.top.end.subtract(belt.wheelCenters.getValue(wheel.index))),
            linear = listOf(CompiledAxisLimit.Locked, CompiledAxisLimit.Locked, CompiledAxisLimit.Limited(-travel, travel)),
            angular = listOf(CompiledAxisLimit.Free, CompiledAxisLimit.Locked, CompiledAxisLimit.Locked),
        )
    }

    /**
     * A road wheel presses on the bottom piece beneath it. The joint axes follow
     * the piece (frame A is the body frame), so Z is along the piece and Y its
     * normal; the wheel side is rotated by the piece's rest pitch to match.
     *
     * - Along the piece the wheel slides freely; the suspension mount, not a
     *   second segment-end stop, defines its longitudinal position.
     * - Across the belt plane it stays put.
     * - Along the normal the contact is one-sided by default: the wheel can leave
     *   the belt (up to [WHEEL_LIFT_M]) but never presses into it, which is what
     *   a wheel on a track does. Every other degree of freedom is left to the
     *   mount, so the piece is not held by rows that only repeat each other.
     */
    private fun wheelLock(piece: Piece, wheelIndex: Int): JointRecord {
        val wheel = wheel(wheelIndex)
        val wheelBody = wheels.getValue(wheelIndex)
        val local = piece.body.getPhysicsRotation(null).inverse()
            .rotate(wheelBody.getPhysicsLocation(null).subtract(piece.body.getPhysicsLocation(null)))
        val restLocal = toPieceLocal(piece, belt.wheelCenters.getValue(wheel.index))
        val z = local.z.toDouble()
        val intoBelt = if (restLocal.y < 0.0) 1.0 else -1.0
        val normal = if (intoBelt > 0.0) {
            CompiledAxisLimit.Limited(-WHEEL_LIFT_M, 0.0)
        } else {
            CompiledAxisLimit.Limited(0.0, WHEEL_LIFT_M)
        }
        return joint(
            "wheel${wheelIndex}_lock", piece.body, wheelBody,
            CompiledConstraintFrame(Vec3(local.x.toDouble(), local.y.toDouble(), z), CompiledRotation.IDENTITY),
            CompiledConstraintFrame(Vec3.ZERO, aboutXCompiled(piece.restPitch)),
            // The wheel is carried on the normal, but must be able to slide
            // longitudinally as the bottom piece moves or changes length.
            // Limiting this axis to the current segment ends creates a second,
            // redundant longitudinal support row. At a split/merge that row
            // fights the suspension and showed up as a 28 mm wheel-lock
            // violation even though the one-sided normal limit was satisfied.
            linear = listOf(CompiledAxisLimit.Free, normal, CompiledAxisLimit.Free),
            angular = List(3) { CompiledAxisLimit.Free },
        )
    }

    private fun bend(): List<CompiledAxisLimit> = listOf(
        CompiledAxisLimit.Limited(-policy.maxBendRad, policy.maxBendRad),
        CompiledAxisLimit.Locked,
        CompiledAxisLimit.Locked,
    )

    private fun joint(
        label: String,
        a: PhysicsRigidBody,
        b: PhysicsRigidBody,
        frameA: CompiledConstraintFrame,
        frameB: CompiledConstraintFrame,
        linear: List<CompiledAxisLimit>,
        angular: List<CompiledAxisLimit>,
        configure: (New6Dof) -> Unit = {},
    ): JointRecord {
        // Logical references are labels only; the native bodies are passed in.
        val wheelB = wheels.entries.firstOrNull { it.value === b }?.key
        val plan = CompiledJointPlan(
            name = "${name}_$label",
            bodyA = CompiledBodyRef(CompiledBodyKind.RUN, side.side, 0),
            bodyB = if (wheelB != null) CompiledBodyRef(CompiledBodyKind.WHEEL, side.side, wheelB) else CompiledBodyRef(CompiledBodyKind.RUN, side.side, 1),
            frameInA = frameA,
            frameInB = frameB,
            linear = linear,
            angular = angular,
            collisionEnabled = false,
        )
        val created = TrackedJointFactory.create(plan, a, b) { world.recordJoint(it) }
        // No per-joint overrideIterations here, on purpose. Bullet solves contact and
        // friction rows only for the first numIterations passes, but a joint with an
        // override keeps iterating alone after them, so the joints get the last word
        // and every update leaves friction violated by one step of the chain's
        // unbalanced force: the parked vehicle then crept at tension x step / belt mass
        // (34 mm/s at 40 iterations, 3 mm/s without). See TrackedEnvelopeBeltNativeTest.
        configure(created)
        world.addJoint(created)
        return JointRecord(created, plan, a, b)
    }

    // ---------------------------------------------------------------- bodies

    private fun createPiece(kind: Kind, span: EnvelopeSpan, s0: Double, s1: Double): Piece {
        val length = s1 - s0
        val center = if (kind == Kind.BOTTOM) bottomRestCenter(s0, s1) else span.start.add(span.end).scale(0.5)
        val label = when (kind) {
            Kind.BOTTOM -> "bottom_${"%.2f".format(s0)}_${"%.2f".format(s1)}"
            else -> kind.name.lowercase()
        }
        return Piece(kind, createBody(label, length), s0, s1, center, span.pitchRad)
    }

    private fun createBody(label: String, lengthM: Double): PhysicsRigidBody {
        val halfWidth = (belt.widthM * 0.5).toFloat()
        val halfThick = half.toFloat()
        val halfLength = (lengthM * 0.5).toFloat()
        val margin = policy.collisionMarginM.toFloat()
        val shape = CompoundCollisionShape(3)
        shape.addChildShape(BoxCollisionShape(Vector3f(halfWidth, halfThick, halfLength)).apply { this.margin = margin }, Vector3f.ZERO)
        // Pin bosses: neighbours share this cylinder, so a bent pin stays closed.
        val boss = CylinderCollisionShape(Vector3f(halfWidth, halfThick, halfThick), PhysicsSpace.AXIS_X).apply { this.margin = margin }
        shape.addChildShape(boss, Vector3f(0f, 0f, -halfLength))
        shape.addChildShape(boss, Vector3f(0f, 0f, halfLength))
        val mass = (belt.massPerMetreKg * lengthM).toFloat()
        val body = PhysicsRigidBody(shape, mass)
        world.configure(body)
        body.name = "${name}_$label"
        // Exact box inertia of the span; the compound's AABB estimate would count the bosses twice.
        val t2 = (2f * halfThick) * (2f * halfThick)
        val w2 = (2f * halfWidth) * (2f * halfWidth)
        val l2 = (2f * halfLength) * (2f * halfLength)
        val scale = policy.inertiaScale.takeIf { it.isFinite() && it > 0.0 }?.toFloat() ?: 1f
        body.setInverseInertiaLocal(
            Vector3f(
                12f / (mass * (t2 + l2) * scale),
                12f / (mass * (w2 + l2) * scale),
                12f / (mass * (w2 + t2) * scale),
            ),
        )
        body.ccdMotionThreshold = halfThick
        body.ccdSweptSphereRadius = halfThick * 0.8f
        body.setDamping(.04f, .02f)
        body.restitution = 0f
        return body
    }

    // ---------------------------------------------------------------- geometry

    private fun wheel(index: Int): CompiledWheel = side.wheels.first { it.index == index }

    /** Where [point] is along [piece] now, metres from its centre. */
    private fun currentLocalZ(piece: Piece, point: Vector3f): Double =
        piece.body.getPhysicsRotation(null).inverse().rotate(point.subtract(piece.body.getPhysicsLocation(null))).z.toDouble()

    private fun bottomPoint(s: Double): Vec3 = belt.bottom.start.add(bottomDirection.scale(s))

    private fun bottomRestCenter(s0: Double, s1: Double): Vec3 = bottomPoint((s0 + s1) * 0.5)

    private fun arcLengthOf(point: Vec3): Double = point.subtract(belt.bottom.start).dot(bottomDirection)

    private fun toPieceLocal(piece: Piece, point: Vec3): Vec3 = toPieceLocal(piece.restCenter, piece.restPitch, point)

    /** Chassis rest point -> body frame of a span whose rest pose is [center] rotated by [pitch] about X. */
    private fun toPieceLocal(center: Vec3, pitch: Double, point: Vec3): Vec3 {
        val d = point.subtract(center)
        val c = cos(-pitch)
        val s = sin(-pitch)
        return Vec3(d.x, d.y * c - d.z * s, d.y * s + d.z * c)
    }

    // ---------------------------------------------------------------- read-out

    /** World position of a body-frame point. */
    private fun worldOf(body: PhysicsRigidBody, local: Vec3): Vector3f =
        body.getPhysicsLocation(null).add(body.getPhysicsRotation(null).rotate(local.toBullet()))

    /**
     * Worst separation of a locked pin, metres: span pins and wheel pins over
     * their full offset, wheel locks over the normal and lateral offset only.
     */
    fun worstPinGapM(): Double = worstPinGap().second

    /**
     * Worst separation between two belt spans at a shared pin, metres. This is
     * the belt's own continuity; wheel locks and wheel slides are excluded.
     */
    fun worstSpanPinGapM(): Double = worstPinGap { it.a !in wheels.values && it.b !in wheels.values }.second

    /** [worstPinGapM] with the name of the joint that has it. */
    fun worstPinGap(): Pair<String, Double> = worstPinGap { true }

    /** Worst actual wheel-lock limit violation, excluding belt-to-belt pins. */
    fun worstWheelLockViolation(): Pair<String, Double> =
        worstPinGap { it.plan.name.contains("_wheel") && it.b in wheels.values }

    /** Current one-sided wheel-lock limit state, including the authored local frame and violation. */
    fun wheelLockDiagnostics(): List<String> = bottomJoints
        .filter { it.plan.name.contains("_wheel") && it.b in wheels.values }
        .mapNotNull { record ->
            val a = worldOf(record.a, record.plan.frameInA.origin)
            val b = worldOf(record.b, record.plan.frameInB.origin)
            val diff = b.subtract(a)
            val frame = record.a.getPhysicsRotation(null).mult(record.plan.frameInA.rotation.toBullet())
            val local = frame.inverse().rotate(diff)
            val limits = record.plan.linear
            val parts = listOf(local.x.toDouble(), local.y.toDouble(), local.z.toDouble())
            val violations = parts.indices.map { axis ->
                when (val limit = limits[axis]) {
                    is CompiledAxisLimit.Locked -> abs(parts[axis])
                    is CompiledAxisLimit.Limited -> max(0.0, max(limit.lower - parts[axis], parts[axis] - limit.upper))
                    else -> 0.0
                }
            }
            val axis = violations.indices.maxByOrNull { violations[it] } ?: return@mapNotNull null
            val violation = violations[axis]
            if (violation <= 1.0e-5) return@mapNotNull null
            val wheelIndex = wheels.entries.firstOrNull { it.value === record.b }?.key
            val piece = bottom.firstOrNull { it.body === record.a }
            "${record.plan.name} axis=$axis q=(${"%.4f".format(local.x)},${"%.4f".format(local.y)},${"%.4f".format(local.z)}) " +
                "limit=${limits[axis]} violation=${"%.2f".format(violation * 1000)}mm " +
                "wheelCenter=${wheelIndex?.let { wheels.getValue(it).getPhysicsLocation(null) }} " +
                "piece=${piece?.let { "${it.s0}..${it.s1}" }}"
        }

    private fun worstPinGap(filter: (JointRecord) -> Boolean): Pair<String, Double> {
        var worst = 0.0
        var label = ""
        for (record in (fixedJoints + bottomJoints).filter(filter)) {
            val a = worldOf(record.a, record.plan.frameInA.origin)
            val b = worldOf(record.b, record.plan.frameInB.origin)
            val diff = b.subtract(a)
            val frame = record.a.getPhysicsRotation(null).mult(record.plan.frameInA.rotation.toBullet())
            val local = frame.inverse().rotate(diff)
            // Only a violated limit is an error: a locked axis must be zero, a limited one
            // inside its range (a wheel lifting off the belt is allowed), a free one anything.
            var sum = 0.0
            val parts = doubleArrayOf(local.x.toDouble(), local.y.toDouble(), local.z.toDouble())
            for (axis in 0..2) {
                val error = when (val limit = record.plan.linear[axis]) {
                    is CompiledAxisLimit.Locked -> abs(parts[axis])
                    is CompiledAxisLimit.Limited -> max(0.0, max(limit.lower - parts[axis], parts[axis] - limit.upper))
                    else -> 0.0
                }
                sum += error * error
            }
            val gap = sqrt(sum)
            if (gap > worst) {
                worst = gap
                label = record.plan.name + " local=(${"%.3f".format(local.x)}, ${"%.3f".format(local.y)}, ${"%.3f".format(local.z)})"
            }
        }
        return label to worst
    }

    /** Worst bend of any pin between bottom pieces, radians. */
    fun worstBottomBendRad(): Double =
        (0 until bottom.size - 1).maxOfOrNull { abs(pinBend(bottom[it], bottom[it + 1])) } ?: 0.0

    /**
     * The closed belt centre line in world space, in belt order: front span,
     * bottom pins, rear span, drive wrap arc, top return, idler wrap arc.
     * Shared pins are the mean of both bodies' ends, so a solver gap of a few
     * millimetres is not drawn as a hole.
     */
    fun loopWorld(): List<Vector3f> {
        val f = front ?: return emptyList()
        val r = rear ?: return emptyList()
        val t = top ?: return emptyList()
        fun start(piece: Piece) = worldOf(piece.body, Vec3(0.0, 0.0, -piece.lengthM * 0.5))
        fun end(piece: Piece) = worldOf(piece.body, Vec3(0.0, 0.0, piece.lengthM * 0.5))
        fun mean(a: Vector3f, b: Vector3f) = a.add(b).multLocal(0.5f)
        val points = ArrayList<Vector3f>()
        points += start(f)
        points += mean(end(f), start(bottom.first()))
        for (index in 0 until bottom.size - 1) points += mean(end(bottom[index]), start(bottom[index + 1]))
        points += mean(end(bottom.last()), start(r))
        points += end(r)
        points += wrapPoints(belt.driveWheel)
        points += start(t)
        points += wrapPoints(belt.idlerWheel)
        // Drop coincident neighbours (wrap ends meet span ends).
        val out = ArrayList<Vector3f>(points.size)
        for (point in points) if (out.isEmpty() || out.last().distance(point) > 5.0e-3f) out += point
        if (out.size > 1 && out.first().distance(out.last()) <= 5.0e-3f) out.removeAt(out.size - 1)
        return out
    }

    /** Interior points of a wheel's wrap arc in world space, entry to exit. */
    private fun wrapPoints(wheelIndex: Int): List<Vector3f> {
        val pieces = belt.wraps[wheelIndex] ?: return emptyList()
        val body = wheels.getValue(wheelIndex)
        val wheel = wheel(wheelIndex)
        val dx = side.belt.centerPlaneX - belt.wheelCenters.getValue(wheel.index).x
        val out = ArrayList<Vector3f>(pieces.size + 1)
        for ((index, piece) in pieces.withIndex()) {
            val direction = Vec3(0.0, -sin(piece.pitchRad), cos(piece.pitchRad))
            if (index == 0) {
                val p = piece.center.subtract(direction.scale(piece.halfLengthM))
                out += worldOf(body, Vec3(dx, p.y, p.z))
            }
            val p = piece.center.add(direction.scale(piece.halfLengthM))
            out += worldOf(body, Vec3(dx, p.y, p.z))
        }
        return out
    }

    /** Mean velocity of the bottom pieces, world frame. */
    fun bottomVelocity(): Vector3f {
        if (bottom.isEmpty()) return Vector3f()
        val sum = Vector3f()
        var mass = 0f
        for (piece in bottom) {
            sum.addLocal(piece.body.getLinearVelocity(null).mult(piece.body.mass))
            mass += piece.body.mass
        }
        return sum.multLocal(1f / mass)
    }

    companion object {
        /** How far a wheel may lift off the belt, metres. */
        private const val WHEEL_LIFT_M = 0.30

        /** Longest hollow a rigid bottom piece is assumed to bridge, metres. */
        private const val BRIDGE_M = 1.5

        private fun Quaternion.rotate(vector: Vector3f): Vector3f = toRotationMatrix().mult(vector, Vector3f())

        private fun Vec3.toBullet(): Vector3f = Vector3f(x.toFloat(), y.toFloat(), z.toFloat())

        private fun CompiledRotation.toBullet(): Quaternion = Quaternion(x, y, z, w)

        private fun aboutX(angle: Double): Quaternion {
            val h = angle * 0.5
            return Quaternion(sin(h).toFloat(), 0f, 0f, cos(h).toFloat())
        }

        private fun aboutXCompiled(angle: Double): CompiledRotation {
            val h = angle * 0.5
            return CompiledRotation(sin(h).toFloat(), 0f, 0f, cos(h).toFloat())
        }

        /**
         * Collision shape of a belt wheel. A guide wheel the belt wraps carries
         * its wrap chords as compound children, so the wrap moves with the wheel
         * and touches terrain like the rest of the belt.
         */
        fun wheelShape(side: CompiledTrackSide, wheel: CompiledWheel): com.jme3.bullet.collision.shapes.CollisionShape {
            val cylinder = CylinderCollisionShape(
                Vector3f(wheel.halfWidth.toFloat(), wheel.radius.toFloat(), wheel.radius.toFloat()),
                PhysicsSpace.AXIS_X,
            ).apply { margin = .001f }
            val envelope = side.envelope ?: return cylinder
            val chords = envelope.wraps[wheel.index] ?: return cylinder
            val compound = CompoundCollisionShape(chords.size + 1)
            compound.addChildShape(cylinder, Vector3f.ZERO)
            val dx = (side.belt.centerPlaneX - wheel.assemblyCenter.x).toFloat()
            for (chord in chords) {
                val box = BoxCollisionShape(
                    Vector3f((envelope.widthM * 0.5).toFloat(), (envelope.thicknessM * 0.5).toFloat(), chord.halfLengthM.toFloat()),
                ).apply { margin = envelope.policy.collisionMarginM.toFloat() }
                compound.addChildShape(
                    box,
                    Vector3f(dx, chord.center.y.toFloat(), chord.center.z.toFloat()),
                    aboutX(chord.pitchRad).toRotationMatrix(),
                )
            }
            return compound
        }

        /** Inverse inertia of the wheel's own cylinder; wrap chords add no mass. */
        fun wheelInverseInertia(wheel: CompiledWheel): Vector3f {
            val m = wheel.massKg
            val r2 = wheel.radius * wheel.radius
            val w2 = (2.0 * wheel.halfWidth) * (2.0 * wheel.halfWidth)
            val axial = m * r2 * 0.5
            val radial = m * (3.0 * r2 + w2) / 12.0
            return Vector3f((1.0 / axial).toFloat(), (1.0 / radial).toFloat(), (1.0 / radial).toFloat())
        }

        /**
         * Resample a closed polyline to [count] points spaced evenly by arc
         * length, starting at its first point. A fixed count lets the client
         * interpolate between snapshots whatever the piece count was.
         */
        fun resample(ys: List<Double>, zs: List<Double>, count: Int): Pair<List<Double>, List<Double>> {
            val n = min(ys.size, zs.size)
            if (n < 2 || count < 3) return ys.take(n) to zs.take(n)
            val cumulative = DoubleArray(n + 1)
            for (i in 0 until n) {
                val j = (i + 1) % n
                cumulative[i + 1] = cumulative[i] + kotlin.math.hypot(ys[j] - ys[i], zs[j] - zs[i])
            }
            val total = cumulative[n]
            if (!total.isFinite() || total <= 1.0e-9) return ys.take(n) to zs.take(n)
            val outY = ArrayList<Double>(count)
            val outZ = ArrayList<Double>(count)
            var segment = 0
            for (k in 0 until count) {
                val s = total * k / count
                while (segment < n - 1 && cumulative[segment + 1] < s) segment++
                val length = cumulative[segment + 1] - cumulative[segment]
                val f = if (length > 1.0e-12) (s - cumulative[segment]) / length else 0.0
                val j = (segment + 1) % n
                outY += ys[segment] + (ys[j] - ys[segment]) * f
                outZ += zs[segment] + (zs[j] - zs[segment]) * f
            }
            return outY to outZ
        }

        private fun wrap(angle: Double): Double {
            var a = angle
            while (a > Math.PI) a -= 2.0 * Math.PI
            while (a < -Math.PI) a += 2.0 * Math.PI
            return a
        }
    }
}

/** Geometry-only checks shared by the live repartition path and its T0 regression. */
internal object TrackedBeltGeometry {
    /**
     * Float Bullet poses cannot represent a material/chord mismatch as a rigid span.
     * The tolerance follows the precision of the world coordinates being replaced.
     */
    fun isRepresentable(
        materialLengths: List<Double>,
        points: List<Vector3f>,
    ): Boolean {
        if (materialLengths.isEmpty() || materialLengths.size != points.size - 1) return false
        val coordinateScale = points.maxOfOrNull { point ->
            max(abs(point.x.toDouble()), max(abs(point.y.toDouble()), abs(point.z.toDouble())))
        } ?: return false
        if (!coordinateScale.isFinite()) return false
        val tolerance = max(
            Math.ulp(coordinateScale.toFloat()).toDouble() * 8.0,
            materialLengths.maxOf { Math.ulp(it.toFloat()).toDouble() * 8.0 },
        )
        return materialLengths.indices.all { index ->
            val material = materialLengths[index]
            val chord = points[index].distance(points[index + 1]).toDouble()
            material.isFinite() && chord.isFinite() && material > 0.0 && chord > 0.0 &&
                abs(material - chord) <= tolerance
        }
    }

    /** Place the new internal pin so each inextensible span exactly fits its chord. */
    fun fitPolyline(
        materialLengths: List<Double>,
        desiredPoints: List<Vector3f>,
        maxBendRad: Double,
        endpointSlackM: Double = 0.0,
    ): List<Vector3f>? {
        if (materialLengths.size == 1 && desiredPoints.size == 2) {
            val start = desiredPoints.first()
            val end = desiredPoints.last()
            val delta = end.subtract(start)
            val chord = delta.length().toDouble()
            val material = materialLengths.single()
            val coordinateTolerance = max(
                Math.ulp(max(abs(start.z), abs(end.z))).toDouble() * 8.0,
                Math.ulp(material.toFloat()).toDouble() * 8.0,
            )
            if (!endpointSlackM.isFinite() || endpointSlackM < 0.0 || !chord.isFinite() || chord <= 1.0e-8 ||
                abs(material - chord) > endpointSlackM + coordinateTolerance
            ) return null
            val fittedEnd = start.add(delta.normalize().mult(material.toFloat()))
            return listOf(start, fittedEnd)
        }
        if (materialLengths.size != 2 || desiredPoints.size != 3) {
            return desiredPoints.takeIf { isRepresentable(materialLengths, it) }
        }
        val start = desiredPoints.first()
        val desired = desiredPoints[1]
        val end = desiredPoints.last()
        val delta = end.subtract(start)
        val chord = delta.length().toDouble()
        val firstLength = materialLengths[0]
        val secondLength = materialLengths[1]
        if (!chord.isFinite() || chord <= 1.0e-8 || firstLength <= 0.0 || secondLength <= 0.0) return null
        if (chord > firstLength + secondLength || chord < abs(firstLength - secondLength)) return null

        val direction = delta.normalize()
        val along = (firstLength * firstLength - secondLength * secondLength + chord * chord) / (2.0 * chord)
        val heightSq = firstLength * firstLength - along * along
        if (heightSq < -1.0e-8) return null
        val height = kotlin.math.sqrt(max(0.0, heightSq)).toFloat()
        val base = start.add(direction.mult(along.toFloat()))
        var perpendicular = Vector3f(0f, -direction.z, direction.y)
        if (perpendicular.lengthSquared() < 1.0e-10f) return null
        perpendicular = perpendicular.normalize()
        val candidates = listOf(base.add(perpendicular.mult(height)), base.subtract(perpendicular.mult(height)))
        val pin = candidates.minBy { it.distance(desired) }
        val first = pin.subtract(start).normalize()
        val second = end.subtract(pin).normalize()
        val bend = kotlin.math.acos(first.dot(second).coerceIn(-1f, 1f).toDouble())
        if (!bend.isFinite() || bend > maxBendRad) return null
        val result = listOf(start, pin, end)
        return result.takeIf { isRepresentable(materialLengths, it) }
    }

    fun canMerge(
        straightSeconds: Double,
        holdSeconds: Double,
        bendRad: Double,
        mergeRad: Double,
        materialLengths: List<Double>,
        points: List<Vector3f>,
        endpointSlackM: Double = 0.0,
    ): Boolean = straightSeconds >= holdSeconds && abs(bendRad) < mergeRad &&
        fitPolyline(materialLengths, points, Double.POSITIVE_INFINITY, endpointSlackM) != null
}
