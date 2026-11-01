package com.atsuishio.superbwarfare.physics.tracked

import cn.solarmoon.spark_core.physics.NativePhysicsLoader
import com.jme3.bullet.PhysicsSpace
import com.jme3.bullet.collision.shapes.BoxCollisionShape
import com.jme3.bullet.collision.shapes.CompoundCollisionShape
import com.jme3.bullet.collision.shapes.CylinderCollisionShape
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.math.Vector3f
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * One belt-like body on flat ground under a small constant push: does ground
 * friction hold it? Isolates the contact from every joint of the belt.
 */
class TrackedBeltContactNativeTest {

    init {
        NativePhysicsLoader.load()
    }

    private fun drift(
        label: String,
        bosses: Boolean,
        margin: Float,
        ccd: Boolean,
        pushN: Float,
        extraDownN: Float = 0f,
        length: Float = 6.0f,
        substeps: Int = 2,
        linearDamping: Float = 0.04f,
    ): Double {
        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
        space.setGravity(Vector3f(0f, -9.81f, 0f))
        val ground = PhysicsRigidBody(BoxCollisionShape(Vector3f(20f, 1f, 40f)), 0f)
        ground.setPhysicsLocation(Vector3f(0f, -1f, 0f))
        ground.friction = 0.5f
        space.addCollisionObject(ground)

        val halfThick = 0.049f
        val halfWidth = 0.39f
        val halfLength = length * 0.5f
        val shape = CompoundCollisionShape(3)
        shape.addChildShape(BoxCollisionShape(Vector3f(halfWidth, halfThick, halfLength)).apply { this.margin = margin }, Vector3f.ZERO)
        if (bosses) {
            val boss = CylinderCollisionShape(Vector3f(halfWidth, halfThick, halfThick), PhysicsSpace.AXIS_X).apply { this.margin = margin }
            shape.addChildShape(boss, Vector3f(0f, 0f, -halfLength))
            shape.addChildShape(boss, Vector3f(0f, 0f, halfLength))
        }
        val mass = 2100f
        val body = PhysicsRigidBody(shape, mass)
        val t2 = (2f * halfThick) * (2f * halfThick)
        val w2 = (2f * halfWidth) * (2f * halfWidth)
        val l2 = (2f * halfLength) * (2f * halfLength)
        body.setInverseInertiaLocal(Vector3f(12f / (mass * (t2 + l2)), 12f / (mass * (w2 + l2)), 12f / (mass * (w2 + t2))))
        body.friction = 0.8f
        body.restitution = 0f
        body.setDamping(linearDamping, 0.02f)
        body.setSleepingThresholds(0f, 0f)
        if (ccd) {
            body.ccdMotionThreshold = halfThick
            body.ccdSweptSphereRadius = halfThick * 0.8f
        }
        body.setPhysicsLocation(Vector3f(0f, halfThick + 0.001f, 0f))
        space.addCollisionObject(body)

        fun step() = repeat(substeps) {
            body.applyCentralForce(Vector3f(0f, -extraDownN, pushN))
            space.update(1f / 60f / substeps, 0, false, true, false)
        }
        repeat(180) { step() }
        val start = body.getPhysicsLocation(null).z
        repeat(600) { step() }
        val moved = (body.getPhysicsLocation(null).z - start) * 1000.0
        println("contact [$label] push=${pushN} N down=${extraDownN} N moved=${"%.2f".format(moved)} mm in 10 s, vz=${"%.2f".format(body.getLinearVelocity(null).z * 1000)} mm/s y=${"%.4f".format(body.getPhysicsLocation(null).y)}")
        space.destroy()
        return moved
    }

    @Test
    fun aLoadedBeltBodyIsHeldByFriction() {
        for (push in listOf(0f, 400f, 5000f)) {
            val moved = listOf(
                drift("plain box, margin 0.02", bosses = false, margin = 0.02f, ccd = false, pushN = push, extraDownN = 150_000f),
                drift("box + bosses, margin 0.02", bosses = true, margin = 0.02f, ccd = false, pushN = push, extraDownN = 150_000f),
                drift("box + bosses + ccd", bosses = true, margin = 0.02f, ccd = true, pushN = push, extraDownN = 150_000f),
                drift("box + bosses, margin 0.001", bosses = true, margin = 0.001f, ccd = false, pushN = push, extraDownN = 150_000f),
            )
            for (value in moved) assertTrue(kotlin.math.abs(value) < 5.0, "a loaded belt body must stay put under ${push} N, moved $value mm")
        }
    }

    /**
     * Does ground friction stop a sliding belt body when a heavy hull rides on it
     * through a joint, as in the vehicle? Initial speed 35 mm/s, no push.
     */
    @Test
    fun aSlidingBeltBodyCarryingAHullIsStopped() {
        for ((label, hullMassKg, hullJoint) in listOf(
            Triple("lone belt body", 0f, "none"),
            Triple("hull 15 t, locked on top", 15_000f, "lock"),
            Triple("hull 15 t, 3 DOF pinned at one point", 15_000f, "pin"),
            Triple("hull 15 t, normal only", 15_000f, "normal"),
        )) {
            for (iterations in listOf(10, 40)) {
                val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
                space.setGravity(Vector3f(0f, -9.81f, 0f))
                val ground = PhysicsRigidBody(BoxCollisionShape(Vector3f(20f, 1f, 40f)), 0f)
                ground.setPhysicsLocation(Vector3f(0f, -1f, 0f))
                ground.friction = 0.5f
                space.addCollisionObject(ground)
                val halfThick = 0.049f
                val shape = BoxCollisionShape(Vector3f(0.39f, halfThick, 3f)).apply { margin = 0.02f }
                val belt = PhysicsRigidBody(shape, 2100f)
                belt.friction = 0.8f
                belt.setSleepingThresholds(0f, 0f)
                belt.setPhysicsLocation(Vector3f(0f, halfThick + 0.001f, 0f))
                space.addCollisionObject(belt)
                var hull: PhysicsRigidBody? = null
                if (hullMassKg > 0f) {
                    val body = PhysicsRigidBody(BoxCollisionShape(Vector3f(0.5f, 0.3f, 2f)), hullMassKg)
                    body.collisionGroup = com.jme3.bullet.collision.PhysicsCollisionObject.COLLISION_GROUP_02
                    body.setCollideWithGroups(com.jme3.bullet.collision.PhysicsCollisionObject.COLLISION_GROUP_NONE)
                    body.setSleepingThresholds(0f, 0f)
                    body.setPhysicsLocation(Vector3f(0f, 1.2f, 0f))
                    space.addCollisionObject(body)
                    val joint = com.jme3.bullet.joints.New6Dof(
                        belt, body, Vector3f(0f, 0f, 0f), Vector3f(0f, -1.2f + halfThick + 0.001f, 0f),
                        com.jme3.math.Matrix3f.IDENTITY, com.jme3.math.Matrix3f.IDENTITY, com.jme3.bullet.RotationOrder.XYZ,
                    )
                    val lock = { axis: Int -> cn.solarmoon.spark_core.physics.body.PhysicsJoints.lock(joint, axis) }
                    val free = { axis: Int -> cn.solarmoon.spark_core.physics.body.PhysicsJoints.free(joint, axis) }
                    when (hullJoint) {
                        "lock" -> { for (a in 0..5) lock(a) }
                        "pin" -> { for (a in 0..2) lock(a); for (a in 3..5) free(a) }
                        else -> { free(0); lock(1); free(2); for (a in 3..5) free(a) }
                    }
                    if (iterations > 10) joint.overrideIterations(iterations)
                    space.addJoint(joint)
                    hull = body
                }
                repeat(120) { repeat(2) { space.update(1f / 120f, 0, false, true, false) } }
                val bodies = listOfNotNull<PhysicsRigidBody>(belt, hull)
                for (b in bodies) b.setLinearVelocity(Vector3f(0f, 0f, 0.035f))
                val trace = StringBuilder()
                repeat(60) { tick ->
                    repeat(2) { space.update(1f / 120f, 0, false, true, false) }
                    if (tick % 10 == 0) trace.append(" ${"%.1f".format(belt.getLinearVelocity(null).z * 1000)}")
                }
                println("slide [$label, iterations $iterations] belt vz over 1 s (mm/s):$trace   final belt=${"%.2f".format(belt.getLinearVelocity(null).z * 1000)} hull=${"%.2f".format((hull?.getLinearVelocity(null)?.z ?: 0f) * 1000)}")
                // The belt body itself must be stopped by the ground whatever rides on it. (A hull that is
                // only pinned at one point swings on it like a pendulum; that is the hull, not the belt.)
                assertTrue(kotlin.math.abs(belt.getLinearVelocity(null).z) < 0.005f, "[$label, iterations $iterations] a sliding belt body must be stopped by friction, ${belt.getLinearVelocity(null).z * 1000} mm/s")
                space.destroy()
            }
        }
    }
}
