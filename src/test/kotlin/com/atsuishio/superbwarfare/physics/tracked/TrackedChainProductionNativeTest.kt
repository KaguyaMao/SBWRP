package com.atsuishio.superbwarfare.physics.tracked

import cn.solarmoon.spark_core.physics.NativePhysicsLoader
import cn.solarmoon.spark_core.physics.PhysicsHost
import cn.solarmoon.spark_core.physics.PhysicsSuspensionRow
import cn.solarmoon.spark_core.physics.SuspensionRowState
import cn.solarmoon.spark_core.physics.body.CollisionGroups
import cn.solarmoon.spark_core.physics.body.collideWithOwnerGroups
import cn.solarmoon.spark_core.physics.body.owner
import cn.solarmoon.spark_core.physics.track.TrackRunAssembly
import com.jme3.bullet.PhysicsSpace
import com.jme3.bullet.collision.PhysicsCollisionObject
import com.jme3.bullet.collision.shapes.BoxCollisionShape
import com.jme3.bullet.collision.shapes.CylinderCollisionShape
import com.jme3.bullet.collision.shapes.MeshCollisionShape
import com.jme3.bullet.collision.shapes.infos.IndexedMesh
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.math.Quaternion
import com.jme3.math.Vector3f
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

/**
 * The production anchor chain, assembled exactly as the runtime does it.
 *
 * Links and hinges come from [TrackedRunSpecs.compilePhysicalRuns] through the
 * core [TrackRunAssembly]; joints to wheels come from
 * [CompiledPhysicalRun.externals] through [TrackedJointFactory]; wheels sit on
 * the compiled mounts with production suspension rows. Only the Minecraft level
 * and the conveyor contact target are missing, so the belt is frictionless and
 * the hull is pushed forward. T0 structure checks come first.
 */
class TrackedChainProductionNativeTest {

    init {
        NativePhysicsLoader.load()
    }

    private val spec: TrackedVehicleSpec = Json { ignoreUnknownKeys = true; isLenient = true }.let { json ->
        TrackedVehicleCompiler.compile(
            "superbwarfare:ztz_99a",
            json.decodeFromString(
                TrackedVehicleDefinition.serializer(),
                File("src/main/resources/data/superbwarfare/sbw/tracked/ztz_99a.json").readText(),
            ).apply {
                // Superseded by the envelope belt; kept compilable for comparison.
                track.topology = TrackTopology.ANCHOR_CHAIN
            },
            File("src/main/resources/assets/superbwarfare/models/bedrock/vehicle/ztz_99a.geo.json").readText(),
        ).requireSpec()
    }

    @Test
    fun theProductionBeltIsTheAnchorChain() {
        assertEquals(2, spec.solverSubsteps)
        for (side in TrackedSide.entries) {
            val compiled = spec.side(side)
            val chain = compiled.chain
            assertTrue(chain != null, "$side compiles the anchor chain")
            val runs = compiled.physicalRuns
            assertEquals(6, runs.size, "$side six core runs: front up/low, bottom, rear low/up, top")
            val bottom = runs.single { it.links.size > 1 }
            assertEquals(13, bottom.links.size)
            assertEquals(12, bottom.hinges.size)
            assertEquals(18, runs.sumOf { it.links.size }, "2 front + 13 bottom + 2 rear + 1 top")
            // Six anchors, each pinned from both neighbours, and four middle road wheels.
            assertEquals(12, runs.sumOf { run -> run.externals.count { it.plan.name.endsWith("_pin") } })
            assertEquals(4, runs.sumOf { run -> run.externals.count { it.plan.name.endsWith("_normal") } })
            assertEquals(compiled.runMassTotalKg, runs.sumOf { it.massKg }, 1.0e-6, "$side belt mass budget")
            // Thickened bottom units keep their ground face on the belt surface.
            val belt = compiled.belt.thicknessM
            for (link in bottom.links) assertEquals(0.15, link.halfExtents.y, 1.0e-9)
            val lowestFace = bottom.links.minOf { it.center.y - it.halfExtents.y }
            val anchorLine = compiled.anchors.minOf { it.bindPoint.y }
            assertEquals(anchorLine - belt * 0.5, lowestFace, 1.0e-6, "$side ground face unchanged by thickening")
        }
        assertTrue(TrackedHullAudit.audit(spec.hullBoxes, spec.sides.values).isEmpty(), "hull clears the chain")
    }

    @Test
    fun theProductionChainCarriesTheSprungHullUpTheRamp() {
        val boxes = ArrayList<TerrainBox>()
        for (x in -3..4) for (z in -14..19) boxes += TerrainBox(x.toDouble(), -1.0, z.toDouble(), x + 1.0, 0.0, z + 1.0)
        for (x in -3..4) for (z in 8..19) boxes += TerrainBox(x.toDouble(), 0.0, z.toDouble(), x + 1.0, 1.0, z + 1.0)
        val plan = TrackedTerrainMeshPlanner.build(boxes, CompiledTerrain(true, 1.0, 3.0, 12, 3, 4), -3.0, -1.0, -14.0, 1L)

        val side = spec.side(TrackedSide.LEFT)
        val substeps = spec.solverSubsteps
        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
        space.setGravity(Vector3f(0f, -9.81f, 0f))
        val host = object : PhysicsHost {
            override val physicsLevel: cn.solarmoon.spark_core.physics.level.PhysicsLevel
                get() = throw UnsupportedOperationException("native test has no PhysicsLevel")
            override val allPhysicsBodies: MutableMap<String, PhysicsCollisionObject> = ConcurrentHashMap()
        }
        val terrain = PhysicsRigidBody(
            MeshCollisionShape(
                true,
                IndexedMesh(
                    Array(plan.vertices.size / 3) { Vector3f(plan.vertices[it * 3], plan.vertices[it * 3 + 1], plan.vertices[it * 3 + 2]) },
                    plan.indices,
                ),
            ).apply { margin = .001f },
            0f,
        )
        terrain.setPhysicsLocation(Vector3f(plan.originX.toFloat(), plan.originY.toFloat(), plan.originZ.toFloat()))
        terrain.owner = host
        terrain.collisionGroup = TrackedTerrainCollision.TERRAIN_MESH
        terrain.setCollideWithGroups(CollisionGroups.NONE)
        terrain.collideWithOwnerGroups = CollisionGroups.TRACK_LINK
        space.addCollisionObject(terrain)

        // Chassis origin height that puts the belt surface on y = 0, then 6 m back.
        val rest = side.belt.thicknessM * 0.5 - side.anchors.minOf { it.bindPoint.y }
        val origin = Vector3f(0f, rest.toFloat(), -6f)

        val hullMass = spec.sprungAssemblyMassKg / 2.0
        val hull = PhysicsRigidBody(BoxCollisionShape(0.5f), hullMass.toFloat())
        hull.collisionGroup = CollisionGroups.CHASSIS
        hull.setCollideWithGroups(CollisionGroups.NONE)
        hull.setPhysicsLocation(origin)
        hull.setLinearFactor(Vector3f(0f, 1f, 1f))
        hull.setAngularFactor(Vector3f(1f, 0f, 0f))
        hull.setInverseInertiaLocal(Vector3f((12.0 / (hullMass * (49.0 + 2.25))).toFloat(), 0f, 0f))
        hull.setSleepingThresholds(0f, 0f)
        hull.owner = host
        space.addCollisionObject(hull)

        val wheels = side.wheels.associate { wheel ->
            val body = PhysicsRigidBody(
                CylinderCollisionShape(Vector3f(wheel.halfWidth.toFloat(), wheel.radius.toFloat(), wheel.radius.toFloat()), 0),
                wheel.massKg.toFloat(),
            )
            body.collisionGroup = CollisionGroups.WHEEL
            body.setCollideWithGroups(CollisionGroups.NONE)
            body.setPhysicsLocation(origin.add(wheel.assemblyCenter.x.toFloat(), wheel.assemblyCenter.y.toFloat(), wheel.assemblyCenter.z.toFloat()))
            body.setSleepingThresholds(0f, 0f)
            body.owner = host
            space.addCollisionObject(body)
            wheel.index to body
        }
        for (mount in side.mounts) {
            val joint = mount.joint ?: continue
            space.addJoint(TrackedJointFactory.create(joint, hull, wheels.getValue(mount.wheelIndex)))
        }

        val specs = TrackedRunSpecs.compilePhysicalRuns(side, spec.vehicleId, friction = 0.0)
        val links = ArrayList<PhysicsRigidBody>()
        for ((runIndex, run) in side.physicalRuns.withIndex()) {
            val runSpec = specs[runIndex]
            val bodies = runSpec.links.indices.map { index ->
                TrackRunAssembly.createBody(runSpec, index, host, TrackRunAssembly.bodyName(runSpec, index)).also { body ->
                    body.setPhysicsLocation(body.getPhysicsLocation(null).add(origin))
                    val inverse = body.getInverseInertiaLocal(null)
                    body.setInverseInertiaLocal(inverse.mult((1.0 / run.inertiaScale).toFloat()))
                    body.setSleepingThresholds(0f, 0f)
                    // A plain PhysicsSpace has no Spark owner filter. In production
                    // PhysicsWorld.needsCollision drops same-owner wheel/hull pairs;
                    // here the mask is narrowed to terrain to reproduce that.
                    body.setCollideWithGroups(TrackedTerrainCollision.TERRAIN_MESH)
                    space.addCollisionObject(body)
                }
            }
            TrackRunAssembly.createJoints(runSpec, bodies).forEach { space.addJoint(it) }
            for (external in run.externals) {
                val a = bodies[external.link]
                val b = wheels.getValue(external.plan.bodyB.ordinal)
                fun world(body: PhysicsRigidBody, local: net.minecraft.world.phys.Vec3) = body.getPhysicsLocation(null).add(
                    body.getPhysicsRotation(null).toRotationMatrix().mult(Vector3f(local.x.toFloat(), local.y.toFloat(), local.z.toFloat()), Vector3f()),
                )
                val gap = world(a, external.plan.frameInA.origin).distance(world(b, external.plan.frameInB.origin))
                assertTrue(gap < 1.0e-3f, "${external.plan.name} frames must coincide at the build pose, gap ${gap * 1000} mm")
                space.addJoint(TrackedJointFactory.create(external.plan, a, b))
            }
            links += bodies
        }

        val rows = side.mounts.mapNotNull { mount -> mount.springRow?.let { mount to it } }
        fun suspension() {
            val hullMatrix = hull.getPhysicsRotation(null).toRotationMatrix()
            val hullInverse = hull.getPhysicsRotation(null).inverse().toRotationMatrix()
            val hullLocation = hull.getPhysicsLocation(null)
            for ((mount, row) in rows) {
                val joint = mount.joint ?: continue
                val body = wheels.getValue(mount.wheelIndex)
                val frame = Quaternion(joint.frameInA.rotation.x, joint.frameInA.rotation.y, joint.frameInA.rotation.z, joint.frameInA.rotation.w).normalizeLocal()
                val axis = hullMatrix.mult(frame.toRotationMatrix().mult(Vector3f(0f, 1f, 0f), Vector3f()), Vector3f()).normalize()
                val fromAnchor = hullInverse.mult(body.getPhysicsLocation(null).subtract(hullLocation), Vector3f())
                    .subtract(joint.frameInA.origin.x.toFloat(), joint.frameInA.origin.y.toFloat(), joint.frameInA.origin.z.toFloat())
                val travel = frame.inverse().toRotationMatrix().mult(fromAnchor, Vector3f()).y
                val offset = body.getPhysicsLocation(null).subtract(hullLocation)
                val hullAtWheel = hull.getLinearVelocity(null).add(hull.getAngularVelocity(null).cross(offset, Vector3f()))
                val rate = body.getLinearVelocity(null).subtract(hullAtWheel).dot(axis)
                val force = PhysicsSuspensionRow.force(row, SuspensionRowState(travel, rate))
                if (!force.isFinite()) continue
                hull.applyForce(axis.mult(force), offset)
                body.applyCentralForce(axis.mult(-force))
            }
        }
        fun tick() = repeat(substeps) {
            suspension()
            space.update(1f / 60f / substeps, 0, false, true, false)
        }

        repeat(180) { tick() }
        val settleY = hull.getPhysicsLocation(null).y
        var peakSink = 0.0
        repeat(18 * 60) {
            hull.setLinearVelocity(Vector3f(0f, hull.getLinearVelocity(null).y, 1f))
            tick()
            for (body in links) {
                val p = body.getPhysicsLocation(null)
                val ground = when {
                    p.z < 5f -> 0.0
                    p.z < 8f -> (p.z - 5.0) / 3.0
                    else -> 1.0
                }
                // Body centre below the ground means the unit passed through it.
                peakSink = max(peakSink, ground - p.y)
            }
        }
        val end = hull.getPhysicsLocation(null)
        val climb = end.y - settleY
        println("production chain: settle=${"%.0f".format((rest - settleY) * 1000)} mm climb=${"%.2f".format(climb)} m peakCentreSink=${"%.0f".format(peakSink * 1000)} mm")
        assertTrue(links.all { val p = it.getPhysicsLocation(null); p.x.isFinite() && p.y.isFinite() && p.z.isFinite() })
        assertTrue(abs(rest - settleY) < 0.1, "the hull must settle near its bind height, moved ${(rest - settleY) * 1000} mm")
        assertTrue(climb > 0.9, "the production chain must carry the hull onto the 1 m step, climbed $climb m")
        assertTrue(peakSink < 0.05, "no belt body centre may pass below the ground, worst ${peakSink * 1000} mm")
        space.destroy()
    }
}
