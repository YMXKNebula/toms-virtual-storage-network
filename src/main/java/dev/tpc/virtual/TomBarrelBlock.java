package dev.tpc.virtual;

import com.tom.storagemod.block.IInventoryCable;
import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/** Capacity only: deliberately no Container, BlockEntity, ticker or sided item storage. */
public final class TomBarrelBlock extends Block implements IInventoryCable {
    public static final BooleanProperty ACTIVE=BooleanProperty.create("active");
    public TomBarrelBlock(Properties properties){super(properties);registerDefaultState(stateDefinition.any().setValue(ACTIVE,false));}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder){builder.add(ACTIVE);}
    @Override public boolean canConnectFrom(BlockState state,Direction direction){return false;}
    @Override public java.util.List<com.tom.storagemod.util.BlockFace> nextScan(Level level,BlockState state,BlockPos pos){return java.util.List.of();}
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit){
        if(level instanceof ServerLevel server)player.sendOverlayMessage(Component.literal(VirtualNetwork.get(server).describe(pos)));
        return InteractionResult.SUCCESS;
    }
}
