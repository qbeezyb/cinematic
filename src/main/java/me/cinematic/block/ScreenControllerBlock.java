package me.cinematic.block;

import me.cinematic.server.CinemaServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

public final class ScreenControllerBlock extends Block implements EntityBlock {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    public ScreenControllerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        // Continue the orientation of the face being built, including side/above placements.
        BlockState neighbor = context.getLevel().getBlockState(context.getClickedPos().relative(context.getClickedFace().getOpposite()));
        if (neighbor.is(this)) { return defaultBlockState().setValue(FACING, neighbor.getValue(FACING)); }
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new ScreenBlockEntity(pos, state); }

    @Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel serverLevel) {
            var service = CinemaServer.get(serverLevel.getServer());
            if (service != null) {
                if (oldState.is(this) && oldState.getValue(FACING) != state.getValue(FACING)) {
                    service.blocksChanged(serverLevel, pos, oldState.getValue(FACING).get2DDataValue());
                }
                service.blocksChanged(serverLevel, pos, state.getValue(FACING).get2DDataValue());
            }
        }
    }

    @Override protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        var service = CinemaServer.get(level.getServer());
        if (service != null && !level.getBlockState(pos).is(this)) { service.blocksChanged(level, pos, state.getValue(FACING).get2DDataValue()); }
    }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer) {
            CinemaServer service = CinemaServer.get(serverPlayer.level().getServer());
            if (service != null) { service.open(serverPlayer, pos); }
        }
        return InteractionResult.SUCCESS;
    }
}
