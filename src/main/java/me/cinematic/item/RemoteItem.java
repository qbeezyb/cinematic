package me.cinematic.item;

import me.cinematic.Cinematic;
import me.cinematic.server.CinemaServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** The block hook consumes the interaction before chests or other block menus can open. */
public final class RemoteItem extends Item {
    public RemoteItem(Properties properties) { super(properties); }

    @Override public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) {
            var service = CinemaServer.get(serverPlayer.level().getServer());
            if (service != null) { service.openRemote(serverPlayer); }
        }
        return InteractionResult.SUCCESS;
    }

    @Override public InteractionResult useOn(UseOnContext context) {
        return use(context.getLevel(), context.getPlayer(), context.getHand());
    }

    public static void useBlock(PlayerInteractEvent.RightClickBlock event) {
        var player = event.getEntity();
        if (!player.getMainHandItem().is(Cinematic.REMOTE.get()) && !player.getOffhandItem().is(Cinematic.REMOTE.get())) { return; }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (player instanceof ServerPlayer serverPlayer) {
            var service = CinemaServer.get(serverPlayer.level().getServer());
            if (service != null) {
                if (event.getLevel().getBlockState(event.getPos()).is(Cinematic.CONTROLLER.get())) { service.open(serverPlayer, event.getPos()); }
                else { service.openRemote(serverPlayer); }
            }
        }
    }
}
