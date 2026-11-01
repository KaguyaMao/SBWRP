package com.atsuishio.superbwarfare.physics.tracked

import cn.solarmoon.spark_core.physics.NativePhysicsLoader
import cn.solarmoon.spark_core.physics.body.CollisionGroups
import com.jme3.bullet.PhysicsSpace
import com.jme3.bullet.collision.shapes.BoxCollisionShape
import com.jme3.bullet.collision.shapes.MeshCollisionShape
import com.jme3.bullet.collision.shapes.infos.IndexedMesh
import com.jme3.bullet.joints.New6Dof
import com.jme3.bullet.objects.PhysicsRigidBody
import com.jme3.math.Quaternion
import com.jme3.math.Vector3f
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * T1: the production envelope belt on a sprung planar rig, one side of the 99A.
 *
 * Wheels, mounts, suspension rows and the guide-wheel wrap shapes come from the
 * compiled spec; the belt is the production [TrackedDynamicBelt]; the suspension
 * springs are configured by the same [TrackedSuspensionSprings] the vehicle runtime
 * uses. Only the Minecraft level and the conveyor contact target are missing, so a
 * driven run is pushed with a traction-limited force.
 */
class TrackedEnvelopeBeltNativeTest {

    init {
        NativePhysicsLoader.load()
        // The Spark physics level uses a 3 s deadline.
        com.jme3.bullet.objects.PhysicsBody.setDeactivationDeadline(3f)
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

    private class Setup(
        val label: String,
        val substeps: Int,
        /** Ground: a block ramp scene, or a flat box. */
        val ramp: Boolean,
        val friction: Float = 0f,
        /** The suspension spring and mean damper held by the mount joint, as production does. */
        val jointSprings: Boolean = true,
        /** Reproduces the per-joint iteration override production no longer uses. */
        val iterationOverride: Int = 0,
        val parked: Boolean = false,
        /** After parking, wake the hull and wheels the way the drive layer does and push; measure how far it goes. */
        val driveAfterParking: Boolean = false,
        /** Solver iterations for the whole world: contacts iterate as long as joints do. Null: the spec's. */
        val globalIterations: Int? = null,
        /** Apply the part of the suspension row the joint spring does not carry (bump stop, asymmetric damping). */
        val explicitResidual: Boolean = true,
    )

    private class Result(
        val label: String,
        val settleDropMm: Double,
        val flatPiecesMax: Int,
        val staticGapMm: Double,
        val rampGapMm: Double,
        val spanGapMm: Double,
        val sinkMm: Double,
        val climbM: Double,
        val splits: Int,
        val merges: Int,
        val moves: Int,
        val maxPieces: Int,
        val endPieces: Int,
        val flaps: Int,
        val finite: Boolean,
        val parkedDriftMm: Double,
        val parkedSpeedMmps: Double,
        val parkedAwake: String = "",
        val trace: String = "",
        val wokenTravelM: Double = 0.0,
        val wokenAwake: String = "",
        val maxWheelLockViolationMm: Double = 0.0,
        val maxWheelLockViolation: String = "",
    ) {
        override fun toString(): String =
            "[$label] settle=${"%.0f".format(settleDropMm)} mm flatPieces<=$flatPiecesMax staticGap=${"%.1f".format(staticGapMm)} mm " +
                "rampGap=${"%.1f".format(rampGapMm)} mm spanPinGap=${"%.1f".format(spanGapMm)} mm sink=${"%.0f".format(sinkMm)} mm climb=${"%.2f".format(climbM)} m " +
                "splits=$splits merges=$merges moves=$moves maxPieces=$maxPieces endPieces=$endPieces flaps=$flaps " +
                "parkedDrift=${"%.1f".format(parkedDriftMm)} mm (${"%.2f".format(parkedSpeedMmps)} mm/s) $parkedAwake"
    }

    private fun run(setup: Setup, driveSeconds: Int = 22, parkedSeconds: Int = 0): Result {
        val boxes = ArrayList<TerrainBox>()
        for (x in -3..4) for (z in -14..19) boxes += TerrainBox(x.toDouble(), -1.0, z.toDouble(), x + 1.0, 0.0, z + 1.0)
        if (setup.ramp) for (x in -3..4) for (z in 8..19) boxes += TerrainBox(x.toDouble(), 0.0, z.toDouble(), x + 1.0, 1.0, z + 1.0)
        val plan = TrackedTerrainMeshPlanner.build(boxes, CompiledTerrain(true, 1.0, 3.0, 12, 3, 4), -3.0, -1.0, -14.0, 1L)

        val side = spec.side(TrackedSide.LEFT)
        val envelope = side.envelope!!
        val space = PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT)
        space.setGravity(Vector3f(0f, -9.81f, 0f))
        space.solverInfo.setNumIterations(setup.globalIterations ?: spec.solverIterations)
        val margin = TrackedTerrainMeshProxyRuntime.MESH_MARGIN
        val terrain = if (setup.ramp) {
            PhysicsRigidBody(
                MeshCollisionShape(
                    true,
                    IndexedMesh(
                        Array(plan.vertices.size / 3) { Vector3f(plan.vertices[it * 3], plan.vertices[it * 3 + 1], plan.vertices[it * 3 + 2]) },
                        plan.indices,
                    ),
                ).apply { this.margin = margin },
                0f,
            ).apply {
                // Lowered by its margin so the contact surface stays on the block tops, as production does.
                setPhysicsLocation(Vector3f(plan.originX.toFloat(), plan.originY.toFloat() - margin, plan.originZ.toFloat()))
            }
        } else {
            PhysicsRigidBody(BoxCollisionShape(Vector3f(20f, 1f, 40f)), 0f).apply { setPhysicsLocation(Vector3f(0f, -1f, 0f)) }
        }
        terrain.collisionGroup = TrackedTerrainCollision.TERRAIN_MESH
        terrain.setCollideWithGroups(CollisionGroups.NONE)
        space.addCollisionObject(terrain)

        val rest = (envelope.thicknessM * 0.5 - envelope.bottom.start.y).toFloat()
        val origin = Vector3f(0f, rest, -6f)
        val hullMass = spec.sprungAssemblyMassKg / 2.0
        val hull = PhysicsRigidBody(BoxCollisionShape(0.5f), hullMass.toFloat())
        hull.collisionGroup = CollisionGroups.CHASSIS
        hull.setCollideWithGroups(CollisionGroups.NONE)
        hull.setPhysicsLocation(origin)
        hull.setLinearFactor(Vector3f(0f, 1f, 1f))
        hull.setAngularFactor(Vector3f(1f, 0f, 0f))
        hull.setInverseInertiaLocal(Vector3f((12.0 / (hullMass * (49.0 + 2.25))).toFloat(), 0f, 0f))
        // Sleeping thresholds are the production ones when the case is about parking.
        hull.setSleepingThresholds(if (setup.parked) .05f else 0f, if (setup.parked) .05f else 0f)
        space.addCollisionObject(hull)

        val wheels = side.wheels.associate { wheel ->
            val body = PhysicsRigidBody(TrackedDynamicBelt.wheelShape(side, wheel), wheel.massKg.toFloat())
            body.setInverseInertiaLocal(TrackedDynamicBelt.wheelInverseInertia(wheel))
            body.collisionGroup = CollisionGroups.WHEEL
            body.setCollideWithGroups(TrackedTerrainCollision.TERRAIN_MESH)
            // Road wheels start at their loaded position, where the belt was fitted.
            val center = envelope.wheelCenters.getValue(wheel.index)
            body.setPhysicsLocation(origin.add(center.x.toFloat(), center.y.toFloat(), center.z.toFloat()))
            body.setSleepingThresholds(if (setup.parked) .02f else 0f, if (setup.parked) .02f else 0f)
            space.addCollisionObject(body)
            wheel.index to body
        }
        val jointSprung = HashSet<Int>()
        for (mount in side.mounts) {
            val plan0 = mount.joint ?: continue
            val created = TrackedJointFactory.create(plan0, hull, wheels.getValue(mount.wheelIndex))
            if (setup.jointSprings && mount.springRow != null) {
                TrackedSuspensionSprings.configure(created, mount.springRow!!)
                jointSprung += mount.wheelIndex
            }
            // The override production used to put on the guide-wheel mounts as well.
            if (setup.iterationOverride > 0 && !mount.isSprung) created.overrideIterations(setup.iterationOverride)
            space.addJoint(created)
        }

        val world = object : BeltWorld {
            override fun configure(body: PhysicsRigidBody) {
                body.collisionGroup = CollisionGroups.TRACK_LINK
                // A plain PhysicsSpace has no Spark owner filter; terrain only.
                body.setCollideWithGroups(TrackedTerrainCollision.TERRAIN_MESH)
                body.friction = setup.friction
                body.setSleepingThresholds(if (setup.parked) .05f else 0f, if (setup.parked) .05f else 0f)
            }
            override fun addBody(body: PhysicsRigidBody) = space.addCollisionObject(body)
            override fun removeBody(body: PhysicsRigidBody) = space.removeCollisionObject(body)
            override fun recordJoint(joint: New6Dof) = Unit
            override fun addJoint(joint: New6Dof) {
                if (setup.iterationOverride > 0) joint.overrideIterations(setup.iterationOverride)
                space.addJoint(joint)
            }
            override fun removeJoint(joint: New6Dof) {
                space.removeJoint(joint)
                joint.destroy()
            }
            override fun terrainRay(from: Vector3f, to: Vector3f): Float? =
                space.rayTest(from, to).filter { it.collisionObject === terrain }.minOfOrNull { it.hitFraction }
        }
        val belt = TrackedDynamicBelt(side, envelope, world, hull, wheels, "test_left")
        belt.build(origin, Quaternion(), Vector3f(), Vector3f())

        val rows = side.mounts.mapNotNull { mount -> mount.springRow?.let { mount to it } }
        fun frameOf(mount: CompiledWheelMount): Quaternion {
            val f = mount.joint!!.frameInA.rotation
            return Quaternion(f.x, f.y, f.z, f.w).normalizeLocal()
        }
        fun axisOf(mount: CompiledWheelMount): Vector3f =
            hull.getPhysicsRotation(null).toRotationMatrix().mult(frameOf(mount).toRotationMatrix().mult(Vector3f(0f, 1f, 0f), Vector3f()), Vector3f()).normalize()
        fun travel(mount: CompiledWheelMount): Float {
            val anchor = mount.joint!!.frameInA.origin
            val local = hull.getPhysicsRotation(null).inverse().toRotationMatrix()
                .mult(wheels.getValue(mount.wheelIndex).getPhysicsLocation(null).subtract(hull.getPhysicsLocation(null)), Vector3f())
                .subtract(anchor.x.toFloat(), anchor.y.toFloat(), anchor.z.toFloat())
            return frameOf(mount).inverse().toRotationMatrix().mult(local, Vector3f()).y
        }
        fun rate(mount: CompiledWheelMount, axis: Vector3f): Float {
            val body = wheels.getValue(mount.wheelIndex)
            val offset = body.getPhysicsLocation(null).subtract(hull.getPhysicsLocation(null))
            val hullAtWheel = hull.getLinearVelocity(null).add(hull.getAngularVelocity(null).cross(offset, Vector3f()))
            return body.getLinearVelocity(null).subtract(hullAtWheel).dot(axis)
        }
        var driving = false
        val sideGearMass = side.wheels.sumOf { it.massKg } + envelope.massPerMetreKg * envelope.freeLengthM
        fun forces() {
            for ((mount, row) in rows) {
                val axis = axisOf(mount)
                val x = travel(mount)
                val v = rate(mount, axis)
                val force = if (mount.wheelIndex in jointSprung) {
                    if (setup.explicitResidual) TrackedSuspensionSprings.residual(row, x, v) else 0f
                } else {
                    TrackedSuspensionSprings.fullForce(row, x, v)
                }
                // Like the runtime: no force, no wake-up, so a parked vehicle can sleep.
                if (!force.isFinite() || force == 0f) continue
                val body = wheels.getValue(mount.wheelIndex)
                hull.applyForce(axis.mult(force), body.getPhysicsLocation(null).subtract(hull.getPhysicsLocation(null)))
                body.applyCentralForce(axis.mult(-force))
            }
            if (driving) {
                // Traction-limited push toward 1 m/s, standing in for the belt conveyor.
                val speed = hull.getLinearVelocity(null).z
                val limit = ((hullMass + sideGearMass) * 9.81 * 0.9).toFloat()
                hull.applyCentralForce(Vector3f(0f, 0f, ((1f - speed) * 50f * hullMass.toFloat()).coerceIn(-limit, limit)))
            }
        }
        fun substep() {
            // Bullet clears applied forces after every update, so apply them before each.
            repeat(setup.substeps) {
                forces()
                space.update(1f / 60f / setup.substeps, 0, false, true, false)
            }
        }

        fun ground(z: Float): Double = if (!setup.ramp) 0.0 else when {
            z < 5f -> 0.0
            z < 8f -> (z - 5.0) / 3.0
            else -> 1.0
        }
        fun sink(): Double {
            var worst = 0.0
            for (point in belt.loopWorld()) {
                // Only the lower half of the loop can touch the ground.
                if (point.y - hull.getPhysicsLocation(null).y > -0.6f) continue
                worst = max(worst, ground(point.z) - (point.y - envelope.thicknessM * 0.5))
            }
            return worst
        }

        var flatPiecesMax = 0
        var staticGap = 0.0
        // Settle for 3 s, one segmentation decision per game tick (three substeps).
        for (tick in 0 until 60) {
            repeat(3) { substep() }
            belt.updateSegmentation(0.05)
            flatPiecesMax = max(flatPiecesMax, belt.bottomPieceCount)
            if (tick >= 40) staticGap = max(staticGap, belt.worstPinGapM())
        }
        val settleY = hull.getPhysicsLocation(null).y

        // Parked: no drive at all, measured over [parkedSeconds].
        var parkedDrift = 0.0
        var parkedSpeed = 0.0
        var parkedAwake = ""
        if (parkedSeconds > 0) {
            // The suspension is still ringing after the first 3 s; measure creep after it has settled.
            for (tick in 0 until 8 * 20) {
                repeat(3) { substep() }
                belt.updateSegmentation(0.05)
            }
            val start = hull.getPhysicsLocation(null).z
            var maxVy = 0.0
            var sumVy2 = 0.0
            var samples = 0
            val bottom = belt.pieces.first { it.kind == TrackedDynamicBelt.Kind.BOTTOM }.body
            for (tick in 0 until parkedSeconds * 20) {
                repeat(3) {
                    substep()
                    val vy = abs(bottom.getLinearVelocity(null).y).toDouble()
                    maxVy = max(maxVy, vy); sumVy2 += vy * vy; samples++
                }
                belt.updateSegmentation(0.05)
            }
            parkedDrift = (hull.getPhysicsLocation(null).z - start) * 1000.0
            parkedSpeed = parkedDrift / parkedSeconds
            val bodies = belt.pieces.map { it.body } + wheels.values + hull
            parkedAwake = "awake=${bodies.count { it.isActive }}/${bodies.size} bottom|vy| max=${"%.4f".format(maxVy)} rms=${"%.5f".format(kotlin.math.sqrt(sumVy2 / samples))} m/s " +
                "speeds(lin mm/s, ang mrad/s)=" + bodies.joinToString(" ") { "(%.1f,%.2f state=%d dt=%.2f)".format(it.getLinearVelocity(null).length() * 1000, it.getAngularVelocity(null).length() * 1000, it.activationState, it.deactivationTime) } +
                " deadline=${com.jme3.bullet.objects.PhysicsBody.getDeactivationDeadline()}"
        }

        val trace = StringBuilder()
        var wokenTravel = 0.0
        var wokenAwake = ""
        if (setup.driveAfterParking) {
            val start = hull.getPhysicsLocation(null).z
            val before = (belt.pieces.map { it.body } + wheels.values + hull).count { it.isActive }
            // What applyDrive does when the demand is not a coast: wake the hull and every wheel.
            hull.activate(true)
            for (body in wheels.values) body.activate(true)
            driving = true
            for (tick in 0 until 60) {
                repeat(3) { substep() }
                belt.updateSegmentation(0.05)
            }
            driving = false
            wokenTravel = (hull.getPhysicsLocation(null).z - start).toDouble()
            val all = belt.pieces.map { it.body } + wheels.values + hull
            wokenAwake = "awakeBefore=$before awakeAfter=${all.count { it.isActive }}/${all.size}"
        }
        var rampGap = 0.0
        var spanGap = 0.0
        var peakSink = sink()
        var maxPieces = belt.bottomPieceCount
        var flaps = 0
        var maxWheelLockViolationMm = 0.0
        var maxWheelLockViolation = ""
        val recentMerges = ArrayList<Pair<Double, Int>>()
        var finite = true
        if (driveSeconds > 0) {
            driving = true
            for (tick in 0 until driveSeconds * 20) {
                repeat(3) { substep() }
                val cutsBefore = belt.cutPositions
                val change = belt.updateSegmentation(0.05)
                if (change != TrackedDynamicBelt.Change.NONE) {
                    val cutsAfter = belt.cutPositions
                    // A flap is a split right back where a merge just removed a cut.
                    for (removed in cutsBefore.filter { old -> cutsAfter.none { abs(it - old) < 1.0e-6 } }) {
                        if (change == TrackedDynamicBelt.Change.MERGE) recentMerges += removed to tick
                    }
                    if (change == TrackedDynamicBelt.Change.SPLIT) {
                        for (added in cutsAfter.filter { new -> cutsBefore.none { abs(it - new) < 1.0e-6 } }) {
                            if (recentMerges.any { (at, whenTick) -> abs(at - added) < 0.5 && tick - whenTick < 20 }) flaps++
                        }
                    }
                }
                if (tick % 20 == 0) {
                    val q = hull.getPhysicsRotation(null)
                    val pitchDeg = Math.toDegrees(2.0 * kotlin.math.atan2(q.x.toDouble(), q.w.toDouble()))
                    val travels = rows.map { (mount, _) -> "%.0f".format(travel(mount) * 1000) }
                    // Ground under each road wheel's foot, to compare the compression with the terrain.
                    val under = rows.map { (mount, _) ->
                        val w = wheels.getValue(mount.wheelIndex).getPhysicsLocation(null)
                        "%.2f".format(ground(w.z))
                    }
                    trace.append("\n    t=${tick / 20}s hullZ=${"%.1f".format(hull.getPhysicsLocation(null).z)} pitch=${"%.1f".format(pitchDeg)}deg pieces=${belt.bottomPieceCount} travel(mm)=$travels ground(m)=$under wheelLocks=${belt.wheelLockDiagnostics().take(8)}")
                }
                maxPieces = max(maxPieces, belt.bottomPieceCount)
                spanGap = max(spanGap, belt.worstSpanPinGapM())
                rampGap = max(rampGap, belt.worstPinGapM())
                val wheelLock = belt.worstWheelLockViolation()
                if (wheelLock.second * 1000.0 > maxWheelLockViolationMm) {
                    maxWheelLockViolationMm = wheelLock.second * 1000.0
                    maxWheelLockViolation = wheelLock.first
                }
                peakSink = max(peakSink, sink())
                finite = finite && belt.pieces.all { val p = it.body.getPhysicsLocation(null); p.x.isFinite() && p.y.isFinite() && p.z.isFinite() }
            }
        }
        trace.append("\n    finalCuts=${belt.cutDiagnostics()}")
        val result = Result(
            label = setup.label,
            settleDropMm = (rest - settleY) * 1000.0,
            flatPiecesMax = flatPiecesMax,
            staticGapMm = staticGap * 1000.0,
            rampGapMm = rampGap * 1000.0,
            spanGapMm = spanGap * 1000.0,
            sinkMm = peakSink * 1000.0,
            climbM = (hull.getPhysicsLocation(null).y - settleY).toDouble(),
            splits = belt.splitCount,
            merges = belt.mergeCount,
            moves = belt.moveCount,
            maxPieces = maxPieces,
            endPieces = belt.bottomPieceCount,
            flaps = flaps,
            finite = finite,
            parkedDriftMm = parkedDrift,
            parkedSpeedMmps = parkedSpeed,
            parkedAwake = parkedAwake,
            trace = trace.toString(),
            wokenTravelM = wokenTravel,
            wokenAwake = wokenAwake,
            maxWheelLockViolationMm = maxWheelLockViolationMm,
            maxWheelLockViolation = maxWheelLockViolation,
        )
        belt.destroy()
        space.destroy()
        return result
    }

    /**
     * The chain from hull to ground needs more than Bullet's default 10 iterations to hold
     * its pins on a slope; the vehicle asks the world for [TrackedVehicleSpec.solverIterations].
     */
    @Test
    fun moreWorldIterationsHoldTheBeltTogetherOnTheRamp() {
        val atDefault = run(Setup("ramp, 10 iterations", spec.solverSubsteps, ramp = true, globalIterations = 10))
        val production = run(Setup("ramp, production iterations", spec.solverSubsteps, ramp = true))
        println("envelope $atDefault")
        println("envelope $production")
        assertTrue(spec.solverIterations > 10, "the vehicle must request more than Bullet's default")
        assertTrue(production.spanGapMm < atDefault.spanGapMm * 0.5, "span pin gap ${production.spanGapMm} mm vs ${atDefault.spanGapMm} mm at 10 iterations")
    }

    /** Diagnostic sweep only: this does not define or relax a wheel-lock acceptance limit. */
    @Test
    fun wheelLockResidualConvergenceSweep() {
        for ((iterations, substeps) in listOf(60 to 2, 120 to 2, 60 to 4, 120 to 4)) {
            val result = run(
                Setup(
                    "wheel-lock iterations=$iterations substeps=$substeps",
                    substeps,
                    ramp = true,
                    globalIterations = iterations,
                ),
                driveSeconds = 18,
            )
            println(
                "wheel-lock convergence ${result.label}: max=${"%.2f".format(result.maxWheelLockViolationMm)} mm " +
                    "joint=${result.maxWheelLockViolation}, pin=${"%.2f".format(result.spanGapMm)} mm " +
                    "climb=${"%.2f".format(result.climbM)} m trace=${result.trace.lines().filter { "t=9s" in it || "t=12s" in it || "t=15s" in it }.joinToString()}",
            )
        }
    }

    @Test
    fun flatGroundKeepsOneBottomPiece() {
        val result = run(Setup("flat", spec.solverSubsteps, ramp = false), driveSeconds = 10)
        println("envelope $result")
        assertTrue(result.finite)
        assertEquals(1, result.flatPiecesMax, "flat settle keeps one bottom piece")
        assertEquals(1, result.maxPieces, "flat driving keeps one bottom piece")
        assertEquals(0, result.splits)
    }

    @Test
    fun theRampSplitsAndMergesTheBottom() {
        val production = run(Setup("production", spec.solverSubsteps, ramp = true, friction = 0f))
        val oneSubstep = run(Setup("1 substep", 1, ramp = true))
        val explicitRows = run(Setup("explicit suspension rows", spec.solverSubsteps, ramp = true, jointSprings = false))
        listOf(production, oneSubstep, explicitRows).forEach { println("envelope $it") }
        println("envelope wheel-lock maximum=${"%.2f".format(production.maxWheelLockViolationMm)} mm joint=${production.maxWheelLockViolation}")
        println("envelope RAMPTRACE ${production.trace}")
        assertTrue(production.finite)
        assertEquals(1, production.flatPiecesMax, "the settled belt starts as one bottom piece")
        assertTrue(production.splits > 0, "the ramp must split the bottom")
        assertEquals(0, production.flaps, "no split right back where a merge just happened")
        assertTrue(production.maxPieces <= 4, "a 1:3 ramp needs at most a toe and a crest cut, had ${production.maxPieces}")
        assertTrue(production.climbM > 0.9, "the hull must climb the 1 m step, climbed ${production.climbM}")
        assertEquals(1, production.endPieces, "the bottom merges back to one piece on the flat top")
    }

    /**
     * A parked vehicle must stay parked. The ground friction is the production one.
     * The reference (not frozen) is 5 mm in 30 s.
     */
    @Test
    fun aParkedVehicleDoesNotCreep() {
        val friction = spec.drivetrain.friction.toFloat()
        val production = run(Setup("parked, production", spec.solverSubsteps, ramp = false, friction = friction, parked = true), driveSeconds = 0, parkedSeconds = 30)
        val explicitRows = run(Setup("parked, explicit suspension rows", spec.solverSubsteps, ramp = false, friction = friction, jointSprings = false, parked = true), driveSeconds = 0, parkedSeconds = 30)
        println("envelope $production")
        println("envelope $explicitRows")
        assertTrue(abs(production.parkedDriftMm) <= 5.0, "a parked vehicle crept ${production.parkedDriftMm} mm in 30 s")
    }

    /**
     * Why production has no per-joint iteration override. Bullet solves contact and
     * friction rows only for the first numIterations passes; a joint with an override
     * keeps iterating alone afterwards, so the joints get the last word and one step of
     * unbalanced chain force is left in the belt every update. The vehicle is kept
     * awake here so the creep is visible: 34 mm/s with an override of 40, 3 mm/s
     * without, at the same tension and Bullet's default 10 world iterations.
     */
    @Test
    fun aPerJointIterationOverrideLeavesFrictionBehind() {
        val friction = spec.drivetrain.friction.toFloat()
        val without = run(Setup("no override", spec.solverSubsteps, ramp = false, friction = friction, jointSprings = false, globalIterations = 10), driveSeconds = 0, parkedSeconds = 20)
        val with = run(Setup("override 40", spec.solverSubsteps, ramp = false, friction = friction, jointSprings = false, iterationOverride = 40, globalIterations = 10), driveSeconds = 0, parkedSeconds = 20)
        println("envelope ${without}")
        println("envelope ${with}")
        assertTrue(abs(with.parkedDriftMm) > 3.0 * abs(without.parkedDriftMm) + 20.0, "the override must visibly increase creep: ${with.parkedDriftMm} vs ${without.parkedDriftMm} mm")
    }

    /** A vehicle that has fallen asleep must wake and drive off when the driver asks, belt included. */
    @Test
    fun aSleepingVehicleWakesWhenDriven() {
        val friction = spec.drivetrain.friction.toFloat()
        val result = run(Setup("woken", spec.solverSubsteps, ramp = false, friction = friction, parked = true, driveAfterParking = true), driveSeconds = 0, parkedSeconds = 12)
        println("envelope $result woken: travel=${"%.2f".format(result.wokenTravelM)} m ${result.wokenAwake}")
        assertTrue(result.parkedAwake.contains("awake=0/"), "the parked vehicle must be asleep first: ${result.parkedAwake}")
        assertTrue(result.wokenTravelM > 0.5, "a woken vehicle must drive off, moved ${result.wokenTravelM} m (${result.wokenAwake})")
    }

    /**
     * The other half of the creep: explicit suspension forces wake every road wheel on every
     * update, so the island never sleeps and the belt's ground friction walks the vehicle.
     */
    @Test
    fun explicitSuspensionForcesKeepAParkedVehicleAwake() {
        val friction = spec.drivetrain.friction.toFloat()
        val explicit = run(Setup("explicit rows", spec.solverSubsteps, ramp = false, friction = friction, jointSprings = false, parked = true), driveSeconds = 0, parkedSeconds = 20)
        val implicit = run(Setup("joint springs", spec.solverSubsteps, ramp = false, friction = friction, parked = true), driveSeconds = 0, parkedSeconds = 20)
        println("envelope ${explicit}")
        println("envelope ${implicit}")
        assertTrue(explicit.parkedAwake.contains("awake=13/13"), "explicit rows keep everything awake: ${explicit.parkedAwake}")
        assertTrue(implicit.parkedAwake.contains("awake=0/"), "joint-held suspension lets the vehicle sleep: ${implicit.parkedAwake}")
    }
}
