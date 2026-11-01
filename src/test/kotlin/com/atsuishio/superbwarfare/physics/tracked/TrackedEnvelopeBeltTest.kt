package com.atsuishio.superbwarfare.physics.tracked

import com.atsuishio.superbwarfare.physics.tracked.render.McspTrackPath
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/** T0: the envelope belt follows the wheels, not the anchors. */
class TrackedEnvelopeBeltTest {

    private fun spec(): TrackedVehicleSpec = Json { ignoreUnknownKeys = true; isLenient = true }.let { json ->
        TrackedVehicleCompiler.compile(
            "superbwarfare:ztz_99a",
            json.decodeFromString(
                TrackedVehicleDefinition.serializer(),
                File("src/main/resources/data/superbwarfare/sbw/tracked/ztz_99a.json").readText(),
            ),
            File("src/main/resources/assets/superbwarfare/models/bedrock/vehicle/ztz_99a.geo.json").readText(),
        ).requireSpec()
    }

    @Test
    fun theEnvelopeWrapsTheWheelsAndClosesWithoutGaps() {
        val spec = spec()
        for (side in TrackedSide.entries) {
            val compiled = spec.side(side)
            assertNotNull(compiled.envelope, "$side compiles the envelope belt")
            val belt = compiled.envelope!!
            assertTrue(compiled.physicalRuns.isEmpty(), "$side builds no core runs")
            val half = belt.thicknessM * 0.5
            val wheels = compiled.wheels.associateBy { it.index }
            fun onCircle(point: net.minecraft.world.phys.Vec3, wheel: Int) {
                val w = wheels.getValue(wheel)
                val c = belt.wheelCenters.getValue(wheel)
                assertEquals(w.radius + half, hypot(point.y - c.y, point.z - c.z), 1.0e-9,
                    "$side point $point sits on ${w.bone} at r + t/2")
            }
            // Spans meet end to end, and the free ends ride on the guide wheels.
            assertEquals(0.0, belt.front.end.distanceTo(belt.bottom.start), 1.0e-12)
            assertEquals(0.0, belt.bottom.end.distanceTo(belt.rear.start), 1.0e-12)
            onCircle(belt.front.start, belt.idlerWheel)
            onCircle(belt.rear.end, belt.driveWheel)
            onCircle(belt.top.start, belt.driveWheel)
            onCircle(belt.top.end, belt.idlerWheel)
            assertEquals(compiled.wheels.first { it.role == TrackWheelRole.IDLER_GUIDE }.index, belt.idlerWheel)
            assertEquals(compiled.wheels.first { it.role == TrackWheelRole.DRIVE_GUIDE }.index, belt.driveWheel)
            // Wrap chords: their ends are on the r + t/2 circle.
            for ((wheel, pieces) in belt.wraps) {
                val w = wheels.getValue(wheel)
                for (piece in pieces) {
                    val mid = hypot(piece.center.y, piece.center.z)
                    val end = hypot(mid, piece.halfLengthM)
                    assertEquals(w.radius + half, end, 1.0e-9)
                }
            }
            // Road wheels sit r + t/2 above the bottom line.
            for (foot in belt.roadFeet) {
                val w = wheels.getValue(foot.wheelIndex)
                assertEquals(w.radius + half, belt.wheelCenters.getValue(w.index).y - foot.foot.y, 0.02, "${w.bone} rides on the bottom")
            }
            val path = McspTrackPath.solve(compiled.beltWheels.map {
                val c = belt.wheelCenters.getValue(it.index)
                McspTrackPath.Wheel(c.y, c.z, it.radius, it.wrap == TrackWrap.OUTSIDE)
            }, compiled.belt.thicknessM)!!
            println("$side envelope spans front=${belt.front.start}->${belt.front.end} rear=${belt.rear.start}->${belt.rear.end} top=${belt.top.start}->${belt.top.end} centers=${belt.wheelCenters}")
            println("$side envelope rest=${"%.4f".format(belt.restLengthM)} m mcsp=${"%.4f".format(path.length)} m " +
                "bottom=${"%.3f".format(belt.bottom.lengthM)} front=${"%.3f".format(belt.front.lengthM)} " +
                "rear=${"%.3f".format(belt.rear.lengthM)} top=${"%.3f".format(belt.top.lengthM)} " +
                "anchors(mm)=${belt.anchorDeviationsM.map { "%.0f".format(it * 1000) }}")
            assertTrue(abs(belt.restLengthM - path.length) < 0.05, "$side rest length follows the wheel envelope")
        }
    }
}
