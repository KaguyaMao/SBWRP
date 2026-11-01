package com.atsuishio.superbwarfare.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate

/**
 * Focused entry points for terrain/drive regressions. Each test uses its own
 * template namespace, so `-PgameTestNamespaces=<namespace>` registers exactly
 * one original scenario in a fresh GameTest server/world.
 */
@GameTestHolder("superbwarfare")
@PrefixGameTestTemplate(false)
object TrackedTerrainIsolatedGameTests {
    @JvmStatic
    @GameTest(template = "empty", templateNamespace = "superbwarfare_physics", batch = "focused-forward-drive", timeoutTicks = 900)
    fun forwardDrive(helper: GameTestHelper) =
        TrackedTerrainAndDriveGameTests.contactMotionDrivesTheHullForward(helper)

    @JvmStatic
    @GameTest(template = "empty", templateNamespace = "superbwarfare_physics", batch = "focused-parked-creep", timeoutTicks = 2000)
    fun parkedCreep(helper: GameTestHelper) =
        TrackedTerrainAndDriveGameTests.aParkedVehicleDoesNotCreep(helper)

    @JvmStatic
    @GameTest(template = "empty", templateNamespace = "superbwarfare_physics", batch = "focused-right-steer", timeoutTicks = 900)
    fun rightSteer(helper: GameTestHelper) =
        TrackedTerrainAndDriveGameTests.steeringYawsTheHull(helper)

    @JvmStatic
    @GameTest(template = "empty", templateNamespace = "superbwarfare_physics", batch = "focused-left-steer", timeoutTicks = 900)
    fun leftSteer(helper: GameTestHelper) =
        TrackedTerrainAndDriveGameTests.aLeftSteerYawsTheOtherWay(helper)

    @JvmStatic
    @GameTest(template = "empty", templateNamespace = "superbwarfare_physics", batch = "focused-flat-drive-stability", timeoutTicks = 1200)
    fun flatDriveStability(helper: GameTestHelper) =
        TrackedTerrainAndDriveGameTests.flatDriveKeepsSuspensionAndBeltTopologyStable(helper)
}
