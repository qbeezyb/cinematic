package me.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import me.cinematic.block.ScreenBlockEntity;
import me.cinematic.block.ScreenControllerBlock;
import me.cinematic.core.PanelGeometry;
import me.cinematic.core.ScreenKey;
import me.cinematic.network.Payloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.NeoForgeRenderTypes;
import org.joml.Vector3f;

public final class ScreenRenderer implements BlockEntityRenderer<ScreenBlockEntity, ScreenRenderer.State> {
    private static final int FULL_BRIGHT = 0xF000F0;
    private static final Identifier WHITE = Identifier.fromNamespaceAndPath("cinematic", "textures/block/white.png");
    private static final ScreenKey LOCAL = new ScreenKey("cinematic:local", 0, 0, 0);

    public static final class State extends BlockEntityRenderState {
        private Payloads.Snapshot screen;
        private Identifier texture;
        private String status;
        private long position;
        private int facing;
    }

    public ScreenRenderer(BlockEntityRendererProvider.Context context) {}
    @Override public State createRenderState() { return new State(); }
    @Override public int getViewDistance() { return 256; }
    @Override public boolean shouldRenderOffScreen() { return true; }

    private static ScreenKey key(ScreenBlockEntity entity) {
        var pos = entity.getBlockPos();
        return new ScreenKey(entity.getLevel().dimension().identifier().toString(), pos.getX(), pos.getY(), pos.getZ());
    }

    @Override public AABB getRenderBoundingBox(ScreenBlockEntity entity) {
        ScreenKey key = key(entity);
        var entry = CinematicClient.PLAYBACK.get(key);
        int facing = entry == null ? entity.getBlockState().getValue(ScreenControllerBlock.FACING).get2DDataValue() : entry.state().facing();
        int width = entry == null ? 1 : entry.state().width();
        int height = entry == null ? 1 : entry.state().height();
        var a = PanelGeometry.point(key, facing, 0, 0, 0);
        var b = PanelGeometry.point(key, facing, width, height, .04);
        return new AABB(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()),
                Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z())).inflate(.1);
    }

    @Override public boolean shouldRender(ScreenBlockEntity entity, Vec3 camera) {
        return getRenderBoundingBox(entity).getCenter().distanceToSqr(camera) < 256 * 256;
    }

    @Override public void extractRenderState(ScreenBlockEntity entity, State state, float partialTick, Vec3 camera,
            ModelFeatureRenderer.CrumblingOverlay crumbling) {
        BlockEntityRenderer.super.extractRenderState(entity, state, partialTick, camera, crumbling);
        var entry = CinematicClient.PLAYBACK.get(key(entity));
        state.screen = entry == null ? null : entry.state();
        state.texture = entry == null ? null : entry.texture();
        state.status = entry == null ? "Cinematic" : entry.status();
        state.position = entry == null ? 0 : entry.position();
        state.facing = entity.getBlockState().getValue(ScreenControllerBlock.FACING).get2DDataValue();
    }

    @Override public void submit(State state, PoseStack poses, SubmitNodeCollector collector, CameraRenderState camera) {
        var screen = state.screen;
        int width = screen == null ? 1 : screen.width();
        int height = screen == null ? 1 : screen.height();
        int facing = screen == null ? state.facing : screen.facing();
        quad(poses, collector, WHITE, facing, 0, 0, width, height, .015, 0xFF000000);
        if (state.texture != null && screen != null && screen.videoWidth() > 0) {
            var fit = PanelGeometry.fit(width, height, screen.videoWidth(), screen.videoHeight());
            quad(poses, collector, state.texture, facing, fit.x(), fit.y(), fit.width(), fit.height(), .025, 0xFFFFFFFF);
        } else {
            String label = screen != null && !screen.name().isEmpty() ? screen.name() : state.status;
            var font = Minecraft.getInstance().font;
            Component text = Component.literal(font.plainSubstrByWidth(label, 240));
            float scale = (float) Math.min(.06, width * .8 / Math.max(1, font.width(text)));
            var center = PanelGeometry.point(LOCAL, facing, width / 2.0, height * .55, .035);
            poses.pushPose();
            poses.translate(center.x(), center.y(), center.z());
            poses.rotateDegrees(Axis.YP, -90 * facing);
            poses.scale(scale, -scale, scale);
            collector.submitText(poses, -font.width(text) / 2f, 0, text.getVisualOrderText(), false, Font.DisplayMode.NORMAL, FULL_BRIGHT, 0xFFFFFFFF, 0, 0);
            poses.popPose();
            if (screen != null && screen.durationMs() > 0) {
                quad(poses, collector, WHITE, facing, width * .1, height * .35, width * .8, .025, .035, 0xFF343434);
                quad(poses, collector, WHITE, facing, width * .1, height * .35,
                        width * .8 * state.position / screen.durationMs(), .025, .04, 0xFFE6E6E6);
            }
        }
    }

    private static void quad(PoseStack poses, SubmitNodeCollector collector, Identifier texture, int facing,
            double x, double y, double width, double height, double outward, int color) {
        var a = PanelGeometry.point(LOCAL, facing, x, y, outward);
        var b = PanelGeometry.point(LOCAL, facing, x + width, y, outward);
        var c = PanelGeometry.point(LOCAL, facing, x + width, y + height, outward);
        var d = PanelGeometry.point(LOCAL, facing, x, y + height, outward);
        collector.submitCustomGeometry(poses, NeoForgeRenderTypes.getEntityUnlitTranslucent(texture), (pose, vertices) -> {
            vertex(pose, vertices, a, 0, 1, color, facing);
            vertex(pose, vertices, b, 1, 1, color, facing);
            vertex(pose, vertices, c, 1, 0, color, facing);
            vertex(pose, vertices, d, 0, 0, color, facing);
        });
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer vertices, PanelGeometry.Point point, float u, float v, int color, int facing) {
        var position = pose.pose().transformPosition((float) point.x(), (float) point.y(), (float) point.z(), new Vector3f());
        var normal = switch (facing) {
            case 0 -> new Vector3f(0, 0, 1);
            case 1 -> new Vector3f(-1, 0, 0);
            case 2 -> new Vector3f(0, 0, -1);
            default -> new Vector3f(1, 0, 0);
        };
        pose.transformNormal(normal, normal);
        vertices.addVertex(position.x(), position.y(), position.z(), color, u, v, OverlayTexture.NO_OVERLAY, FULL_BRIGHT, normal.x(), normal.y(), normal.z());
    }
}
