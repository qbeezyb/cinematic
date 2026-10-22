package me.cinematic.client;

import me.cinematic.Cinematic;
import me.cinematic.network.Payloads;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = Cinematic.ID, dist = Dist.CLIENT)
public final class CinematicClient {
    public static final ClientPlayback PLAYBACK = new ClientPlayback();

    public CinematicClient(IEventBus bus, ModContainer container) {
        bus.addListener(CinematicClient::payloads);
        bus.addListener((EntityRenderersEvent.RegisterRenderers event) -> event.registerBlockEntityRenderer(Cinematic.SCREEN_ENTITY.get(), ScreenRenderer::new));
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> PLAYBACK.tick());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> PLAYBACK.reset());
    }

    private static void payloads(RegisterClientPayloadHandlersEvent event) {
        event.register(Payloads.Snapshots.TYPE, (packet, context) -> PLAYBACK.snapshots(packet));
        event.register(Payloads.Access.TYPE, (packet, context) -> PLAYBACK.access(packet));
        event.register(Payloads.Clock.TYPE, (packet, context) -> PLAYBACK.clock(packet));
        event.register(Payloads.Open.TYPE, (packet, context) -> {
            PLAYBACK.remote.open(packet.initial());
            Minecraft.getInstance().gui.setScreen(packet.initial() == null ? new ScreenPickerScreen(null) : new CinemaScreen(packet.initial().id()));
        });
        event.register(Payloads.ScreenList.TYPE, (packet, context) -> {
            if (Minecraft.getInstance().gui.screen() instanceof ScreenPickerScreen screen) { screen.screens(packet); }
        });
        event.register(Payloads.ScreenState.TYPE, (packet, context) -> {
            var current = Minecraft.getInstance().gui.screen();
            if (current instanceof CinemaScreen screen) { screen.remoteState(packet); }
            else if (current instanceof ScreenPickerScreen screen) { screen.selected(packet); }
        });
        event.register(Payloads.AudioTracks.TYPE, (packet, context) -> {
            if (Minecraft.getInstance().gui.screen() instanceof CinemaScreen screen) { screen.audioTracks(packet); }
        });
        event.register(Payloads.Library.TYPE, (packet, context) -> {
            if (Minecraft.getInstance().gui.screen() instanceof CinemaScreen screen) { screen.library(packet); }
        });
        event.register(Payloads.Notice.TYPE, (packet, context) -> {
            if (Minecraft.getInstance().gui.screen() instanceof CinemaScreen screen) { screen.notice(packet); }
        });
    }
}
