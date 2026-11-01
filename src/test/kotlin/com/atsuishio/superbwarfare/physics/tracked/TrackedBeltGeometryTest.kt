package com.atsuishio.superbwarfare.physics.tracked

import com.jme3.math.Vector3f
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrackedBeltGeometryTest {

    @Test
    fun mergeRequiresStraightPinsAndRepresentableMaterialLength() {
        val straight = listOf(Vector3f(), Vector3f(0f, 0f, 1f))
        assertTrue(
            TrackedBeltGeometry.canMerge(
                straightSeconds = 0.5,
                holdSeconds = 0.5,
                bendRad = 0.0,
                mergeRad = Math.toRadians(1.0),
                materialLengths = listOf(1.0),
                points = straight,
            ),
        )
        assertFalse(
            TrackedBeltGeometry.canMerge(
                straightSeconds = 10.0,
                holdSeconds = 0.5,
                bendRad = 0.0,
                mergeRad = Math.toRadians(1.0),
                materialLengths = listOf(1.0),
                points = listOf(Vector3f(), Vector3f(0f, 0f, 0.98f)),
            ),
            "elapsed time must not make inextensible material fit an incompatible chord",
        )
        assertFalse(
            TrackedBeltGeometry.canMerge(
                straightSeconds = 10.0,
                holdSeconds = 0.5,
                bendRad = Math.toRadians(5.0),
                mergeRad = Math.toRadians(1.0),
                materialLengths = listOf(1.0),
                points = listOf(Vector3f(), Vector3f(0f, 0f, 1f)),
            ),
            "a straight chord alone cannot authorize merging a bent pin",
        )
    }

    @Test
    fun mergeWaitsForTheAuthoredStraightHold() {
        val points = listOf(Vector3f(), Vector3f(0f, 0f, 1f))
        for ((elapsed, expected) in listOf(0.49 to false, 0.50 to true)) {
            assertTrue(
                TrackedBeltGeometry.canMerge(
                    straightSeconds = elapsed,
                    holdSeconds = 0.5,
                    bendRad = 0.0,
                    mergeRad = Math.toRadians(1.0),
                    materialLengths = listOf(1.0),
                    points = points,
                ) == expected,
            )
        }
    }

    @Test
    fun everyRebuiltPieceMustIndependentlyMatchItsMaterialLength() {
        assertTrue(
            TrackedBeltGeometry.isRepresentable(
                materialLengths = listOf(0.5, 0.5, 0.5),
                points = listOf(
                    Vector3f(),
                    Vector3f(0f, 0f, 0.5f),
                    Vector3f(0f, 0f, 1.0f),
                    Vector3f(0f, 0f, 1.5f),
                ),
            ),
            "exactly fitted material spans remain representable",
        )
        assertFalse(
            TrackedBeltGeometry.isRepresentable(
                materialLengths = listOf(0.5, 0.5, 0.5),
                points = listOf(
                    Vector3f(),
                    Vector3f(0f, 0f, 0.5f),
                    Vector3f(0f, 0f, 0.9998f),
                    Vector3f(0f, 0f, 1.4998f),
                ),
            ),
            "an interior chord mismatch cannot be borrowed from either exterior pin",
        )
    }

    @Test
    fun measuredResidualOnlyClosesOneMergedSpanWithoutLengtheningMaterial() {
        val endpoints = listOf(Vector3f(), Vector3f(0f, 0f, 0.9998f))
        assertTrue(
            TrackedBeltGeometry.fitPolyline(
                materialLengths = listOf(1.0),
                desiredPoints = endpoints,
                maxBendRad = Math.toRadians(40.0),
                endpointSlackM = 0.0002,
            ) != null,
            "one merge may consume the measured outer-pin residual once while retaining material length",
        )
        val fitted = TrackedBeltGeometry.fitPolyline(
            materialLengths = listOf(0.5, 0.5),
            desiredPoints = listOf(
                Vector3f(),
                Vector3f(0f, 0f, 0.4998f),
                Vector3f(0f, 0f, 0.9996f),
            ),
            maxBendRad = Math.toRadians(40.0),
            endpointSlackM = 0.0002,
        )
        assertTrue(fitted != null, "the internal split pin can be solved geometrically")
        assertTrue(
            TrackedBeltGeometry.isRepresentable(listOf(0.5, 0.5), fitted!!),
            "both rebuilt spans retain their authored material length after the pin fit",
        )
        assertFalse(
            TrackedBeltGeometry.isRepresentable(
                materialLengths = listOf(0.5, 0.5, 0.5),
                points = listOf(
                    Vector3f(),
                    Vector3f(0f, 0f, 0.4998f),
                    Vector3f(0f, 0f, 0.9996f),
                    Vector3f(0f, 0f, 1.4994f),
                ),
            ),
            "a residual at the outer pins cannot be added to each rebuilt segment length",
        )
    }
}
