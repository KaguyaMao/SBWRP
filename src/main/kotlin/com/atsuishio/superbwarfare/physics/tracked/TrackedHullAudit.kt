package com.atsuishio.superbwarfare.physics.tracked

import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3d
import kotlin.math.abs

/**
 * Checks that the solid chassis boxes leave the running gear free.
 *
 * A hull box that encloses a belt run or a wheel turns every substep into a
 * fight between the contact pushing the gear out and the joints pulling it
 * back. The running gear is not supposed to touch its own hull at all, so any
 * overlap is a compile error rather than something the solver resolves.
 *
 * Wheels are tested as their bounding box (axle half width by radius), which
 * is conservative. Runs are tested as their true oriented box.
 */
internal object TrackedHullAudit {

    data class Box(val center: Vec3, val rotation: Quaternionf, val half: Vec3)

    data class Overlap(val hullIndex: Int, val member: String, val depthM: Double)

    fun audit(
        hull: List<CompiledHullBox>,
        sides: Collection<CompiledTrackSide>,
        toleranceM: Double = 1.0e-3,
    ): List<Overlap> {
        val members = ArrayList<Pair<String, Box>>()
        for (side in sides) {
            val label = side.side.name.lowercase()
            for (wheel in side.wheels) {
                members += "${label}_${wheel.bone}" to Box(
                    wheel.assemblyCenter,
                    Quaternionf(),
                    Vec3(wheel.halfWidth, wheel.radius, wheel.radius),
                )
            }
            for (run in side.runs) {
                members += "${label}_run_${run.index}" to Box(run.center, run.rotation.toQuaternion(), run.halfExtents)
            }
            for (run in side.physicalRuns) for ((index, link) in run.links.withIndex()) {
                members += "${label}_${run.label}_${run.index}_$index" to
                    Box(link.center, link.rotation.toQuaternion(), link.halfExtents)
            }
        }
        val out = ArrayList<Overlap>()
        hull.forEachIndexed { index, box ->
            val hullBox = Box(box.center, box.rotation.toQuaternion(), box.halfExtents)
            for ((name, member) in members) {
                val depth = overlap(hullBox, member)
                if (depth > toleranceM) out += Overlap(index, name, depth)
            }
        }
        return out
    }

    /**
     * Separating-axis test for two oriented boxes.
     *
     * Returns the smallest overlap along any of the 15 candidate axes: positive
     * when the boxes intersect (the penetration depth), negative when separated.
     */
    fun overlap(a: Box, b: Box): Double {
        val axesA = axes(a.rotation)
        val axesB = axes(b.rotation)
        val ha = doubleArrayOf(a.half.x, a.half.y, a.half.z)
        val hb = doubleArrayOf(b.half.x, b.half.y, b.half.z)
        val delta = Vector3d(b.center.x - a.center.x, b.center.y - a.center.y, b.center.z - a.center.z)
        val candidates = ArrayList<Vector3d>(15)
        candidates += axesA
        candidates += axesB
        for (u in axesA) for (v in axesB) {
            val cross = Vector3d(u).cross(v)
            if (cross.lengthSquared() > 1.0e-10) candidates += cross.normalize()
        }
        var best = Double.POSITIVE_INFINITY
        for (axis in candidates) {
            val ra = (0..2).sumOf { ha[it] * abs(axesA[it].dot(axis)) }
            val rb = (0..2).sumOf { hb[it] * abs(axesB[it].dot(axis)) }
            val distance = abs(delta.dot(axis))
            val depth = ra + rb - distance
            if (depth < best) best = depth
        }
        return best
    }

    private fun axes(rotation: Quaternionf): List<Vector3d> {
        val q = Quaternionf(rotation).normalize()
        return listOf(
            Vector3d(1.0, 0.0, 0.0).rotate(org.joml.Quaterniond(q.x.toDouble(), q.y.toDouble(), q.z.toDouble(), q.w.toDouble())),
            Vector3d(0.0, 1.0, 0.0).rotate(org.joml.Quaterniond(q.x.toDouble(), q.y.toDouble(), q.z.toDouble(), q.w.toDouble())),
            Vector3d(0.0, 0.0, 1.0).rotate(org.joml.Quaterniond(q.x.toDouble(), q.y.toDouble(), q.z.toDouble(), q.w.toDouble())),
        )
    }
}
