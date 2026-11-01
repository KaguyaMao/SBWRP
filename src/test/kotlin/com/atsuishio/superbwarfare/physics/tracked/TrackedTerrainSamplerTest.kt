package com.atsuishio.superbwarfare.physics.tracked

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrackedTerrainSamplerTest {
    @Test
    fun yawRotatesAndNeverShrinksTheRunningGearWindow() {
        val envelope = TerrainEnvelope(-3.0, 3.0, -1.0, 1.0, -1.0, 1.0)
        val terrain = CompiledTerrain(false, 1.0, 1.0, 10, 3, 4)
        val straight = TrackedTerrainSampler.window(Vec3.ZERO, envelope, terrain)
        val shifted = TrackedTerrainSampler.window(Vec3(0.1, 0.0, -0.1), envelope, terrain)
        assertTrue(straight.minX <= envelope.minX && straight.maxX >= envelope.maxX)
        assertTrue(straight.minZ <= envelope.minZ && straight.maxZ >= envelope.maxZ)
        assertEquals(straight, shifted, "small movements inside the snap cell keep the same mesh window")
    }

    @Test
    fun anEnvelopeThatExceedsWindowRadiusIsRejectedInsteadOfClipped() {
        val envelope = TerrainEnvelope(-7.0, 7.0, -1.0, 1.0, -1.0, 1.0)
        val terrain = CompiledTerrain(false, 1.0, 1.0, 6, 3, 4)
        val error = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            TrackedTerrainSampler.window(Vec3.ZERO, envelope, terrain)
        }
        assertTrue(error.message.orEmpty().contains("WindowRadius"))
    }

    /**
     * While [TrackedTerrainSampler.covers] holds, the installed window still
     * contains the whole running gear plus the ramp look-ahead in every yaw.
     */
    @Test
    fun aCoveringWindowContainsTheGearAndTheRampLookahead() {
        val envelope = TerrainEnvelope(-2.2, 2.2, -1.2, 1.2, -4.3, 4.3)
        val terrain = CompiledTerrain(true, 1.0, 3.0, 12, 3, 4)
        val window = TrackedTerrainSampler.window(Vec3.ZERO, envelope, terrain)
        val reach = kotlin.math.hypot(2.2, 4.3 + 3.0 + TrackedTerrainSampler.RAMP_LOOKAHEAD)
        val limit = TrackedTerrainSampler.COVER_DISTANCE
        for (step in 0 until 16) {
            val angle = step * Math.PI / 8.0
            val center = Vec3(kotlin.math.cos(angle) * limit, 0.0, kotlin.math.sin(angle) * limit)
            assertTrue(TrackedTerrainSampler.covers(Vec3.ZERO, center))
            assertTrue(window.minX <= center.x - reach && window.maxX >= center.x + reach)
            assertTrue(window.minZ <= center.z - reach && window.maxZ >= center.z + reach)
        }
        assertTrue(window.minY <= envelope.minY - TrackedTerrainSampler.COVER_HEIGHT)
    }

    /** The window moves only after a real displacement, and never freezes before coverage ends. */
    @Test
    fun rebuildIsRequestedBeforeCoverageEnds() {
        val origin = Vec3.ZERO
        assertTrue(!TrackedTerrainSampler.wantsRebuild(origin, Vec3(1.5, 0.3, 0.5)))
        assertTrue(TrackedTerrainSampler.wantsRebuild(origin, Vec3(2.1, 0.0, 0.0)))
        assertTrue(TrackedTerrainSampler.covers(origin, Vec3(2.1, 0.0, 0.0)))
        assertTrue(!TrackedTerrainSampler.covers(origin, Vec3(3.1, 0.0, 0.0)))
        assertTrue(TrackedTerrainSampler.REBUILD_DISTANCE < TrackedTerrainSampler.COVER_DISTANCE)
        assertTrue(TrackedTerrainSampler.REBUILD_HEIGHT < TrackedTerrainSampler.COVER_HEIGHT)
    }
}
