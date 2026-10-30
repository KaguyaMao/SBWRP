package com.atsuishio.superbwarfare.gametest;

import cn.solarmoon.spark_core.physics.SparkPhysics;
import cn.solarmoon.spark_core.physics.body.CollisionGroups;
import cn.solarmoon.spark_core.physics.level.PhysicsLevel;
import com.atsuishio.superbwarfare.entity.misc.SupportTestBodyEntity;
import com.atsuishio.superbwarfare.init.ModEntities;
import com.jme3.bullet.collision.ManifoldPoints;
import com.jme3.bullet.collision.PersistentManifolds;
import com.jme3.bullet.collision.PhysicsCollisionObject;
import com.jme3.bullet.objects.PhysicsRigidBody;
import com.jme3.math.Vector3f;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * End-to-end support checks with a real rigid body entity.
 *
 * The body collides with the height-field support pair only. It therefore
 * cannot be held up by a raw block top or a wedge; if the installed support
 * mesh is missing, it falls. Stone is not classified, so the fixtures use dirt.
 */
@GameTestHolder("superbwarfare_support")
@PrefixGameTestTemplate(false)
public final class SupportFieldGameTests {
    private SupportFieldGameTests() {
    }

    private static AABB activation(double x, double y, double z) {
        double pad = 2.5;
        return new AABB(x - pad, y - pad, z - pad, x + pad, y + pad, z + pad);
    }

    private static void clearArea(GameTestHelper helper, BlockPos origin, int minX, int maxX, int minZ, int maxZ, int maxY) {
        for (int dx = minX; dx <= maxX; dx++) {
            for (int dz = minZ; dz <= maxZ; dz++) {
                for (int dy = 0; dy <= maxY; dy++) {
                    helper.setBlock(origin.offset(dx, dy, dz), Blocks.AIR);
                }
            }
        }
    }

    /** Spawn and wait until the physics thread has actually created the native body. */
    private static SupportTestBodyEntity spawnBody(GameTestHelper helper, double x, double y, double z) {
        SupportTestBodyEntity body = ModEntities.SUPPORT_TEST_BODY.get().create(helper.getLevel());
        helper.assertTrue(body != null, "support test body could not be created");
        body.setPos(x, y, z);
        helper.getLevel().addFreshEntity(body);
        return body;
    }

    private static void assertBodyConfigured(GameTestHelper helper, SupportTestBodyEntity entity) {
        PhysicsRigidBody body = entity.getBody();
        helper.assertTrue(Math.abs(body.getMass() - SupportTestBodyEntity.MASS) < 1.0e-3f,
                "support body mass was overwritten by synched defaults: " + body.getMass());
        helper.assertTrue(Math.abs(body.getFriction() - SupportTestBodyEntity.FRICTION) < 1.0e-3f,
                "support body friction was overwritten by synched defaults: " + body.getFriction());
        helper.assertTrue((body.getCollideWithGroups() & CollisionGroups.SUPPORT_PAIR) == CollisionGroups.SUPPORT_PAIR,
                "support body mask is not the support pair: " + body.getCollideWithGroups());
        helper.assertTrue((body.getCollideWithGroups() & CollisionGroups.TERRAIN) == 0,
                "support body still collides with raw terrain: " + body.getCollideWithGroups());
        helper.assertTrue(body.getCcdMotionThreshold() > 0f,
                "support body CCD threshold was overwritten: " + body.getCcdMotionThreshold());
    }

    /** Upward contact groups for one body, read from the solved manifolds. */
    private static Set<Integer> upwardGroups(PhysicsLevel physics, PhysicsRigidBody body) {
        Set<Integer> groups = new HashSet<>();
        for (long manifold : physics.getWorld().listManifoldIds()) {
            boolean onA = PersistentManifolds.getBodyAId(manifold) == body.nativeId();
            if (!onA && PersistentManifolds.getBodyBId(manifold) != body.nativeId()) continue;
            long otherId = onA ? PersistentManifolds.getBodyBId(manifold) : PersistentManifolds.getBodyAId(manifold);
            PhysicsCollisionObject other = PhysicsCollisionObject.findInstance(otherId);
            if (other == null) continue;
            for (long point : PersistentManifolds.listPointIds(manifold)) {
                Vector3f normal = new Vector3f();
                ManifoldPoints.getNormalWorldOnB(point, normal);
                float up = normal.y * (onA ? 1f : -1f);
                if (up > 0.5f && ManifoldPoints.getAppliedImpulse(point) > 0f) groups.add(other.getCollisionGroup());
            }
        }
        return groups;
    }

    /**
     * Manifolds belong to the physics thread. Queue a read there and publish
     * the result through [out] instead of touching the native space here.
     */
    private static void requestUpwardGroups(PhysicsLevel physics, PhysicsRigidBody body,
                                            java.util.concurrent.atomic.AtomicReference<Set<Integer>> out) {
        out.set(null);
        physics.submitImmediateTask(() -> {
            out.set(upwardGroups(physics, body));
            return kotlin.Unit.INSTANCE;
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void supportBodyRestsOnDirtThenFallsWhenDirtIsRemoved(GameTestHelper helper) {
        PhysicsLevel physics = SparkPhysics.get(helper.getLevel());
        BlockPos relative = new BlockPos(1, 2, 1);
        clearArea(helper, relative, -2, 2, -2, 2, 6);
        helper.setBlock(relative, Blocks.DIRT);
        BlockPos floor = helper.absolutePos(relative);
        double spawnY = floor.getY() + 4.0;
        AABB box = activation(floor.getX() + .5, spawnY, floor.getZ() + .5);

        SupportTestBodyEntity body = spawnBody(helper, floor.getX() + .5, spawnY, floor.getZ() + .5);

        AtomicLong restTick = new AtomicLong(-1);
        java.util.concurrent.atomic.AtomicReference<Set<Integer>> owners = new java.util.concurrent.atomic.AtomicReference<>();
        helper.startSequence()
                .thenWaitUntil(() -> {
                    String issue = physics.getTerrainManager().readinessIssue(box);
                    helper.assertTrue(issue == null, "support terrain not ready: " + issue
                            + " " + physics.getTerrainManager().getStats());
                    Float top = physics.getTerrainManager().installedSupportRawTopAt(floor);
                    helper.assertTrue(top != null && top > floor.getY() + 0.9f,
                            "classified dirt installed no support at the target column top: top=" + top
                                    + " " + physics.getTerrainManager().getStats());
                    helper.assertTrue(body.isBodyReady(), "physics thread never created the support body");
                })
                .thenWaitUntil(() -> {
                    if (restTick.get() < 0) restTick.set(physics.getTickCount());
                    helper.assertTrue(physics.getTickCount() >= restTick.get() + 100,
                            "physics tick did not advance while waiting for rest");
                })
                .thenExecute(() -> {
                    assertBodyConfigured(helper, body);
                    double expected = floor.getY() + 1.0 + SupportTestBodyEntity.HALF;
                    helper.assertTrue(Math.abs(body.getY() - expected) < 0.35,
                            "support body did not rest on the dirt height field: y=" + body.getY()
                                    + ", expected=" + expected
                                    + ", block=" + helper.getLevel().getBlockState(floor));
                })
                .thenExecute(() -> requestUpwardGroups(physics, body.getBody(), owners))
                .thenWaitUntil(() -> helper.assertTrue(owners.get() != null,
                        "physics thread did not publish the contact owners"))
                .thenExecute(() -> helper.assertTrue(
                        !owners.get().isEmpty() && owners.get().stream()
                                .allMatch(group -> group == CollisionGroups.HEIGHT_FIELD),
                        "dirt support was not owned by the height field: " + owners.get()))
                .thenExecute(() -> helper.setBlock(relative, Blocks.AIR))
                .thenWaitUntil(() -> {
                    String issue = physics.getTerrainManager().readinessIssue(box);
                    helper.assertTrue(issue == null, "terrain not ready after dirt removal: " + issue);
                })
                .thenExecute(() -> restTick.set(physics.getTickCount()))
                .thenWaitUntil(() -> helper.assertTrue(
                        physics.getTickCount() >= restTick.get() + 100,
                        "physics tick did not advance after dirt removal"))
                .thenExecute(() -> {
                    helper.assertTrue(body.getY() < floor.getY() + 1.0,
                            "support body stayed on the removed dirt: y=" + body.getY()
                                    + ", block=" + helper.getLevel().getBlockState(floor));
                    body.discard();
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void supportBodyFallsThroughUnclassifiedStone(GameTestHelper helper) {
        PhysicsLevel physics = SparkPhysics.get(helper.getLevel());
        BlockPos relative = new BlockPos(1, 2, 1);
        clearArea(helper, relative, -2, 2, -2, 2, 6);
        helper.setBlock(relative, Blocks.STONE);
        BlockPos floor = helper.absolutePos(relative);
        double spawnY = floor.getY() + 4.0;
        AABB box = activation(floor.getX() + .5, spawnY, floor.getZ() + .5);

        SupportTestBodyEntity body = spawnBody(helper, floor.getX() + .5, spawnY, floor.getZ() + .5);

        AtomicLong startTick = new AtomicLong(-1);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    String issue = physics.getTerrainManager().readinessIssue(box);
                    helper.assertTrue(issue == null, "terrain not ready over stone: " + issue);
                    Float top = physics.getTerrainManager().installedSupportRawTopAt(floor);
                    helper.assertTrue(top == null || top < floor.getY() + 0.9f,
                            "unclassified stone produced support at its own top: top=" + top
                                    + " " + physics.getTerrainManager().getStats());
                    helper.assertTrue(body.isBodyReady(), "physics thread never created the support body");
                })
                .thenExecute(() -> startTick.set(physics.getTickCount()))
                .thenWaitUntil(() -> helper.assertTrue(
                        physics.getTickCount() >= startTick.get() + 100,
                        "physics tick did not advance over stone"))
                .thenExecute(() -> {
                    helper.assertTrue(body.getY() < floor.getY(),
                            "support body was carried by an unclassified stone top: y=" + body.getY()
                                    + ", block=" + helper.getLevel().getBlockState(floor));
                    body.discard();
                })
                .thenSucceed();
    }

    /**
     * A one-block dirt step must be a shaved height-field edge, not a raw block
     * top. The body lands on the high column, follows the slope down, and every
     * upward contact is owned by the height field.
     *
     * The step is placed inside one section on purpose: a step exactly on a
     * section border is covered by the T1 margin-evidence test. Keeping the
     * T3 geometry away from the border makes this test about smoothing, not
     * about which section happened to be loaded.
     */
    @GameTest(template = "empty", timeoutTicks = 500)
    public static void supportBodyFollowsTheShavedOneBlockEdge(GameTestHelper helper) {
        PhysicsLevel physics = SparkPhysics.get(helper.getLevel());
        BlockPos originAbs = helper.absolutePos(BlockPos.ZERO);
        int sectionX = originAbs.getX() & -16;
        int sectionZ = originAbs.getZ() & -16;
        int baseY = originAbs.getY();

        BlockPos lowAbs = new BlockPos(sectionX + 4, baseY, sectionZ + 4);
        BlockPos highAbs = new BlockPos(sectionX + 5, baseY, sectionZ + 4);
        BlockPos lowRel = lowAbs.subtract(originAbs);
        BlockPos highRel = highAbs.subtract(originAbs);

        // Clear the working area, then build a 1-block step at x = sectionX + 5.
        for (int dx = -3; dx <= 9; dx++) {
            for (int dz = -3; dz <= 7; dz++) {
                for (int dy = -1; dy <= 7; dy++) {
                    helper.setBlock(lowRel.offset(dx, dy, dz), Blocks.AIR);
                }
            }
        }
        for (int x = sectionX + 2; x <= sectionX + 4; x++) {
            for (int z = sectionZ + 2; z <= sectionZ + 6; z++) {
                helper.setBlock(new BlockPos(x, baseY, z).subtract(originAbs), Blocks.DIRT);
            }
        }
        for (int x = sectionX + 5; x <= sectionX + 7; x++) {
            for (int z = sectionZ + 2; z <= sectionZ + 6; z++) {
                BlockPos rel = new BlockPos(x, baseY, z).subtract(originAbs);
                helper.setBlock(rel, Blocks.DIRT);
                helper.setBlock(rel.above(), Blocks.DIRT);
            }
        }

        BlockPos highColumn = highAbs;
        BlockPos lowColumn = new BlockPos(sectionX + 4, baseY, sectionZ + 4);
        double spawnY = highColumn.getY() + 4.0;
        AABB box = activation(highColumn.getX() + .5, spawnY, highColumn.getZ() + .5);

        SupportTestBodyEntity body = spawnBody(helper, highColumn.getX() + .5, spawnY, highColumn.getZ() + .5);

        AtomicLong restTick = new AtomicLong(-1);
        java.util.concurrent.atomic.AtomicReference<Set<Integer>> owners = new java.util.concurrent.atomic.AtomicReference<>();
        helper.startSequence()
                .thenWaitUntil(() -> {
                    String issue = physics.getTerrainManager().readinessIssue(box);
                    helper.assertTrue(issue == null, "slope terrain not ready: " + issue
                            + " " + physics.getTerrainManager().getStats());
                    float[] corners = physics.getTerrainManager().installedSupportCornersAt(highColumn);
                    helper.assertTrue(corners != null, "high column installed no support corners at "
                            + highColumn + " (local x=" + (highColumn.getX() & 15) + ")");
                    helper.assertTrue(corners[0] < corners[1] - 0.9f,
                            "the shared edge was not shaved: corners=" + java.util.Arrays.toString(corners)
                                    + " at " + highColumn + " (local x=" + (highColumn.getX() & 15) + ")");
                    float[] lowCorners = physics.getTerrainManager().installedSupportCornersAt(lowColumn);
                    helper.assertTrue(lowCorners != null && Math.abs(lowCorners[0] - lowCorners[1]) < 1.0e-3f,
                            "the low column should stay flat: "
                                    + (lowCorners == null ? "null" : java.util.Arrays.toString(lowCorners)));
                    helper.assertTrue(body.isBodyReady(), "physics thread never created the support body");
                })
                .thenWaitUntil(() -> {
                    if (restTick.get() < 0) restTick.set(physics.getTickCount());
                    helper.assertTrue(physics.getTickCount() >= restTick.get() + 140,
                            "physics tick did not advance while waiting for the slope");
                })
                .thenExecute(() -> {
                    assertBodyConfigured(helper, body);
                    double rawHighRest = highColumn.getY() + 2.0 + SupportTestBodyEntity.HALF;
                    helper.assertTrue(body.getY() < rawHighRest - 0.2,
                            "the raw high block top still carried the body: y=" + body.getY()
                                    + ", raw rest=" + rawHighRest);
                    helper.assertTrue(body.getX() < highColumn.getX() + .5,
                            "the body did not follow the shaved edge toward the low side: x=" + body.getX());
                })
                .thenExecute(() -> requestUpwardGroups(physics, body.getBody(), owners))
                .thenWaitUntil(() -> helper.assertTrue(owners.get() != null,
                        "physics thread did not publish the contact owners"))
                .thenExecute(() -> {
                    helper.assertTrue(!owners.get().isEmpty(), "the body had no solved upward contact");
                    helper.assertTrue(owners.get().stream().allMatch(group -> group == CollisionGroups.HEIGHT_FIELD),
                            "the shaved edge was not owned by the height field: " + owners.get());
                })
                .thenExecute(body::discard)
                .thenSucceed();
    }
}
