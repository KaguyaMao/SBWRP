package com.atsuishio.superbwarfare.physics.tracked

import cn.solarmoon.spark_core.physics.NativePhysicsLoader
import cn.solarmoon.spark_core.physics.PhysicsHost
import cn.solarmoon.spark_core.physics.body.CollisionGroups
import cn.solarmoon.spark_core.physics.body.PhysicsJoints
import cn.solarmoon.spark_core.physics.body.collideWithOwnerGroups
import cn.solarmoon.spark_core.physics.body.owner
import com.jme3.bullet.PhysicsSpace
import com.jme3.bullet.RotationOrder
import com.jme3.bullet.collision.PhysicsCollisionObject
import com.jme3.bullet.collision.shapes.BoxCollisionShape
import com.jme3.bullet.collision.shapes.CylinderCollisionShape
import com.jme3.bullet.collision.shapes.MeshCollisionShape
import com.jme3.bullet.collision.shapes.infos.IndexedMesh
import com.jme3.bullet.joints.New6Dof
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.math.Matrix3f
import com.jme3.math.Quaternion
import com.jme3.math.Vector3f
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import net.minecraft.world.phys.Vec3
import kotlin.math.max
import kotlin.math.sqrt

/**
 * T1 prototype of the anchor-driven belt chain.
 *
 * Wheels are kinematic and scripted, so the measurements isolate the belt: pin
 * gaps, fit to the ground and jitter. Suspension, drive and the hull are covered
 * elsewhere and are deliberately absent. Nothing here is a pass limit; limits
 * are not frozen, so the numbers are printed for review and the assertions only
 * guard against a broken fixture (NaN, a collapsed chain).
 */
class TrackedBeltChainNativeTest {

    init {
        NativePhysicsLoader.load()
    }

    private val spec: TrackedVehicleSpec = Json { ignoreUnknownKeys = true; isLenient = true }.let { json ->
        TrackedVehicleCompiler.compile(
            "superbwarfare:ztz_99a",
            json.decodeFromString(
                TrackedVehicleDefinition.serializer(),
                File("src/main/resources/data/superbwarfare/sbw/tracked/ztz_99a.json").readText(),
            ),
            File("src/main/resources/assets/superbwarfare/models/bedrock/vehicle/ztz_99a.geo.json").readText(),
        ).requireSpec()
    }

    /** Candidate passive tensioner: constant preload, damping, hard travel limit. */
    private data class Tensioner(val preloadN: Double, val dampingNsPerM: Double, val travelM: Double)

    /** Ground height under world z; the flat case is 0, the ramp case follows the planner. */
    private class Ground(val height: (Double) -> Double)

    private class Rig(
        val substeps: Int,
        val space: PhysicsSpace,
        val side: CompiledTrackSide,
        val chain: CompiledBeltChain,
        val wheels: Map<Int, PhysicsRigidBody>,
        val segments: List<PhysicsRigidBody>,
        /** World height of the chassis origin at rest. */
        val restHeight: Double,
        /** Kinematic mounts the script moves; a wheel without an entry is scripted directly. */
        val mounts: Map<Int, PhysicsRigidBody>,
        val tensioner: Tensioner?,
    ) {
        var tensionerRest = 0.0
        var previousSlide = 0.0
        var peakSlide = 0.0
        var lowSlide = 0.0
        var pinRoadEnds = true
        /** Present in the dynamic rig: the sprung hull the wheels are mounted to. */
        var hull: PhysicsRigidBody? = null
    }

    private fun v(x: Double, y: Double, z: Double) = Vector3f(x.toFloat(), y.toFloat(), z.toFloat())

    private fun aboutX(angle: Double): Quaternion = Quaternion().fromAngleNormalAxis(angle.toFloat(), Vector3f(1f, 0f, 0f))

    private fun matrix(q: Quaternion): Matrix3f = Matrix3f().set(q)

    private fun build(
        policy: BeltChainPolicy,
        terrain: TerrainMeshPlan?,
        substeps: Int,
        edgeFilter: Boolean = false,
        iterations: Int = 0,
        tensioner: Tensioner? = null,
        /** True: the bottom run's end pins are forked to road wheels 1 and 6. False: ordinary hinges. */
        pinRoadEnds: Boolean = true,
        /** Longitudinal travel of the end pins on road wheels 1 and 6, metres; 0 locks them. */
        endTravelM: Double = 0.0,
        /** Constraint-force mixing on every pin's linear axes; 0 is a hard pin. */
        pinCfm: Float = 0f,
        /** Dynamic wheels on the compiled mounts under a free heave/pitch hull, instead of scripted wheels. */
        dynamic: Boolean = false,
        /** Friction of the chain units on terrain. */
        unitFriction: Float = .8f,
        /** Mass override per wheel role, kg; null keeps the compiled mass. */
        roadWheelKg: Double? = null,
        guideWheelKg: Double? = null,
        /** Multiplier on the chain units' rotational inertia; statics are unchanged. */
        unitInertiaScale: Float = 1f,
        /** Multiplier on the hull mass (sensitivity only: it changes the vehicle). */
        hullMassScale: Double = 1.0,
        /** Multiplier on every chain unit's mass; the extra mass is taken from the hull so the total is kept. */
        unitMassScale: Double = 1.0,
        /** Collision thickness of bottom units, metres; grows toward the loop interior, contact face unchanged. */
        bottomCollisionThickness: Double? = null,
        /** Error-reduction parameter on every pin's locked axes; 0 keeps Bullet's default. */
        pinErp: Float = 0f,
    ): Rig {
        val side = spec.side(TrackedSide.LEFT)
        val chain = TrackedBeltChainCompiler.compile(side, spec.pitch, policy)
        val thickness = side.belt.thicknessM
        val bottomY = side.anchors.minOf { it.bindPoint.y }
        // Chassis origin height that puts the belt's lower surface on y = 0.
        val rest = thickness * 0.5 - bottomY

        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
        space.setGravity(Vector3f(0f, -9.81f, 0f))
        val host = object : PhysicsHost {
            override val physicsLevel: cn.solarmoon.spark_core.physics.level.PhysicsLevel
                get() = throw UnsupportedOperationException("native prototype has no PhysicsLevel")
            override val allPhysicsBodies: MutableMap<String, PhysicsCollisionObject> = ConcurrentHashMap()
        }

        if (terrain != null) {
            val points = Array(terrain.vertices.size / 3) {
                Vector3f(terrain.vertices[it * 3], terrain.vertices[it * 3 + 1], terrain.vertices[it * 3 + 2])
            }
            val shape = MeshCollisionShape(true, IndexedMesh(points, terrain.indices)).apply {
                margin = .001f
                setContactFilterEnabled(edgeFilter)
            }
            val body = PhysicsRigidBody(shape, 0f)
            body.setPhysicsLocation(v(terrain.originX, terrain.originY, terrain.originZ))
            body.friction = .8f
            body.owner = host
            body.collisionGroup = TrackedTerrainCollision.TERRAIN_MESH
            body.setCollideWithGroups(CollisionGroups.NONE)
            body.collideWithOwnerGroups = CollisionGroups.TRACK_LINK
            space.addCollisionObject(body)
        } else {
            val floor = PhysicsRigidBody(BoxCollisionShape(60f, 0.5f, 60f), 0f)
            floor.setPhysicsLocation(Vector3f(0f, -0.5f, 0f))
            floor.friction = .8f
            floor.owner = host
            floor.collisionGroup = TrackedTerrainCollision.TERRAIN_MESH
            floor.setCollideWithGroups(CollisionGroups.NONE)
            floor.collideWithOwnerGroups = CollisionGroups.TRACK_LINK
            space.addCollisionObject(floor)
        }

        val wheels = HashMap<Int, PhysicsRigidBody>()
        val mounts = HashMap<Int, PhysicsRigidBody>()
        for (wheel in side.wheels) {
            val shape = CylinderCollisionShape(
                Vector3f(wheel.halfWidth.toFloat(), wheel.radius.toFloat(), wheel.radius.toFloat()), 0,
            )
            val at = v(wheel.assemblyCenter.x, wheel.assemblyCenter.y + rest, wheel.assemblyCenter.z)
            val tensioned = !dynamic && tensioner != null && wheel.role == TrackWheelRole.IDLER_GUIDE
            val mass = when {
                wheel.isSuspended -> roadWheelKg ?: wheel.massKg
                else -> guideWheelKg ?: wheel.massKg
            }
            val body = PhysicsRigidBody(shape, if (tensioned || dynamic) mass.toFloat() else 0f)
            if (!tensioned && !dynamic) body.isKinematic = true
            if (dynamic) body.setSleepingThresholds(0f, 0f)
            body.collisionGroup = CollisionGroups.WHEEL
            body.setCollideWithGroups(CollisionGroups.NONE)
            body.setPhysicsLocation(at)
            body.owner = host
            if (tensioned) body.setSleepingThresholds(0f, 0f)
            space.addCollisionObject(body)
            wheels[wheel.index] = body
            if (tensioned) {
                // The scripted mount carries the hull pose; the wheel slides on it
                // along the hull axis within a hard mechanical limit.
                val mount = PhysicsRigidBody(BoxCollisionShape(0.05f), 0f)
                mount.isKinematic = true
                mount.collisionGroup = CollisionGroups.WHEEL
                mount.setCollideWithGroups(CollisionGroups.NONE)
                mount.setPhysicsLocation(at)
                mount.owner = host
                space.addCollisionObject(mount)
                mounts[wheel.index] = mount
                val slide = New6Dof(
                    mount, body, Vector3f(), Vector3f(), Matrix3f.IDENTITY, Matrix3f.IDENTITY, RotationOrder.XYZ,
                )
                PhysicsJoints.lock(slide, PhysicsJoints.LIN_X)
                PhysicsJoints.lock(slide, PhysicsJoints.LIN_Y)
                PhysicsJoints.limit(
                    slide, PhysicsJoints.LIN_Z, -tensioner!!.travelM.toFloat(), tensioner.travelM.toFloat(),
                )
                for (axis in PhysicsJoints.ANG_X..PhysicsJoints.ANG_Z) PhysicsJoints.lock(slide, axis)
                space.addJoint(slide)
            }
        }

        var hull: PhysicsRigidBody? = null
        if (dynamic) {
            // One side's share of the sprung assembly. Roll and lateral motion are
            // frozen because the other side is absent; heave and pitch are free.
            val unitExtra = chain.massKg * (unitMassScale - 1.0)
            val mass = (spec.sprungAssemblyMassKg / 2.0 - unitExtra) * hullMassScale
            val body = PhysicsRigidBody(BoxCollisionShape(0.5f), mass.toFloat())
            body.collisionGroup = CollisionGroups.CHASSIS
            body.setCollideWithGroups(CollisionGroups.NONE)
            body.setPhysicsLocation(v(0.0, rest, 0.0))
            body.setLinearFactor(Vector3f(0f, 1f, 1f))
            body.setAngularFactor(Vector3f(1f, 0f, 0f))
            val length = 7.0
            val height = 1.5
            val pitchInertia = mass * (length * length + height * height) / 12.0
            body.setInverseInertiaLocal(Vector3f((1.0 / pitchInertia).toFloat(), 0f, 0f))
            body.setSleepingThresholds(0f, 0f)
            body.owner = host
            space.addCollisionObject(body)
            hull = body
            for (wheel in side.wheels) {
                val plan = side.mount(wheel.index).joint ?: continue
                val a = if (plan.bodyA.kind == CompiledBodyKind.CHASSIS) body else wheels.getValue(wheel.index)
                val b = if (plan.bodyB.kind == CompiledBodyKind.CHASSIS) body else wheels.getValue(wheel.index)
                space.addJoint(TrackedJointFactory.create(plan, a, b))
            }
        }

        val loopCenter = chain.segments.fold(Vec3.ZERO) { sum, s -> sum.add(s.center) }.scale(1.0 / chain.segments.size)
        val bodies = chain.segments.map { segment ->
            val thick = bottomCollisionThickness
            val shape: com.jme3.bullet.collision.shapes.CollisionShape =
                if (thick != null && segment.kind == BeltSegmentKind.BOTTOM && thick > segment.halfExtents.y * 2.0) {
                    // Grow toward the loop interior only, so the ground face does not move.
                    val half = thick * 0.5
                    val localY = Vec3(0.0, kotlin.math.cos(segment.pitchRad), kotlin.math.sin(segment.pitchRad))
                    val inward = loopCenter.subtract(segment.center)
                    val sign = if (localY.y * inward.y + localY.z * inward.z >= 0.0) 1.0 else -1.0
                    val offset = (half - segment.halfExtents.y) * sign
                    com.jme3.bullet.collision.shapes.CompoundCollisionShape(1).apply {
                        addChildShape(
                            BoxCollisionShape(segment.halfExtents.x.toFloat(), half.toFloat(), segment.halfExtents.z.toFloat())
                                .apply { margin = .001f },
                            Vector3f(0f, offset.toFloat(), 0f),
                        )
                    }
                } else {
                    BoxCollisionShape(
                        segment.halfExtents.x.toFloat(), segment.halfExtents.y.toFloat(), segment.halfExtents.z.toFloat(),
                    ).apply { margin = .001f }
                }
            val body = PhysicsRigidBody(shape, (segment.massKg * unitMassScale).toFloat())
            if (shape is com.jme3.bullet.collision.shapes.CompoundCollisionShape) {
                // Keep the inertia of the thin plate the chain was designed with.
                val plate = BoxCollisionShape(
                    segment.halfExtents.x.toFloat(), segment.halfExtents.y.toFloat(), segment.halfExtents.z.toFloat(),
                )
                val probe = PhysicsRigidBody(plate, (segment.massKg * unitMassScale).toFloat())
                body.setInverseInertiaLocal(probe.getInverseInertiaLocal(null))
            }
            body.setPhysicsLocation(v(segment.center.x, segment.center.y + rest, segment.center.z))
            body.setPhysicsRotation(aboutX(segment.pitchRad))
            body.owner = host
            body.collisionGroup = CollisionGroups.TRACK_LINK
            body.setCollideWithGroups(TrackedTerrainCollision.TERRAIN_MESH)
            body.collideWithOwnerGroups = TrackedTerrainCollision.TERRAIN_MESH
            body.friction = unitFriction
            if (unitInertiaScale != 1f) {
                val inverse = body.getInverseInertiaLocal(null)
                body.setInverseInertiaLocal(inverse.mult(1f / unitInertiaScale))
            }
            body.setSleepingThresholds(0f, 0f)
            body.setDamping(.02f, .05f)
            space.addCollisionObject(body)
            body
        }

        val limit = Math.toRadians(policy.maxBendDegrees).toFloat()
        val roadIndices = side.wheels.filter { it.isSuspended }.map { it.index }.toSet()
        fun carried(wheel: Int) = wheel >= 0 && (pinRoadEnds || wheel !in roadIndices)

        fun lockLinear(joint: New6Dof) {
            for (axis in 0..2) {
                PhysicsJoints.lock(joint, axis)
                if (pinCfm > 0f) joint.set(com.jme3.bullet.joints.motors.MotorParam.StopCfm, axis, pinCfm)
                if (pinErp > 0f) joint.set(com.jme3.bullet.joints.motors.MotorParam.StopErp, axis, pinErp)
            }
        }
        fun tune(joint: New6Dof) {
            if (iterations > 0) joint.overrideIterations(iterations)
        }

        // Interior pins are hinges. A pin carried by a wheel on both sides is a
        // fork: each segment pins to the wheel instead, so the wheel owns the point.
        for (index in chain.segments.indices) {
            val a = chain.segments[index]
            val b = chain.next(index)
            val bodyA = bodies[index]
            val bodyB = bodies[b.index]
            if (carried(a.endWheel) && carried(b.startWheel)) continue
            val joint = New6Dof(
                bodyA, bodyB,
                v(0.0, 0.0, a.lengthM * 0.5), v(0.0, 0.0, -b.lengthM * 0.5),
                matrix(aboutX(-a.pitchRad)), matrix(aboutX(-b.pitchRad)),
                RotationOrder.XYZ,
            )
            lockLinear(joint)
            PhysicsJoints.limit(joint, PhysicsJoints.ANG_X, -limit, limit)
            PhysicsJoints.lock(joint, PhysicsJoints.ANG_Y)
            PhysicsJoints.lock(joint, PhysicsJoints.ANG_Z)
            tune(joint)
            space.addJoint(joint)
        }
        fun pinToWheel(segment: BeltChainSegment, wheelIndex: Int, pin: com.jme3.math.Vector3f, atEnd: Boolean) {
            val wheel = side.wheels.single { it.index == wheelIndex }
            val joint = New6Dof(
                bodies[segment.index], wheels.getValue(wheelIndex),
                v(0.0, 0.0, if (atEnd) segment.lengthM * 0.5 else -segment.lengthM * 0.5),
                Vector3f(pin.x - wheel.assemblyCenter.x.toFloat(), pin.y - wheel.assemblyCenter.y.toFloat(), pin.z - wheel.assemblyCenter.z.toFloat()),
                matrix(aboutX(-segment.pitchRad)), Matrix3f.IDENTITY,
                RotationOrder.XYZ,
            )
            lockLinear(joint)
            if (endTravelM > 0.0 && wheelIndex in roadIndices) {
                // Joint axes are chassis axes at rest, so LIN_Z is longitudinal.
                PhysicsJoints.limit(joint, PhysicsJoints.LIN_Z, -endTravelM.toFloat(), endTravelM.toFloat())
            }
            PhysicsJoints.limit(joint, PhysicsJoints.ANG_X, -limit, limit)
            PhysicsJoints.lock(joint, PhysicsJoints.ANG_Y)
            PhysicsJoints.lock(joint, PhysicsJoints.ANG_Z)
            tune(joint)
            space.addJoint(joint)
        }
        for (segment in chain.segments) {
            if (carried(segment.startWheel)) {
                pinToWheel(segment, segment.startWheel, v(segment.start.x, segment.start.y, segment.start.z), false)
            }
            if (carried(segment.endWheel)) {
                pinToWheel(segment, segment.endWheel, v(segment.end.x, segment.end.y, segment.end.z), true)
            }
        }

        // Road wheels between the end pins hold the unit beneath them along its
        // normal only. The foot point slides along the unit, so the chain can
        // fold between wheels without being dragged.
        val bottoms = chain.segments.filter { it.kind == BeltSegmentKind.BOTTOM }
        for (wheel in side.wheels.filter { it.isSuspended }) {
            val unit = bottoms.firstOrNull { wheel.assemblyCenter.z <= max(it.start.z, it.end.z) &&
                wheel.assemblyCenter.z >= minOf(it.start.z, it.end.z) } ?: continue
            if (carried(unit.startWheel) && unit.startWheel == wheel.index) continue
            if (carried(unit.endWheel) && unit.endWheel == wheel.index) continue
            // The lock point is where the chain's own rest line passes under the
            // wheel, not an idealised r + t/2 below it. The authored anchors and
            // the wheel geometry differ by a few millimetres; forcing the chain
            // onto the wheel instead would load an inextensible chain at rest.
            val s = (wheel.assemblyCenter.z - unit.start.z) / (unit.end.z - unit.start.z)
            val line = Vec3(
                wheel.assemblyCenter.x,
                unit.start.y + (unit.end.y - unit.start.y) * s,
                wheel.assemblyCenter.z,
            )
            val foot = Vector3f(
                (line.x - wheel.assemblyCenter.x).toFloat(),
                (line.y - wheel.assemblyCenter.y).toFloat(),
                (line.z - wheel.assemblyCenter.z).toFloat(),
            )
            val dir = Vec3(0.0, unit.end.y - unit.start.y, unit.end.z - unit.start.z).normalize()
            val offset = Vec3(line.x - unit.center.x, line.y - unit.center.y, line.z - unit.center.z)
            val along = (offset.y * dir.y + offset.z * dir.z).toFloat()
            val joint = New6Dof(
                bodies[unit.index], wheels.getValue(wheel.index),
                Vector3f(0f, 0f, along), foot,
                matrix(aboutX(-unit.pitchRad)), Matrix3f.IDENTITY,
                RotationOrder.XYZ,
            )
            PhysicsJoints.lock(joint, PhysicsJoints.LIN_X)
            PhysicsJoints.lock(joint, PhysicsJoints.LIN_Y)
            PhysicsJoints.limit(
                joint, PhysicsJoints.LIN_Z, -(unit.lengthM * 0.5).toFloat(), (unit.lengthM * 0.5).toFloat(),
            )
            PhysicsJoints.free(joint, PhysicsJoints.ANG_X)
            PhysicsJoints.lock(joint, PhysicsJoints.ANG_Y)
            PhysicsJoints.lock(joint, PhysicsJoints.ANG_Z)
            tune(joint)
            space.addJoint(joint)
        }
        return Rig(substeps, space, side, chain, wheels, bodies, rest, mounts, tensioner).also {
            it.pinRoadEnds = pinRoadEnds
            it.hull = hull
        }
    }

    /** One 1/60 s tick, split into [Rig.substeps] fixed Bullet steps. */
    private fun Rig.step() {
        val dt = 1.0 / 60.0 / substeps
        repeat(substeps) {
            val tension = tensioner
            if (tension != null) {
                for ((index, mount) in mounts) {
                    val wheel = wheels.getValue(index)
                    val slide = (wheel.getPhysicsLocation(null).z - mount.getPhysicsLocation(null).z).toDouble()
                    val rate = (slide - previousSlide) / dt
                    previousSlide = slide
                    peakSlide = max(peakSlide, slide)
                    lowSlide = minOf(lowSlide, slide)
                    // Preload pushes the idler forward (+Z) to take up slack; damping
                    // resists its sliding speed. The joint's limit is the hard stop.
                    val force = tension.preloadN - tension.dampingNsPerM * rate
                    wheel.applyCentralForce(v(0.0, 0.0, force))
                }
            }
            hull?.let { applySuspension(it) }
            space.update(dt.toFloat(), 0, false, true, false)
        }
    }

    /** Production row forces, as `TrackedVehicleRuntime.applySuspension` computes them. */
    private fun Rig.applySuspension(hull: PhysicsRigidBody) {
        val hullRotation = hull.getPhysicsRotation(null)
        val hullMatrix = hullRotation.toRotationMatrix()
        val hullInverse = hullRotation.inverse().toRotationMatrix()
        val hullLocation = hull.getPhysicsLocation(null)
        for (wheel in side.wheels) {
            val mount = side.mount(wheel.index)
            val row = mount.springRow ?: continue
            val plan = mount.joint ?: continue
            val body = wheels.getValue(wheel.index)
            val frame = Quaternion(plan.frameInA.rotation.x, plan.frameInA.rotation.y, plan.frameInA.rotation.z, plan.frameInA.rotation.w)
                .normalizeLocal()
            val axis = hullMatrix.mult(frame.toRotationMatrix().mult(Vector3f(0f, 1f, 0f), Vector3f()), Vector3f()).normalize()
            val inHull = hullInverse.mult(body.getPhysicsLocation(null).subtract(hullLocation), Vector3f())
            val fromAnchor = inHull.subtract(
                v(plan.frameInA.origin.x, plan.frameInA.origin.y, plan.frameInA.origin.z),
            )
            val travel = frame.inverse().toRotationMatrix().mult(fromAnchor, Vector3f()).y
            val offset = body.getPhysicsLocation(null).subtract(hullLocation)
            val hullAtWheel = hull.getLinearVelocity(null).add(hull.getAngularVelocity(null).cross(offset, Vector3f()))
            val rate = body.getLinearVelocity(null).subtract(hullAtWheel).dot(axis)
            val force = cn.solarmoon.spark_core.physics.PhysicsSuspensionRow.force(
                row, cn.solarmoon.spark_core.physics.SuspensionRowState(travel, rate),
            )
            if (!force.isFinite()) continue
            val push = axis.mult(force)
            hull.applyForce(push, offset)
            body.applyCentralForce(push.negate())
        }
    }

    /** A pin owned by a wheel on both sides has no segment-to-segment joint to measure. */
    private fun Rig.carriedPin(a: BeltChainSegment, b: BeltChainSegment): Boolean {
        val roads = side.wheels.filter { it.isSuspended }.map { it.index }.toSet()
        fun carried(wheel: Int) = wheel >= 0 && (pinRoadEnds || wheel !in roads)
        return carried(a.endWheel) && carried(b.startWheel)
    }

    private fun Rig.pinGaps(): Pair<Double, Double> {
        var worst = 0.0
        var sum = 0.0
        var count = 0
        for (index in chain.segments.indices) {
            val a = chain.segments[index]
            val b = chain.next(index)
            if (carriedPin(a, b)) continue
            val pa = segments[index].getPhysicsLocation(null).add(
                segments[index].getPhysicsRotation(null).toRotationMatrix().mult(v(0.0, 0.0, a.lengthM * 0.5), Vector3f()),
            )
            val pb = segments[b.index].getPhysicsLocation(null).add(
                segments[b.index].getPhysicsRotation(null).toRotationMatrix().mult(v(0.0, 0.0, -b.lengthM * 0.5), Vector3f()),
            )
            val gap = pa.distance(pb).toDouble()
            worst = max(worst, gap)
            sum += gap
            count++
        }
        return worst to (if (count == 0) 0.0 else sum / count)
    }

    /** Gap of every pin, with the kinds on both sides, for diagnosing which pin opens. */
    private fun Rig.pinReport(): String = chain.segments.indices.mapNotNull { index ->
        val a = chain.segments[index]
        val b = chain.next(index)
        if (carriedPin(a, b)) return@mapNotNull null
        val pa = segments[index].getPhysicsLocation(null).add(
            segments[index].getPhysicsRotation(null).toRotationMatrix().mult(v(0.0, 0.0, a.lengthM * 0.5), Vector3f()),
        )
        val pb = segments[b.index].getPhysicsLocation(null).add(
            segments[b.index].getPhysicsRotation(null).toRotationMatrix().mult(v(0.0, 0.0, -b.lengthM * 0.5), Vector3f()),
        )
        "${a.kind}#$index->${b.kind}: ${"%.1f".format(pa.distance(pb) * 1000)} mm"
    }.joinToString("; ")

    /**
     * World position of the lower belt surface at local along-offset [s].
     *
     * A bottom unit runs toward -Z, so its body is rotated half a turn about X
     * and local -Y is the *upper* surface. Pick whichever face is lower in world
     * space instead of trusting a local axis.
     */
    private fun Rig.lowerSurface(body: PhysicsRigidBody, s: Double): Vector3f {
        val half = side.belt.thicknessM * 0.5
        val matrix = body.getPhysicsRotation(null).toRotationMatrix()
        val location = body.getPhysicsLocation(null)
        val a = location.add(matrix.mult(v(0.0, half, s), Vector3f()))
        val b = location.add(matrix.mult(v(0.0, -half, s), Vector3f()))
        return if (a.y < b.y) a else b
    }

    /** Per bottom unit: centre height of the lower surface above the ground, mm. */
    private fun Rig.floatReport(ground: Ground): String = chain.segments.mapNotNull { segment ->
        if (segment.kind != BeltSegmentKind.BOTTOM) return@mapNotNull null
        val point = lowerSurface(segments[segment.index], 0.0)
        "#${segment.index}:${"%.0f".format((point.y - ground.height(point.z.toDouble())) * 1000)}"
    }.joinToString(" ")

    /** Lowest surface point of each bottom unit minus the ground height there; > 0 floats. */
    private fun Rig.groundFit(ground: Ground): Pair<Double, Double> {
        var floatMax = 0.0
        var sinkMax = 0.0
        for ((index, segment) in chain.segments.withIndex()) {
            if (segment.kind != BeltSegmentKind.BOTTOM) continue
            for (t in doubleArrayOf(-0.9, 0.0, 0.9)) {
                val point = lowerSurface(segments[index], segment.lengthM * 0.5 * t)
                val gap = point.y - ground.height(point.z.toDouble())
                floatMax = max(floatMax, gap)
                sinkMax = max(sinkMax, -gap)
            }
        }
        return floatMax to sinkMax
    }

    private fun Rig.finite(): Boolean = segments.all {
        val p = it.getPhysicsLocation(null)
        p.x.isFinite() && p.y.isFinite() && p.z.isFinite()
    }

    @Test
    fun flatGroundHoldsTheChainWithoutGapsOrJitter() {
        println("=== flat ground: pin gap, ground fit, jitter ===")
        for (top in intArrayOf(3, 1)) for (substeps in intArrayOf(1, 2, 4)) {
            val rig = build(BeltChainPolicy(topPieces = top), null, substeps)
            repeat(240) { rig.step() }
            var worstGap = 0.0
            val before = rig.segments.map { it.getPhysicsLocation(null).clone() }
            var maxStep = 0.0
            var previous = before
            repeat(120) {
                rig.step()
                val now = rig.segments.map { it.getPhysicsLocation(null).clone() }
                for (i in now.indices) maxStep = max(maxStep, now[i].distance(previous[i]).toDouble())
                previous = now
                worstGap = max(worstGap, rig.pinGaps().first)
            }
            val fit = rig.groundFit(Ground { 0.0 })
            val bottomGap = rig.chain.segments.indices.filter { rig.chain.segments[it].kind == BeltSegmentKind.BOTTOM }
                .maxOf { index ->
                    val a = rig.chain.segments[index]
                    val b = rig.chain.next(index)
                    if (b.kind != BeltSegmentKind.BOTTOM) 0.0 else {
                        val pa = rig.segments[index].getPhysicsLocation(null).add(
                            rig.segments[index].getPhysicsRotation(null).toRotationMatrix()
                                .mult(v(0.0, 0.0, a.lengthM * 0.5), Vector3f()),
                        )
                        val pb = rig.segments[b.index].getPhysicsLocation(null).add(
                            rig.segments[b.index].getPhysicsRotation(null).toRotationMatrix()
                                .mult(v(0.0, 0.0, -b.lengthM * 0.5), Vector3f()),
                        )
                        pa.distance(pb).toDouble()
                    }
                }
            println(
                "top=$top substeps=$substeps pinGap(worst)=${"%.2f".format(worstGap * 1000)} mm " +
                    "bottomPinGap=${"%.2f".format(bottomGap * 1000)} mm " +
                    "float=${"%.1f".format(fit.first * 1000)} mm sink=${"%.1f".format(fit.second * 1000)} mm " +
                    "maxStepMotion=${"%.3f".format(maxStep * 1000)} mm/step",
            )
            if (top == 1 && substeps == 1) {
                println("float per unit (mm): ${rig.floatReport(Ground { 0.0 })}")
            }
            assertTrue(rig.finite(), "the chain must stay finite at top=$top substeps=$substeps")
            assertTrue(fit.second < 0.2, "the chain must not fall through flat ground")
            rig.space.destroy()
        }
    }

    /**
     * Drive the rig onto a 1 m step through the planner's 3 m ramp. Road wheels
     * follow the ground with their own heave; the hull follows their mean.
     */
    @Test
    fun aRampFoldsTheChainOntoTheSlopeWithoutGaps() {
        println("=== 1:3 ramp: pin gap, ground fit, bend, jitter ===")
        val boxes = ArrayList<TerrainBox>()
        for (x in -3..4) for (z in -14..19) {
            boxes += TerrainBox(x.toDouble(), -1.0, z.toDouble(), x + 1.0, 0.0, z + 1.0)
        }
        for (x in -3..4) for (z in 8..19) {
            boxes += TerrainBox(x.toDouble(), 0.0, z.toDouble(), x + 1.0, 1.0, z + 1.0)
        }
        val plan = TrackedTerrainMeshPlanner.build(
            boxes,
            CompiledTerrain(true, 1.0, 3.0, 12, 3, 4),
            -3.0, -1.0, -14.0, 1L,
        )
        // The ramp rises from z = 5 to z = 8; the platform is 1 m high beyond it.
        val ground = Ground { z ->
            when {
                z < 5.0 -> 0.0
                z < 8.0 -> (z - 5.0) / 3.0
                else -> 1.0
            }
        }
        data class Variant(val label: String, val travel: Double, val cfm: Float)
        val variants = listOf(
            Variant("baseline", 0.0, 0f),
            Variant("soft pins cfm=1e-4", 0.0, 1.0e-4f),
            Variant("soft pins cfm=1e-3", 0.0, 1.0e-3f),
            Variant("end travel 0.08", 0.08, 0f),
            Variant("end travel 0.15", 0.15, 0f),
            Variant("end travel 0.08 + cfm=1e-4", 0.08, 1.0e-4f),
        )
        val tension = Tensioner(20_000.0, 8_000.0, 0.10)
        for (variant in variants) for (substeps in intArrayOf(1, 4)) {
            val label = variant.label
            val iterations = 0
            val top = 1
            val rig = build(BeltChainPolicy(topPieces = top), plan, substeps, false, iterations, tension, true, variant.travel, variant.cfm)
            val side = rig.side
            val radiusPlusBelt = { r: Double -> r + side.belt.thicknessM }
            val road = side.wheels.filter { it.isSuspended }
            val restY = road.associate { it.index to it.assemblyCenter.y + rig.restHeight }
            val idlerDrive = side.wheels.filter { !it.isSuspended }

            fun wheelHeight(wheel: CompiledWheel, z: Double): Double {
                val reach = radiusPlusBelt(wheel.radius)
                var best = Double.NEGATIVE_INFINITY
                var sample = z - reach
                while (sample <= z + reach) {
                    val dz = sample - z
                    val lift = sqrt(max(0.0, reach * reach - dz * dz))
                    best = max(best, ground.height(sample) + lift)
                    sample += 0.02
                }
                return best
            }

            // Start far enough back that the whole belt is on the flat floor.
            val startShift = -6.0
            val speed = 1.0
            var shift = startShift
            fun place(rigShift: Double) {
                // Road wheels sit on the ground. The hull takes the line fitted
                // through their lifts (heave and pitch); the idler and drive wheel
                // follow the hull rigidly, so the chain is never asked to change
                // length by an impossible wheel script.
                val samples = road.map { wheel ->
                    val z = wheel.assemblyCenter.z + rigShift
                    wheel.assemblyCenter.z to (wheelHeight(wheel, z) - restY.getValue(wheel.index))
                }
                val n = samples.size.toDouble()
                val meanZ = samples.sumOf { it.first } / n
                val meanLift = samples.sumOf { it.second } / n
                val slope = samples.sumOf { (it.first - meanZ) * (it.second - meanLift) } /
                    samples.sumOf { (it.first - meanZ) * (it.first - meanZ) }
                val pitch = kotlin.math.atan(slope)
                val heave = meanLift - slope * meanZ
                for (wheel in side.wheels) {
                    val body = rig.mounts[wheel.index] ?: rig.wheels.getValue(wheel.index)
                    val y: Double
                    val z: Double
                    if (wheel.isSuspended) {
                        val rotatedZ = wheel.assemblyCenter.z * kotlin.math.cos(pitch)
                        z = rotatedZ + rigShift
                        y = wheelHeight(wheel, z)
                    } else {
                        val ry = wheel.assemblyCenter.y
                        val rz = wheel.assemblyCenter.z
                        y = rig.restHeight + heave + ry * kotlin.math.cos(pitch) + rz * kotlin.math.sin(pitch)
                        z = rz * kotlin.math.cos(pitch) - ry * kotlin.math.sin(pitch) + rigShift
                    }
                    body.setPhysicsLocation(v(wheel.assemblyCenter.x, y, z))
                }
            }
            // Move chain and wheels to the start together so nothing is stretched.
            for ((index, body) in rig.segments.withIndex()) {
                val p = body.getPhysicsLocation(null)
                body.setPhysicsLocation(v(p.x.toDouble(), p.y.toDouble(), p.z + startShift))
                rig.segments[index].activate()
            }
            place(startShift)
            // A dynamic wheel rides on its scripted mount; move it with the mount
            // and clear its motion, or the first step drags it across the scene.
            for ((index, mount) in rig.mounts) {
                val wheel = rig.wheels.getValue(index)
                wheel.setPhysicsLocation(mount.getPhysicsLocation(null))
                wheel.setLinearVelocity(Vector3f())
                wheel.setAngularVelocity(Vector3f())
            }
            repeat(120) { rig.step() }
            rig.previousSlide = 0.0
            rig.peakSlide = 0.0
            rig.lowSlide = 0.0

            var worstGap = 0.0
            var worstFloat = 0.0
            var worstSink = 0.0
            var maxStep = 0.0
            var worstBend = 0.0
            var previous = rig.segments.map { it.getPhysicsLocation(null).clone() }
            var worstReport = ""
            val steps = ((8.0 - startShift + 4.0) / speed * 60.0).toInt()
            repeat(steps) {
                shift += speed / 60.0
                place(shift)
                rig.step()
                val now = rig.segments.map { it.getPhysicsLocation(null).clone() }
                for (i in now.indices) maxStep = max(maxStep, now[i].distance(previous[i]).toDouble())
                previous = now
                val gapNow = rig.pinGaps().first
                if (gapNow > worstGap) {
                    worstReport = "shift=${"%.2f".format(shift)} ${rig.pinReport()} | float/unit mm: ${rig.floatReport(ground)}"
                }
                worstGap = max(worstGap, gapNow)
                val fit = rig.groundFit(ground)
                worstFloat = max(worstFloat, fit.first)
                worstSink = max(worstSink, fit.second)
                for (index in rig.chain.segments.indices) {
                    if (rig.chain.segments[index].kind != BeltSegmentKind.BOTTOM) continue
                    val a = rig.segments[index].getPhysicsRotation(null)
                    val b = rig.segments[(index + 1) % rig.segments.size].getPhysicsRotation(null)
                    val relative = a.inverse().mult(b)
                    var bend = 2.0 * kotlin.math.atan2(relative.x.toDouble(), relative.w.toDouble())
                    if (bend > Math.PI) bend -= 2.0 * Math.PI
                    if (bend < -Math.PI) bend += 2.0 * Math.PI
                    worstBend = max(worstBend, abs(Math.toDegrees(bend)))
                }
            }
            println(
                "[$label] substeps=$substeps pinGap(worst)=${"%.2f".format(worstGap * 1000)} mm " +
                    "float(worst)=${"%.0f".format(worstFloat * 1000)} mm sink(worst)=${"%.0f".format(worstSink * 1000)} mm " +
                    "bottomBend(worst)=${"%.1f".format(worstBend)} deg maxStepMotion=${"%.1f".format(maxStep * 1000)} mm/step " +
                    "tensionerSlide=[${"%.0f".format(rig.lowSlide * 1000)}..${"%.0f".format(rig.peakSlide * 1000)}] mm",
            )
            if (substeps == 1) println("worst moment (tensioner=$label): $worstReport")
            assertTrue(rig.finite(), "the chain must stay finite on the ramp at $substeps substeps")
            rig.space.destroy()
        }
    }

    /**
     * The chain carries a sprung hull on dynamic wheels over the same ramp. The
     * hull is pushed forward at a fixed speed; heave, pitch and suspension
     * settle by themselves, so the chain is never asked to change length by a
     * script. Units are frictionless here because this standalone space has no
     * conveyor contact target; traction is covered by the GameTests.
     */
    @Test
    fun aSprungRigCarriesItsWeightOnTheChainUpTheRamp() {
        println("=== sprung rig on the 1:3 ramp ===")
        val boxes = ArrayList<TerrainBox>()
        for (x in -3..4) for (z in -14..19) boxes += TerrainBox(x.toDouble(), -1.0, z.toDouble(), x + 1.0, 0.0, z + 1.0)
        for (x in -3..4) for (z in 8..19) boxes += TerrainBox(x.toDouble(), 0.0, z.toDouble(), x + 1.0, 1.0, z + 1.0)
        val plan = TrackedTerrainMeshPlanner.build(boxes, CompiledTerrain(true, 1.0, 3.0, 12, 3, 4), -3.0, -1.0, -14.0, 1L)
        val ground = Ground { z -> when { z < 5.0 -> 0.0; z < 8.0 -> (z - 5.0) / 3.0; else -> 1.0 } }
        data class Case(
            val label: String,
            val substeps: Int,
            val road: Double? = null,
            val guide: Double? = null,
            val inertia: Float = 16f,
            val hull: Double = 1.0,
            val unitMass: Double = 1.0,
            val thick: Double? = null,
            val erp: Float = 0f,
        )
        // The 99A data now carries the 4x belt mass itself (RunMassBudgetKg 5688).
        val cases = listOf(
            Case("data mass + 0.3 m", 1, thick = 0.3),
            Case("data mass + 0.3 m", 2, thick = 0.3),
            Case("data mass + 0.3 m", 3, thick = 0.3),
        )
        for (case in cases) {
            val substeps = case.substeps
            val iterations = 0
            val rig = build(
                BeltChainPolicy(topPieces = 1), plan, substeps, iterations = iterations, dynamic = true, unitFriction = 0f,
                roadWheelKg = case.road, guideWheelKg = case.guide, unitInertiaScale = case.inertia,
                hullMassScale = case.hull, unitMassScale = case.unitMass,
                bottomCollisionThickness = case.thick, pinErp = case.erp,
            )
            val hull = rig.hull!!
            // Start the whole assembly 6 m back, on the flat floor.
            val shift = Vector3f(0f, 0f, -6f)
            for (body in rig.segments + rig.wheels.values + hull) body.setPhysicsLocation(body.getPhysicsLocation(null).add(shift))
            repeat(180) { rig.step() }
            val settleY = hull.getPhysicsLocation(null).y
            var worstGap = 0.0
            var worstFloat = 0.0
            var worstSink = 0.0
            var worstBend = 0.0
            var peakPitch = 0.0
            var worstReport = ""
            val speed = 1.0f
            val steps = (18.0 / speed * 60.0).toInt()
            repeat(steps) {
                val velocity = hull.getLinearVelocity(null)
                hull.setLinearVelocity(Vector3f(0f, velocity.y, speed))
                rig.step()
                val gap = rig.pinGaps().first
                if (gap > worstGap) worstReport = "z=${"%.2f".format(hull.getPhysicsLocation(null).z)} ${rig.pinReport()}"
                worstGap = max(worstGap, gap)
                val fit = rig.groundFit(ground)
                worstFloat = max(worstFloat, fit.first)
                worstSink = max(worstSink, fit.second)
                val q = hull.getPhysicsRotation(null)
                peakPitch = max(peakPitch, abs(Math.toDegrees(2.0 * kotlin.math.atan2(q.x.toDouble(), q.w.toDouble()))))
                for (index in rig.chain.segments.indices) {
                    if (rig.chain.segments[index].kind != BeltSegmentKind.BOTTOM) continue
                    val a = rig.segments[index].getPhysicsRotation(null)
                    val b = rig.segments[(index + 1) % rig.segments.size].getPhysicsRotation(null)
                    val relative = a.inverse().mult(b)
                    var bend = 2.0 * kotlin.math.atan2(relative.x.toDouble(), relative.w.toDouble())
                    if (bend > Math.PI) bend -= 2.0 * Math.PI
                    if (bend < -Math.PI) bend += 2.0 * Math.PI
                    worstBend = max(worstBend, abs(Math.toDegrees(bend)))
                }
            }
            val end = hull.getPhysicsLocation(null)
            println(
                "substeps=$substeps [${case.label}] settleDrop=${"%.0f".format((rig.restHeight - settleY) * 1000)} mm " +
                    "climb=${"%.2f".format(end.y - settleY)} m endZ=${"%.1f".format(end.z)} " +
                    "pinGap(worst)=${"%.2f".format(worstGap * 1000)} mm float(worst)=${"%.0f".format(worstFloat * 1000)} mm " +
                    "sink(worst)=${"%.0f".format(worstSink * 1000)} mm bottomBend(worst)=${"%.1f".format(worstBend)} deg " +
                    "peakPitch=${"%.1f".format(peakPitch)} deg",
            )
            
            assertTrue(rig.finite(), "the sprung chain must stay finite at $substeps substeps")
            rig.space.destroy()
        }
    }
}
