package me.cinematic.network;

import me.cinematic.server.CinemaServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Common registration contains no references to client classes, including handler lambdas. */
public final class CinemaNetwork {
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("2");
        registrar.playToServer(Payloads.Request.TYPE, Payloads.Request.CODEC, (packet, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                var server = CinemaServer.get(player.level().getServer());
                if (server != null) { server.request(player, packet); }
            }
        });
        registrar.playToServer(Payloads.Control.TYPE, Payloads.Control.CODEC, (packet, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                var server = CinemaServer.get(player.level().getServer());
                if (server != null) { server.control(player, packet); }
            }
        });
        registrar.playToClient(Payloads.Snapshots.TYPE, Payloads.Snapshots.CODEC);
        registrar.playToClient(Payloads.AudioTracks.TYPE, Payloads.AudioTracks.CODEC);
        registrar.playToClient(Payloads.Library.TYPE, Payloads.Library.CODEC);
        registrar.playToClient(Payloads.Access.TYPE, Payloads.Access.CODEC);
        registrar.playToClient(Payloads.Clock.TYPE, Payloads.Clock.CODEC);
        registrar.playToClient(Payloads.Open.TYPE, Payloads.Open.CODEC);
        registrar.playToClient(Payloads.Notice.TYPE, Payloads.Notice.CODEC);
    }
    private CinemaNetwork() {}
}
