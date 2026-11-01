package com.atsuishio.superbwarfare.physics.tracked

import cn.solarmoon.spark_core.physics.NativePhysicsLoader
import com.jme3.bullet.PhysicsSpace
import com.jme3.bullet.SolverMode
import com.jme3.bullet.collision.ContactPointFlag
import com.jme3.bullet.collision.ManifoldPoints
import com.jme3.bullet.collision.PhysicsCollisionObject
import com.jme3.bullet.collision.shapes.BoxCollisionShape
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.math.Vector3f
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Verifies that a callback-written second friction target reaches Bullet's solver. */
class TrackedContactFrictionNativeTest {

    init {
        NativePhysicsLoader.load()
    }

    @Test
    fun twoFrictionDirectionsResolveTheConfiguredLateralTarget() {
        val motion = run(useTwoDirections = true)
        val single = run(useTwoDirections = false)
        println("contact friction two-direction vx=${motion.first} configured=${motion.second}")
        println("contact friction single-direction vx=${single.first} configured=${single.second}")
        assertTrue(motion.second > 0, "the contact target must be written in the second tangent")
        assertTrue(motion.first > 0.2f, "Bullet's second friction row must apply its target; vx=${motion.first}")
        assertTrue(single.first < motion.first * 0.2f, "without the second row the lateral target is ignored; vx=${single.first}")
    }

    private fun run(useTwoDirections: Boolean): Pair<Float, Int> {
        val space = ContactSpace()
        try {
            space.setGravity(Vector3f(0f, -9.81f, 0f))
            val mode = SolverMode.SIMD or SolverMode.WarmStart or SolverMode.CacheFriendly or
                SolverMode.CacheDirection or SolverMode.NoVelocityDependent or
                if (useTwoDirections) SolverMode.Use2Directions else 0
            space.solverInfo.setMode(mode)
            val floor = PhysicsRigidBody(BoxCollisionShape(Vector3f(10f, 0.5f, 10f)), 0f)
            floor.setPhysicsLocation(Vector3f(0f, -0.5f, 0f))
            floor.collisionGroup = PhysicsCollisionObject.COLLISION_GROUP_01
            floor.friction = 1f
            val plate = PhysicsRigidBody(BoxCollisionShape(Vector3f(0.5f, 0.1f, 0.5f)), 20f)
            plate.setPhysicsLocation(Vector3f(0f, 0.105f, 0f))
            plate.collisionGroup = PhysicsCollisionObject.COLLISION_GROUP_02
            plate.friction = 1f
            plate.setSleepingThresholds(0f, 0f)
            space.addCollisionObject(floor)
            space.addCollisionObject(plate)
            repeat(30) { space.update(1f / 60f, 0, false, true, false) }
            space.motion = 0.5f
            repeat(60) { space.update(1f / 60f, 0, false, true, false) }
            return plate.getLinearVelocity(null).x to space.configuredDirections
        } finally {
            space.destroy()
        }
    }

    private class ContactSpace : PhysicsSpace(BroadphaseType.DBVT) {
        var motion = 0f
        var configuredDirections = 0

        override fun onContactConceived(
            manifoldPointId: Long,
            manifoldId: Long,
            bodyA: PhysicsCollisionObject,
            bodyB: PhysicsCollisionObject,
        ): Boolean {
            configure(manifoldPointId, bodyA, bodyB)
            return true
        }

        override fun onContactProcessed(
            bodyA: PhysicsCollisionObject,
            bodyB: PhysicsCollisionObject,
            manifoldPointId: Long,
        ) {
            configure(manifoldPointId, bodyA, bodyB)
        }

        private fun configure(point: Long, bodyA: PhysicsCollisionObject, bodyB: PhysicsCollisionObject) {
            val isFloorPlatePair = (bodyA.collisionGroup == PhysicsCollisionObject.COLLISION_GROUP_01 &&
                bodyB.collisionGroup == PhysicsCollisionObject.COLLISION_GROUP_02) ||
                (bodyB.collisionGroup == PhysicsCollisionObject.COLLISION_GROUP_01 &&
                    bodyA.collisionGroup == PhysicsCollisionObject.COLLISION_GROUP_02)
            if (isFloorPlatePair) {
                ManifoldPoints.setCombinedFriction(point, 1f)
                ManifoldPoints.setLateralFrictionDir1(point, Vector3f(0f, 0f, 1f))
                ManifoldPoints.setLateralFrictionDir2(point, Vector3f(1f, 0f, 0f))
                ManifoldPoints.setContactMotion1(point, 0f)
                val sign = if (bodyA.collisionGroup == PhysicsCollisionObject.COLLISION_GROUP_02) 1f else -1f
                ManifoldPoints.setContactMotion2(point, motion * sign)
                ManifoldPoints.setFlags(
                    point,
                    ManifoldPoints.getFlags(point) or ContactPointFlag.LATERAL_FRICTION,
                )
                configuredDirections++
            }
        }
    }
}
