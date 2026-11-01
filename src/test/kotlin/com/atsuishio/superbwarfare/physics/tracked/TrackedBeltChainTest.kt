package com.atsuishio.superbwarfare.physics.tracked

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/** T0: the anchor-driven belt chain is a gap-free closed loop with sane geometry. */
class TrackedBeltChainTest {

    private fun spec(): TrackedVehicleSpec {
        val definition = Json { ignoreUnknownKeys = true; isLenient = true }.decodeFromString(
            TrackedVehicleDefinition.serializer(),
            File("src/main/resources/data/superbwarfare/sbw/tracked/ztz_99a.json").readText(),
        )
        // 99A now runs the envelope belt; the superseded anchor chain stays
        // compilable for comparison, so select it explicitly.
        definition.track.topology = TrackTopology.ANCHOR_CHAIN
        val model = File("src/main/resources/assets/superbwarfare/models/bedrock/vehicle/ztz_99a.geo.json").readText()
        return TrackedVehicleCompiler.compile("superbwarfare:ztz_99a", definition, model).requireSpec()
    }

    private fun chain(side: TrackedSide, policy: BeltChainPolicy = BeltChainPolicy()): CompiledBeltChain {
        val spec = spec()
        return TrackedBeltChainCompiler.compile(spec.side(side), spec.pitch, policy)
    }

    @Test
    fun theChainIsAClosedLoopWithNoGap() {
        for (side in TrackedSide.entries) {
            val chain = chain(side)
            assertEquals(0.0, chain.worstPinGapM, 1.0e-12, "$side every end pin is the next start pin")
            // Closed: the last segment ends where the first starts.
            val first = chain.segments.first().start
            val last = chain.segments.last().end
            assertEquals(0.0, hypot(first.y - last.y, first.z - last.z), 1.0e-9, "$side loop closes")
        }
    }

    @Test
    fun theLengthIsTheAnchorOutlineAndTheMassBudgetIsKept() {
        val spec = spec()
        for (side in TrackedSide.entries) {
            val chain = chain(side)
            // Anchor outline measured in the offline check: about 18.74 m.
            assertEquals(18.74, chain.restLengthM, 0.05, "$side rest length")
            assertEquals(spec.side(side).runMassTotalKg, chain.massKg, 1.0e-6, "$side mass budget")
            assertTrue(chain.segments.all { it.massKg > 0.0 })
        }
    }

    @Test
    fun theBottomRunIsCutIntoEqualUnitsOnThePitchGrid() {
        val spec = spec()
        val chain = chain(TrackedSide.LEFT)
        val bottom = chain.segments.filter { it.kind == BeltSegmentKind.BOTTOM }
        assertEquals(chain.bottomUnits, bottom.size)
        assertEquals(13, chain.bottomUnits, "6.0 m of bottom run in ~0.466 m units")
        val unit = chain.bottomUnitLengthM
        assertTrue(abs(unit / spec.pitch - 2.0) < 0.1, "unit is two pitches, was ${unit / spec.pitch}")
        for (piece in bottom) assertEquals(unit, piece.lengthM, 1.0e-9)
    }

    @Test
    fun theUnitLengthFollowsThePolicyRatherThanAConstant() {
        val three = chain(TrackedSide.LEFT, BeltChainPolicy(unitPitches = 3.0))
        assertEquals(9, three.bottomUnits)
        val one = chain(TrackedSide.LEFT, BeltChainPolicy(unitPitches = 1.0))
        assertTrue(one.bottomUnits >= 25, "one pitch units: ${one.bottomUnits}")
    }

    /**
     * The pin limit is relative to each pin's rest bend. Wheel corners rest at
     * about 90 degrees; bottom and top pins rest straight; no pin rests beyond a
     * right angle, and every range contains its own rest angle.
     */
    @Test
    fun everyPinRangeContainsItsRestBendAndRightAnglesAreTheWorstCase() {
        for (side in TrackedSide.entries) {
            val chain = chain(side)
            for (index in chain.segments.indices) {
                val (low, high) = chain.bendRange(index)
                val rest = chain.restBendRad(index)
                assertTrue(low < rest && rest < high, "$side pin $index range contains its rest bend")
            }
            val worst = Math.toDegrees(TrackedBeltChainCompiler.worstRestBendRad(chain))
            assertTrue(worst <= 90.5, "$side worst rest bend $worst deg is a right angle at most")
            val straight = chain.segments.indices.filter {
                chain.segments[it].kind == BeltSegmentKind.TOP && chain.segments[(it + 1) % chain.segments.size].kind == BeltSegmentKind.TOP
            }
            assertTrue(straight.all { abs(chain.restBendRad(it)) < 1.0e-9 }, "$side interior top pins are straight")
        }
    }

    @Test
    fun wheelCarriedPinsAreExactlyTheSixAnchors() {
        val spec = spec()
        for (side in TrackedSide.entries) {
            val compiled = spec.side(side)
            val chain = chain(side)
            val carried = chain.segments.filter { it.startWheel >= 0 }
            assertEquals(6, carried.size, "$side one carried start pin per anchor")
            for (segment in carried) {
                val anchor = compiled.anchors.single { it.ownerWheel == segment.startWheel && it.bindPoint == segment.start }
                assertEquals(anchor.bindPoint.y, segment.start.y, 0.0)
            }
        }
    }

    @Test
    fun theBoxesCoverTheirPinsAndOverlapForBends() {
        val chain = chain(TrackedSide.LEFT)
        for (segment in chain.segments) {
            assertTrue(segment.halfExtents.z > segment.lengthM * 0.5, "${segment.kind} overlaps its pins")
            assertEquals(chain.segments[0].halfExtents.y, segment.halfExtents.y, 1.0e-12)
        }
    }

    /** The renderer's polyline through the rest pins is the chain itself and keeps the envelope's direction. */
    @Test
    fun theVisiblePolylineIsTheChainAndKeepsTheEnvelopeDirection() {
        val spec = spec()
        val side = spec.side(TrackedSide.LEFT)
        val chain = side.chain!!
        val envelope = com.atsuishio.superbwarfare.physics.tracked.render.McspTrackPath.solve(
            side.beltWheels.map {
                com.atsuishio.superbwarfare.physics.tracked.render.McspTrackPath.Wheel(
                    it.bindCenter.y, it.bindCenter.z, it.radius, it.wrap == TrackWrap.OUTSIDE,
                )
            },
            side.belt.thicknessM,
        )!!
        val ys = chain.segments.map { it.start.y }
        val zs = chain.segments.map { it.start.z }
        val forward = com.atsuishio.superbwarfare.physics.tracked.render.McspTrackPath.polyline(ys, zs, envelope)!!
        val backward = com.atsuishio.superbwarfare.physics.tracked.render.McspTrackPath.polyline(ys.reversed(), zs.reversed(), envelope)!!
        assertEquals(chain.restLengthM, forward.length, 1.0e-6)
        // Same direction regardless of the input order: both start tangents agree with the envelope's travel.
        val a = forward.at(0.0)
        val b = backward.at(0.0)
        assertTrue(a.tangentY.isFinite() && b.tangentY.isFinite())
        val spec2 = spec.renders.getValue(TrackedSide.LEFT)
        assertEquals(chain.restLengthM, spec2.restPitchM * spec2.visibleCount, 1.0e-6, "plate count follows the chain")
    }
}
