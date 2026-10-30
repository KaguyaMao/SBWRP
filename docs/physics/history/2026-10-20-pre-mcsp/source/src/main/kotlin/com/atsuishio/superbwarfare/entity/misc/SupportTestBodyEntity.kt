package com.atsuishio.superbwarfare.entity.misc

import cn.solarmoon.spark_core.physics.body.CollisionGroups
import cn.solarmoon.spark_core.physics.body.RigidBodyEntity
import cn.solarmoon.spark_core.physics.body.name as bodyName
import com.atsuishio.superbwarfare.init.ModEntities
import com.jme3.bullet.collision.shapes.BoxCollisionShape
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.math.Vector3f
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.Level
import net.minecraftforge.network.NetworkHooks

/**
 * Debug-only rigid cube that collides with the height-field support pair only.
 * It never rests on raw block tops, so a missing support surface makes it fall.
 * Not an acceptance fixture and not a vehicle part.
 *
 * The native body is created on the physics thread, not in the entity
 * constructor. `body` must not be read until `bodyReady` flips true.
 */
class SupportTestBodyEntity(
    type: EntityType<out SupportTestBodyEntity>,
    level: Level,
) : RigidBodyEntity(type, level) {
    companion object {
        const val HALF = 0.4f
        const val MASS = 40f
        const val FRICTION = 0.7f
        const val CCD_MOTION_THRESHOLD = 0.02f
        const val CCD_SWEEP_RADIUS = 0.3f

        fun create(level: Level): SupportTestBodyEntity =
            SupportTestBodyEntity(ModEntities.SUPPORT_TEST_BODY.get(), level)
    }

    private lateinit var bodyRef: PhysicsRigidBody

    override val body: PhysicsRigidBody
        get() {
            check(bodyReady) { "support test body was read before the physics thread created it" }
            return bodyRef
        }

    init {
        bodyReady = false
        noCulling = true
    }

    override fun defineSynchedData() {
        super.defineSynchedData()
        entityData.set(DATA_GRAVITY, org.joml.Vector3f(0f, 0f, 0f))
        entityData.set(RigidBodyEntity.DATA_MASS, MASS)
        entityData.set(DATA_FRICTION, FRICTION)
        entityData.set(DATA_CCD_MOTION_THRESHOLD, CCD_MOTION_THRESHOLD)
        entityData.set(DATA_CCD_SWEEP_RADIUS, CCD_SWEEP_RADIUS)
        entityData.set(DATA_COLLISION_GROUP, CollisionGroups.PHYSICS_BODY)
        entityData.set(DATA_COLLISION_WITH_GROUPS, CollisionGroups.SUPPORT_PAIR)
        entityData.set(DATA_RESTITUTION, 0f)
        entityData.set(DATA_CONTACT_RESPONSE, true)
    }

    override fun createDeferredBody() {
        bodyRef = PhysicsRigidBody(
            BoxCollisionShape(Vector3f(HALF, HALF, HALF)).apply { margin = 0.001f },
            MASS,
        ).apply {
            this.bodyName = "support_test_body"
            friction = FRICTION
            restitution = 0f
            ccdMotionThreshold = CCD_MOTION_THRESHOLD
            ccdSweptSphereRadius = CCD_SWEEP_RADIUS
            collisionGroup = CollisionGroups.PHYSICS_BODY
            setCollideWithGroups(CollisionGroups.SUPPORT_PAIR)
        }
    }

    override fun getAddEntityPacket(): Packet<ClientGamePacketListener> = NetworkHooks.getEntitySpawningPacket(this)

    override fun isPickable(): Boolean = !isRemoved

    override fun tick() {
        super.tick()
        if (!level().isClientSide && tickCount > 20 * 60 * 5) discard()
    }
}
