package dev.tpc.mixin;

import dev.tpc.PersistentCables;
import com.tom.storagemod.block.IInventoryCable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class ChunkMutationMixin {
    @Inject(method="setBlockState",at=@At("HEAD"))
    private void tpc$beforeChange(BlockPos pos,BlockState state,int flags,CallbackInfoReturnable<BlockState> cir){
        if(PersistentCables.enabled() && ((LevelChunk)(Object)this).getLevel() instanceof ServerLevel level){
            var old=((LevelChunk)(Object)this).getBlockState(pos);
            if(old.getBlock()==dev.tpc.virtual.VirtualContent.BARREL && old.getBlock()!=state.getBlock())dev.tpc.virtual.VirtualNetwork.get(level).beforeChange(pos,old,state);
        }
    }
    @Inject(method="setBlockState",at=@At("RETURN"))
    private void tpc$changed(BlockPos pos,BlockState state,int flags,CallbackInfoReturnable<BlockState> cir){
        BlockState old=cir.getReturnValue();
        if(PersistentCables.enabled() && old!=null && old!=state && ((LevelChunk)(Object)this).getLevel() instanceof ServerLevel level){
            if(old.getBlock() instanceof IInventoryCable || state.getBlock() instanceof IInventoryCable)PersistentCables.manager(level).changed(pos);
            dev.tpc.virtual.VirtualNetwork.get(level).changed(pos,old,state);
        }
    }
}
