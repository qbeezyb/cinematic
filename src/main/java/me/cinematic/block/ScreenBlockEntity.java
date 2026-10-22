package me.cinematic.block;

import me.cinematic.Cinematic;
import me.cinematic.server.CinemaServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class ScreenBlockEntity extends BlockEntity {
    public ScreenBlockEntity(BlockPos pos, BlockState state) { super(Cinematic.SCREEN_ENTITY.get(), pos, state); }

    @Override public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            var service = CinemaServer.get(serverLevel.getServer());
            if (service != null) {
                service.blockLoaded(serverLevel, worldPosition, getBlockState().getValue(ScreenControllerBlock.FACING).get2DDataValue());
            }
        }
    }
}
