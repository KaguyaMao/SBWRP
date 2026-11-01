package com.atsuishio.superbwarfare.client.debug

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.physics.tracked.TrackedCollisionDebug
import com.atsuishio.superbwarfare.physics.tracked.TrackedTerrainDebugMesh
import com.atsuishio.superbwarfare.tools.mc
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.RenderType
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.ClientPlayerNetworkEvent
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import kotlin.math.sqrt

/**
 * Draws the installed Bullet terrain mesh as its real triangle edges.
 *
 * Orange edges, with a white normal, are generated ramps. Cyan is a flat
 * floor, red is a wall, and magenta is a downward face. The lines are the
 * mesh vertices, not a height query.
 */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(
    modid = Mod.MODID,
    bus = net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus.FORGE,
    value = [Dist.CLIENT],
)
object TrackedTerrainDebugRenderer {
    @SubscribeEvent
    fun onLogout(event: ClientPlayerNetworkEvent.LoggingOut) {
        TrackedTerrainDebugClient.clearAll()
    }

    @SubscribeEvent
    fun onRender(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return
        if (TrackedTerrainDebugClient.meshCount() == 0 && TrackedTerrainDebugClient.shapeCount() == 0) return
        val poseStack = event.poseStack
        val camera = event.camera.position
        val buffer = mc.renderBuffers().bufferSource()
        val lines = buffer.getBuffer(RenderType.lines())
        poseStack.pushPose()
        poseStack.translate(-camera.x, -camera.y, -camera.z)
        TrackedTerrainDebugClient.forEach { mesh -> draw(poseStack, lines, mesh) }
        val partialTick = event.partialTick
        val level = mc.level
        TrackedTerrainDebugClient.forEachShape { shape ->
            val vehicle = level?.getEntity(shape.entityId)
                as? com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity ?: return@forEachShape
            drawShape(poseStack, lines, shape, vehicle.getVehicleTransform(partialTick))
        }
        poseStack.popPose()
        buffer.endBatch(RenderType.lines())
    }

    private fun draw(
        poseStack: PoseStack,
        lines: VertexConsumer,
        mesh: TrackedTerrainDebugClient.RenderMesh,
    ) {
        val width = TrackedTerrainDebugMesh.FLOATS_PER_TRIANGLE
        val values = mesh.triangles
        var cursor = 0
        while (cursor + width <= values.size) {
            val ax = values[cursor]
            val ay = values[cursor + 1]
            val az = values[cursor + 2]
            val bx = values[cursor + 3]
            val by = values[cursor + 4]
            val bz = values[cursor + 5]
            val cx = values[cursor + 6]
            val cy = values[cursor + 7]
            val cz = values[cursor + 8]
            val ramp = values[cursor + 9] > 0.5f
            cursor += width
            val nx = (by - ay) * (cz - az) - (bz - az) * (cy - ay)
            val ny = (bz - az) * (cx - ax) - (bx - ax) * (cz - az)
            val nz = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
            val length = sqrt(nx * nx + ny * ny + nz * nz)
            if (length < 1.0e-5f) continue
            val color = colorOf(ramp, ny / length)
            edge(poseStack, lines, ax, ay, az, bx, by, bz, color)
            edge(poseStack, lines, bx, by, bz, cx, cy, cz, color)
            edge(poseStack, lines, cx, cy, cz, ax, ay, az, color)
            if (ramp) {
                val mx = (ax + bx + cx) / 3f
                val my = (ay + by + cy) / 3f
                val mz = (az + bz + cz) / 3f
                edge(
                    poseStack, lines, mx, my, mz,
                    mx + nx / length * 0.35f,
                    my + ny / length * 0.35f,
                    mz + nz / length * 0.35f,
                    floatArrayOf(1f, 1f, 1f),
                )
            }
        }
    }

    /**
     * Hull boxes are yellow, wheel cylinders white, run boxes green, turret and
     * barrel blue.
     *
     * These are the shapes installed in Bullet, relative to the chassis body.
     * They are placed with the vehicle's interpolated render transform, so they
     * move with the drawn model. They are not the entity hurtbox.
     */
    private fun drawShape(
        poseStack: PoseStack,
        lines: VertexConsumer,
        shape: TrackedTerrainDebugClient.ShapeView,
        transform: org.joml.Matrix4d,
    ) {
        val width = TrackedCollisionDebug.FLOATS_PER_SEGMENT
        val values = shape.segments
        val origin = shape.origin
        val a = org.joml.Vector3d()
        val b = org.joml.Vector3d()
        var cursor = 0
        while (cursor + width <= values.size) {
            val kind = values[cursor + 6]
            val color = when {
                kind > 3.5f -> floatArrayOf(0.35f, 0.6f, 1f)
                kind > 2.5f -> floatArrayOf(0.2f, 0.95f, 0.35f)
                kind > 1.5f -> floatArrayOf(0.92f, 0.92f, 0.95f)
                else -> floatArrayOf(1f, 0.86f, 0.15f)
            }
            a.set(
                (origin[0] + values[cursor]).toDouble(),
                (origin[1] + values[cursor + 1]).toDouble(),
                (origin[2] + values[cursor + 2]).toDouble(),
            )
            b.set(
                (origin[0] + values[cursor + 3]).toDouble(),
                (origin[1] + values[cursor + 4]).toDouble(),
                (origin[2] + values[cursor + 5]).toDouble(),
            )
            transform.transformPosition(a)
            transform.transformPosition(b)
            edge(
                poseStack, lines,
                a.x.toFloat(), a.y.toFloat(), a.z.toFloat(),
                b.x.toFloat(), b.y.toFloat(), b.z.toFloat(),
                color,
            )
            cursor += width
        }
    }

    private fun colorOf(ramp: Boolean, upward: Float): FloatArray = when {
        ramp -> floatArrayOf(1f, 0.62f, 0.08f)
        upward > 0.85f -> floatArrayOf(0.15f, 0.85f, 0.9f)
        upward > 0.25f -> floatArrayOf(0.95f, 0.82f, 0.15f)
        upward < -0.25f -> floatArrayOf(0.72f, 0.2f, 0.95f)
        else -> floatArrayOf(0.95f, 0.22f, 0.18f)
    }

    private fun edge(
        poseStack: PoseStack,
        buffer: VertexConsumer,
        x0: Float,
        y0: Float,
        z0: Float,
        x1: Float,
        y1: Float,
        z1: Float,
        color: FloatArray,
    ) {
        val dx = x1 - x0
        val dy = y1 - y0
        val dz = z1 - z0
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        if (length < 1.0e-5f) return
        val pose = poseStack.last()
        buffer.vertex(pose.pose(), x0, y0, z0)
            .color(color[0], color[1], color[2], 1f)
            .normal(pose.normal(), dx / length, dy / length, dz / length)
            .endVertex()
        buffer.vertex(pose.pose(), x1, y1, z1)
            .color(color[0], color[1], color[2], 1f)
            .normal(pose.normal(), dx / length, dy / length, dz / length)
            .endVertex()
    }
}
