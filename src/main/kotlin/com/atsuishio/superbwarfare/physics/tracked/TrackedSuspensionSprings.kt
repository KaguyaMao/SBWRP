package com.atsuishio.superbwarfare.physics.tracked

import cn.solarmoon.spark_core.physics.PhysicsSuspensionRow
import cn.solarmoon.spark_core.physics.SuspensionRowSpec
import cn.solarmoon.spark_core.physics.SuspensionRowState
import cn.solarmoon.spark_core.physics.body.PhysicsJoints
import com.jme3.bullet.joints.New6Dof

/**
 * Splits a suspension row into a part the constraint solver carries and a part
 * the runtime still applies as a force.
 *
 * A sprung road wheel under a belt is a long chain: hull, spring, wheel, belt,
 * ground. A spring force applied from outside is integrated explicitly against a
 * 900 kg wheel and is only seen by the solver one step late. Measured on the
 * 99A rig (TrackedEnvelopeBeltNativeTest), the explicit rows leave about ten
 * times more residual creep at rest than the same spring held by the mount
 * joint itself (3.0 against 0.27 mm/s), because the joint's own spring row is
 * solved with every other row of the island.
 *
 * A wheel that is pushed on every update can never fall asleep: applying a force
 * wakes the body, so a parked vehicle whose road wheels carry explicit spring forces
 * stays awake for ever and keeps creeping at a few mm/s on the belt's ground friction.
 * With the spring in the joint, nothing is applied from outside while the wheel rides
 * in its normal range, and the vehicle can sleep.
 *
 * So the linear spring and a mean damper live in the mount joint. What the joint
 * cannot express is the row's progressive bump stop, so [residual] returns exactly
 * the full row minus the joint's own spring and damper, but only once the wheel is
 * into its bump-stop range (and zero before, where compression and rebound damping
 * differ by ten per cent of critical and the joint carries their mean).
 */
object TrackedSuspensionSprings {

    /** Damper rate the joint carries: the mean of the row's two directions. */
    fun jointDamping(row: SuspensionRowSpec): Float =
        (row.compressionDampingNsPerM + row.reboundDampingNsPerM) * 0.5f

    /**
     * Enable the spring on the travel axis (the mount frame's Y) of [joint].
     * Equilibrium is zero travel, the bind pose, as for the explicit row. The
     * travel limits are already in the joint from [TrackedJointFactory].
     */
    fun configure(joint: New6Dof, row: SuspensionRowSpec) {
        joint.enableSpring(PhysicsJoints.LIN_Y, true)
        joint.setStiffness(PhysicsJoints.LIN_Y, row.stiffnessNPerM, false)
        joint.setDamping(PhysicsJoints.LIN_Y, jointDamping(row), false)
        joint.setEquilibriumPoint(PhysicsJoints.LIN_Y, 0f)
    }

    /** The full row force for [travelM] / [rateMps], as the explicit model computes it. */
    fun fullForce(row: SuspensionRowSpec, travelM: Float, rateMps: Float): Float =
        PhysicsSuspensionRow.force(row, SuspensionRowState(travelM, rateMps))

    /**
     * What the row asks beyond the joint's own spring and damper, newtons; zero while
     * the wheel is below the start of its bump stop.
     */
    fun residual(row: SuspensionRowSpec, travelM: Float, rateMps: Float): Float {
        if (travelM <= row.bumpStopTravelM) return 0f
        val full = fullForce(row, travelM, rateMps)
        if (!full.isFinite()) return 0f
        return full - (row.stiffnessNPerM * travelM + jointDamping(row) * rateMps)
    }
}
