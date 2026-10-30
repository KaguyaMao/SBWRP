package com.atsuishio.superbwarfare.client.debug

import cn.solarmoon.spark_core.physics.terrain.support.SupportDebugCell
import cn.solarmoon.spark_core.physics.terrain.support.SupportDebugStatus
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderType
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod

/**
 * Faces and lines of the installed height-field collision patch.
 * The two triangles use the same corner order as TerrainSupportBodies, so a
 * shaved edge is a real slope, not a flat cell grid.
 */
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE, value = [Dist.CLIENT])
object HeightFieldDebugState {
    private const val LIFT = 0.025f

    @Volatile
    private var enabled = false
    private var cells: List<SupportDebugCell> = emptyList()

    fun replace(nextEnabled: Boolean, next: List<SupportDebugCell>) {
        enabled = nextEnabled
        cells = if (nextEnabled) next else emptyList()
    }

    @SubscribeEvent
    fun render(event: RenderLevelStageEvent) {
        if (!enabled || event.stage != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return
        val snapshot = cells
        if (snapshot.isEmpty()) return
        val pose = event.poseStack
        val camera = event.camera.position
        val buffers = Minecraft.getInstance().renderBuffers().bufferSource()
        val surface = buffers.getBuffer(SupportFieldRenderTypes.SURFACE)
        val lines = buffers.getBuffer(RenderType.lines())
        pose.pushPose()
        pose.translate(-camera.x, -camera.y, -camera.z)
        for (cell in snapshot) {
            when (cell.status) {
                SupportDebugStatus.SOFT, SupportDebugStatus.HARD -> {
                    val hard = cell.status == SupportDebugStatus.HARD
                    val red = if (hard) 0.25f else 0.22f
                    val green = if (hard) 0.72f else 0.82f
                    val blue = if (hard) 0.95f else 0.30f
                    patch(pose, surface, lines, cell, red, green, blue)
                }
                SupportDebugStatus.INVALID -> marker(pose, lines, cell, 1f, 0.25f, 0.2f)
                // Missing means "outside the loaded support field", which is
                // expected at range edges. Drawing it would imply a surface.
                SupportDebugStatus.MISSING -> Unit
            }
        }
        pose.popPose()
        buffers.endBatch(SupportFieldRenderTypes.SURFACE)
        buffers.endBatch(RenderType.lines())
    }

    private fun marker(
        pose: PoseStack,
        lines: VertexConsumer,
        cell: SupportDebugCell,
        red: Float,
        green: Float,
        blue: Float,
    ) {
        val x = cell.x.toFloat() + 0.5f
        val z = cell.z.toFloat() + 0.5f
        val y = cell.y00 + LIFT
        val r = 0.18f
        line(pose, lines, floatArrayOf(x - r, y, z - r), floatArrayOf(x + r, y, z + r), red, green, blue)
        line(pose, lines, floatArrayOf(x - r, y, z + r), floatArrayOf(x + r, y, z - r), red, green, blue)
    }

    private fun patch(
        pose: PoseStack,
        surface: VertexConsumer,
        lines: VertexConsumer,
        cell: SupportDebugCell,
        red: Float,
        green: Float,
        blue: Float,
    ) {
        val x0 = cell.x.toFloat()
        val z0 = cell.z.toFloat()
        val p00 = vertex(x0, cell.y00, z0)
        val p01 = vertex(x0, cell.y01, z0 + 1f)
        val p10 = vertex(x0 + 1f, cell.y10, z0)
        val p11 = vertex(x0 + 1f, cell.y11, z0 + 1f)
        triangle(pose, surface, p00, p01, p10, red, green, blue, 0.62f)
        triangle(pose, surface, p10, p01, p11, red, green, blue, 0.62f)
        // Edges and a midline on each collision triangle, so the slope is readable.
        line(pose, lines, p00, p01, 1f, 1f, 0.85f)
        line(pose, lines, p01, p10, 1f, 1f, 0.85f)
        line(pose, lines, p10, p00, 1f, 1f, 0.85f)
        line(pose, lines, p10, p11, 1f, 1f, 0.85f)
        line(pose, lines, p11, p01, 1f, 1f, 0.85f)
        line(pose, lines, mid(p00, p10), mid(p01, p11), red, green, blue)
        line(pose, lines, mid(p00, p01), mid(p10, p11), red, green, blue)
    }

    private fun vertex(x: Float, y: Float, z: Float) = floatArrayOf(x, y + LIFT, z)

    private fun mid(a: FloatArray, b: FloatArray) = floatArrayOf(
        (a[0] + b[0]) * 0.5f,
        (a[1] + b[1]) * 0.5f,
        (a[2] + b[2]) * 0.5f,
    )

    private fun triangle(
        pose: PoseStack,
        buffer: VertexConsumer,
        a: FloatArray,
        b: FloatArray,
        c: FloatArray,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
    ) {
        val matrix = pose.last().pose()
        buffer.vertex(matrix, a[0], a[1], a[2]).color(red, green, blue, alpha).endVertex()
        buffer.vertex(matrix, b[0], b[1], b[2]).color(red, green, blue, alpha).endVertex()
        buffer.vertex(matrix, c[0], c[1], c[2]).color(red, green, blue, alpha).endVertex()
    }

    private fun line(
        pose: PoseStack,
        lines: VertexConsumer,
        a: FloatArray,
        b: FloatArray,
        red: Float,
        green: Float,
        blue: Float,
    ) {
        val matrix = pose.last().pose()
        val normal = pose.last().normal()
        lines.vertex(matrix, a[0], a[1], a[2]).color(red, green, blue, 1f).normal(normal, 0f, 1f, 0f).endVertex()
        lines.vertex(matrix, b[0], b[1], b[2]).color(red, green, blue, 1f).normal(normal, 0f, 1f, 0f).endVertex()
    }
}
