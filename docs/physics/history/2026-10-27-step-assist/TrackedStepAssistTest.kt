package com.atsuishio.superbwarfare.physics.tracked

import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * S3.2 step-assist policy, without a Level.
 *
 * The terrain probe needs a real `Level`, but the *decision* rules do not: the
 * height gate, the refusal of two-block walls and the lift sizing are pure. Those
 * are the parts that can silently turn into "the vehicle climbs anything", so
 * they are asserted directly.
 */
class TrackedStepAssistTest {

    @Test
    fun aOneBlockStepIsClimbable() {
        assertTrue(TrackedStepAssist.isClimbable(1.0), "a full block must be climbable")
        assertTrue(TrackedStepAssist.isClimbable(0.5), "a slab must be climbable")
        assertFalse(TrackedStepAssist.isClimbable(0.0), "no rise is not a step")
        assertFalse(TrackedStepAssist.isClimbable(0.01), "a millimetre lip is not a step")
    }

    @Test
    fun aTwoBlockWallIsRefused() {
        assertFalse(TrackedStepAssist.isClimbable(2.0), "two blocks must never be assisted")
        assertFalse(TrackedStepAssist.isClimbable(1.5), "anything above one block must be refused")
    }

    @Test
    fun aRefusedWallProducesNoContactAssist() {
        val contact = Vec3(0.0, 64.0, 0.0)
        val wall = TrackedStepAssist.TerrainCandidate(
            bounds = AABB(-0.5, 63.0, 0.2, 0.5, 66.0, 1.2),
            rise = 2.0,
            run = 1.0,
            distance = 0.2,
        )
        val assist = TrackedStepAssist.contactAssist(
            normal = Vec3(0.0, 0.0, -1.0),
            chassisForward = Vec3(0.0, 0.0, 1.0),
            candidate = wall,
            totalClimb = 2.0,
            forwardSpeed = 5.0,
            baseTargetSpeedMps = 6.0,
        )
        assertNull(assist, "a two-block wall must never receive a target")
    }

    @Test
    fun aRealContactProducesATangentialTargetOnly() {
        // An AABB stair's tread: the contact plane is already sloped, so a
        // forward push has a real uphill component the assist may bias. A
        // vertical riser is deliberately *not* assisted; see
        // aVerticalRiserIsNotTurnedIntoALadder.
        val candidate = TrackedStepAssist.TerrainCandidate(
            bounds = AABB(-0.5, 63.0, 0.2, 0.5, 65.0, 1.2),
            rise = 1.0,
            run = 1.0,
            distance = 0.2,
        )
        val normal = Vec3(0.0, 0.8, 0.6).normalize()
        val assist = TrackedStepAssist.contactAssist(
            normal = normal,
            chassisForward = Vec3(0.0, 0.0, -1.0),
            candidate = candidate,
            totalClimb = 1.0,
            forwardSpeed = 5.0,
            baseTargetSpeedMps = 6.0,
        )
        assertNotNull(assist, "a climbable real contact must produce a target")
        assist!!
        assertEquals(0.0, assist.tangent.dot(normal), 1e-6, "the target must stay in the contact plane")
        assertTrue(assist.tangent.y > 0.0, "the tangent must point up the real face")
        assertTrue(assist.targetSpeedMps > 0.0)
        assertTrue(assist.targetSpeedMps <= 6.0, "the target may not exceed the driver demand")
        assertTrue(assist.weight in 0.0..1.0)
    }

    @Test
    fun aVerticalRiserIsNotTurnedIntoALadder() {
        // A horizontal normal means the chassis forward has no in-plane
        // projection. Inventing the uphill tangent there drove the belt up a
        // face the hull was only pressed against; in a real GameTest it lifted
        // and flipped the vehicle off the fixture. The assist must return null.
        val candidate = TrackedStepAssist.TerrainCandidate(
            bounds = AABB(-0.5, 63.0, 0.2, 0.5, 64.0, 1.2),
            rise = 1.0,
            run = 1.0,
            distance = 0.0,
        )
        assertNull(
            TrackedStepAssist.contactAssist(
                normal = Vec3(0.0, 0.0, -1.0),
                chassisForward = Vec3(0.0, 0.0, -1.0),
                candidate = candidate,
                totalClimb = 1.0,
                forwardSpeed = 5.0,
                baseTargetSpeedMps = 6.0,
            ),
            "a vertical face must not receive a fabricated climb direction",
        )
    }

    @Test
    fun aCoastOrBrakeRequestProducesNoAssist() {
        val candidate = TrackedStepAssist.TerrainCandidate(
            bounds = AABB(-0.5, 63.0, 0.2, 0.5, 65.0, 1.2),
            rise = 1.0,
            run = 1.0,
            distance = 0.2,
        )
        val normal = Vec3(0.0, 0.0, -1.0)
        val assist = TrackedStepAssist.contactAssist(
            normal = normal,
            chassisForward = Vec3(0.0, 0.0, 1.0),
            candidate = candidate,
            totalClimb = 1.0,
            forwardSpeed = 5.0,
            baseTargetSpeedMps = 0.0,
        )
        assertNull(assist, "coast and brake have no driven target to assist")
    }

    @Test
    fun aSlowApproachProducesNoAssist() {
        val candidate = TrackedStepAssist.TerrainCandidate(
            bounds = AABB(-0.5, 63.0, 0.2, 0.5, 65.0, 1.2),
            rise = 1.0,
            run = 1.0,
            distance = 0.2,
        )
        val assist = TrackedStepAssist.contactAssist(
            normal = Vec3(0.0, 0.0, -1.0),
            chassisForward = Vec3(0.0, 0.0, 1.0),
            candidate = candidate,
            totalClimb = 1.0,
            forwardSpeed = 2.0,
            baseTargetSpeedMps = 6.0,
        )
        assertNull(assist, "below MinSpeed the assist must be inactive")
    }

    @Test
    fun theClimbComponentOfTheTargetIsBounded() {
        val candidate = TrackedStepAssist.TerrainCandidate(
            bounds = AABB(-0.5, 63.0, 0.2, 0.5, 65.0, 1.2),
            rise = 1.0,
            run = 0.3,
            distance = 0.2,
        )
        val assist = TrackedStepAssist.contactAssist(
            normal = Vec3(0.0, 0.6, 0.8).normalize(),
            chassisForward = Vec3(0.0, 0.0, -1.0),
            candidate = candidate,
            totalClimb = 1.0,
            forwardSpeed = 10.0,
            baseTargetSpeedMps = 15.0,
        )
        assertNotNull(assist)
        val climbSpeed = assist!!.tangent.y * assist.targetSpeedMps
        assertTrue(
            climbSpeed <= TrackedStepAssist.MAX_ASSIST_CLIMB_MPS + 1e-6,
            "the vertical part must stay bounded, was $climbSpeed",
        )
    }

    @Test
    fun aContactOffTheCandidateBoxDoesNotMatch() {
        val candidate = TrackedStepAssist.TerrainCandidate(
            bounds = AABB(-0.5, 63.0, 0.2, 0.5, 65.0, 1.2),
            rise = 1.0,
            run = 1.0,
            distance = 0.2,
        )
        assertTrue(TrackedStepAssist.contactMatches(Vec3(0.0, 64.0, 0.4), candidate))
        assertFalse(TrackedStepAssist.contactMatches(Vec3(0.0, 64.0, 4.0), candidate))
        assertFalse(TrackedStepAssist.contactMatches(Vec3(0.0, 58.0, 0.4), candidate))
    }

    @Test
    fun candidatesKeepSeparateBoxesAndRejectWallsBeyondTheRunWindow() {
        val contact = Vec3(0.0, 64.0, 0.0)
        val step = AABB(-0.5, 63.0, 0.2, 0.5, 65.0, 1.2)
        val far = AABB(-0.5, 63.0, 9.0, 0.5, 65.0, 10.0)
        val found = TrackedStepAssist.candidatesAhead(contact, Vec3(0.0, 0.0, 1.0), listOf(step, far))
        assertEquals(1, found.size, "a box beyond MaxRun must not be a candidate")
        assertEquals(1.0, found[0].rise, 1e-9)
    }

    @Test
    fun aTwoBlockWallIsTwoSeparateBoxesAndVetoesTheWholeApproach() {
        val contact = Vec3(0.0, 64.0, 0.0)
        val lower = AABB(-0.5, 64.0, 0.2, 0.5, 65.0, 1.2)
        val upper = AABB(-0.5, 65.0, 0.2, 0.5, 66.0, 1.2)
        val found = TrackedStepAssist.candidatesAhead(contact, Vec3(0.0, 0.0, 1.0), listOf(lower, upper))
        assertEquals(2, found.size, "the two boxes must not be merged into one ramp")
        // Each box keeps its own rise measured from the same ground contact.
        assertEquals(1.0, found.minOf { it.rise }, 1e-9)
        assertEquals(2.0, found.maxOf { it.rise }, 1e-9)
        // The lower box alone looks climbable; the approach as a whole is not.
        assertTrue(
            TrackedStepAssist.approachRefused(found),
            "a two-block wall must veto the whole approach, not just the tall box",
        )
        val climbable = found.filter { it.rise <= TrackedStepAssist.MAX_STEP_HEIGHT }
        assertEquals(1, climbable.size, "only the lower box looks climbable in isolation")
    }

    @Test
    fun aStationaryVehicleGetsNoAssist() {
        assertEquals(0.0, TrackedStepAssist.forwardSpeedGate(0.0), 0.0)
        assertEquals(0.0, TrackedStepAssist.forwardSpeedGate(-1.0), 0.0)
        assertEquals(0.0, TrackedStepAssist.forwardSpeedGate(Double.NaN), 0.0)
        // A crawl still climbs; the gate ramps in rather than switching on.
        assertTrue(TrackedStepAssist.forwardSpeedGate(0.25) in 0.0..1.0)
        assertEquals(1.0, TrackedStepAssist.forwardSpeedGate(5.0), 1e-9)
    }

    @Test
    fun aStepDirectlyAheadIsDetected() {
        val contact = Vec3(0.0, 64.0, 0.0)
        val forward = Vec3(0.0, 0.0, 1.0)
        // A one-block-high block whose top sits 1.0 m above the contact point and
        // whose footprint the probe actually stands over.
        val step = AABB(-0.5, 63.0, 0.2, 0.5, 65.0, 1.2)
        val assessment = TrackedStepAssist.assess(contact, forward, listOf(step))
        assertTrue(assessment.stepAhead, "a block ahead must be seen")
        assertEquals(1.0, assessment.stepHeight, 1e-9)
        assertFalse(assessment.refused, "one block must not be refused")
    }

    @Test
    fun aTwoBlockWallAheadIsRefused() {
        val contact = Vec3(0.0, 64.0, 0.0)
        val wall = AABB(-0.5, 63.0, 0.2, 0.5, 66.0, 1.2)
        val assessment = TrackedStepAssist.assess(contact, Vec3(0.0, 0.0, 1.0), listOf(wall))
        assertTrue(assessment.refused, "a two-block wall must be refused")
    }

    @Test
    fun aContinuousFaceIsRefusedEvenAfterClimbingItsFirstBlock() {
        // After mounting the lower block, the upper one measures only one block
        // of rise from the new support. Rise alone therefore cannot tell a ledge
        // from the second half of a wall; the continued face must refuse it.
        val contact = Vec3(0.0, 65.0, 0.0)
        val lower = AABB(-0.5, 64.0, 0.2, 0.5, 65.0, 1.2)
        val upper = AABB(-0.5, 65.0, 0.2, 0.5, 66.0, 1.2)
        val found = TrackedStepAssist.candidatesAhead(contact, Vec3(0.0, 0.0, 1.0), listOf(lower, upper))
        assertTrue(found.isNotEmpty(), "the upper box is a real candidate")
        assertTrue(
            found.any { it.stackedOnSameFootprint },
            "the upper box must be marked as a continued face, found=$found",
        )
        assertTrue(
            TrackedStepAssist.approachRefused(found),
            "a continued face must veto the approach even at one block of rise",
        )
    }

    @Test
    fun aLedgeWithAirAboveIsNotAContinuedFace() {
        val contact = Vec3(0.0, 64.0, 0.0)
        val ledge = AABB(-0.5, 63.0, 0.2, 0.5, 65.0, 1.2)
        val found = TrackedStepAssist.candidatesAhead(contact, Vec3(0.0, 0.0, 1.0), listOf(ledge))
        assertTrue(found.isNotEmpty())
        assertFalse(found[0].stackedOnSameFootprint, "a free-standing ledge has no face above it")
        assertFalse(TrackedStepAssist.approachRefused(found))
    }

    @Test
    fun terrainBehindOrBesideIsNotAStep() {
        val contact = Vec3(0.0, 64.0, 0.0)
        // Behind the vehicle: the probe is ahead at +Z, so this box is not under it.
        val behind = AABB(-0.5, 63.0, -3.0, 0.5, 65.0, -2.0)
        val assessment = TrackedStepAssist.assess(contact, Vec3(0.0, 0.0, 1.0), listOf(behind))
        assertFalse(assessment.stepAhead, "a box behind the probe is not a step ahead")
    }

    @Test
    fun flatGroundIsNotAStep() {
        val contact = Vec3(0.0, 65.0, 0.0)
        // Ground whose top is level with the contact point.
        val ground = AABB(-0.5, 63.0, 0.2, 0.5, 65.0, 1.2)
        val assessment = TrackedStepAssist.assess(contact, Vec3(0.0, 0.0, 1.0), listOf(ground))
        assertFalse(assessment.stepAhead, "level ground must not trigger the assist")
    }

    // ---- MCSP PhysRamps gate ------------------------------------------------

    @Test
    fun theSpeedGateRampsBetweenMinAndFullSpeed() {
        assertEquals(0.0, TrackedStepAssist.speedWeight(0.0), 0.0)
        assertEquals(0.0, TrackedStepAssist.speedWeight(2.999), 0.0)
        assertEquals(0.0, TrackedStepAssist.speedWeight(3.0), 1e-9)
        assertEquals(0.5, TrackedStepAssist.speedWeight((3.0 + 10.5) / 2.0), 1e-9)
        assertEquals(1.0, TrackedStepAssist.speedWeight(10.5), 1e-9)
        assertEquals(1.0, TrackedStepAssist.speedWeight(20.0), 1e-9)
        assertEquals(0.0, TrackedStepAssist.speedWeight(Double.NaN), 0.0)
    }

    @Test
    fun aSingleFaceMayNotExceedOneBlockEvenAtTheClimbLimit() {
        // A two-metre vertical wall is below MaxClimb but no single face may
        // exceed MaxStep, so it must be refused.
        assertFalse(TrackedStepAssist.rampGate(stepRise = 2.0, totalClimb = 2.0, run = 1.0, forwardSpeed = 5.0))
        assertTrue(TrackedStepAssist.rampGate(stepRise = 1.0, totalClimb = 2.0, run = 2.0, forwardSpeed = 5.0))
    }

    @Test
    fun theRunMustStayInsideTheAuthoredWindow() {
        assertFalse(TrackedStepAssist.rampGate(0.5, 1.0, run = 0.24, forwardSpeed = 5.0))
        assertTrue(TrackedStepAssist.rampGate(0.5, 1.0, run = 0.25, forwardSpeed = 5.0))
        assertTrue(TrackedStepAssist.rampGate(0.5, 1.0, run = 2.5, forwardSpeed = 5.0))
        assertFalse(TrackedStepAssist.rampGate(0.5, 1.0, run = 2.51, forwardSpeed = 5.0))
    }

    @Test
    fun aSlowApproachIsGatedEvenWhenTheGeometryIsFine() {
        assertFalse(TrackedStepAssist.rampGate(0.5, 1.0, 1.0, forwardSpeed = 2.9))
        // At exactly MinSpeed the weight is still zero: the ramp starts there.
        assertFalse(TrackedStepAssist.rampGate(0.5, 1.0, 1.0, forwardSpeed = 3.0))
        assertTrue(TrackedStepAssist.rampGate(0.5, 1.0, 1.0, forwardSpeed = 3.1))
        assertFalse(TrackedStepAssist.rampGate(0.5, 1.0, 1.0, forwardSpeed = Double.NaN))
    }

    @Test
    fun theCumulativeClimbIsBoundedSeparately() {
        assertTrue(TrackedStepAssist.rampGate(0.5, totalClimb = 2.0, run = 1.0, forwardSpeed = 5.0))
        assertFalse(TrackedStepAssist.rampGate(0.5, totalClimb = 2.01, run = 1.0, forwardSpeed = 5.0))
        assertFalse(TrackedStepAssist.rampGate(0.5, totalClimb = 0.0, run = 1.0, forwardSpeed = 5.0))
    }

    @Test
    fun aLedgeMadeOfSeveralColumnsSharesOneFaceKey() {
        // One stone ledge built from three adjacent columns is three real boxes
        // but one face. If the key followed the box, every run segment on a
        // different column would write its own target for the same step.
        val forward = Vec3(0.0, 0.0, -1.0)
        val left = TrackedStepAssist.TerrainCandidate(
            bounds = AABB(-1.5, 63.0, 0.2, -0.5, 64.0, 1.2),
            rise = 1.0,
            run = 1.0,
            distance = 0.0,
        )
        val middle = TrackedStepAssist.TerrainCandidate(
            bounds = AABB(-0.5, 63.0, 0.2, 0.5, 64.0, 1.2),
            rise = 1.0,
            run = 1.0,
            distance = 0.0,
        )
        val right = TrackedStepAssist.TerrainCandidate(
            bounds = AABB(0.5, 63.0, 0.2, 1.5, 64.0, 1.2),
            rise = 1.0,
            run = 1.0,
            distance = 0.0,
        )
        val keys = listOf(left, middle, right).map { TrackedStepAssist.faceKey(it, forward) }
        assertEquals(1, keys.toSet().size, "one ledge must be one face, keys=$keys")
    }

    @Test
    fun twoSuccessiveStepsHaveDifferentFaceKeys() {
        val forward = Vec3(0.0, 0.0, -1.0)
        val lower = TrackedStepAssist.TerrainCandidate(
            bounds = AABB(-0.5, 63.0, 0.2, 0.5, 64.0, 1.2),
            rise = 1.0,
            run = 1.0,
            distance = 0.0,
        )
        val upper = TrackedStepAssist.TerrainCandidate(
            bounds = AABB(-0.5, 64.0, -0.8, 0.5, 65.0, 0.2),
            rise = 2.0,
            run = 1.0,
            distance = 0.0,
        )
        assertTrue(
            TrackedStepAssist.faceKey(lower, forward) != TrackedStepAssist.faceKey(upper, forward),
            "two steps at different depth and height must not share one budget",
        )
    }
}
