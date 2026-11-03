package dev.tpc.mixin;

import dev.tpc.PersistentCables;
import com.tom.storagemod.block.entity.InventoryConnectorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(value=InventoryConnectorBlockEntity.class,remap=false)
public abstract class ConnectorLoadedScanMixin {
    @Redirect(method="detectTouchingInventories",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState tpc$loadedState(Level level,BlockPos pos){
        if(PersistentCables.enabled() && level instanceof ServerLevel server) {
            var chunk=server.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
            return chunk==null?Blocks.AIR.defaultBlockState():chunk.getBlockState(pos);
        }
        return level.getBlockState(pos);
    }
    @Redirect(method="detectTouchingInventories",at=@At(value="INVOKE",target="Lcom/tom/storagemod/inventory/PlatformInventoryAccess$BlockInventoryAccess;hasInventoryAt(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;)Z"))
    private boolean tpc$loadedInventory(Level level,BlockPos pos,BlockState state,Direction direction){
        if(PersistentCables.enabled() && level instanceof ServerLevel server && server.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4)==null)return false;
        return com.tom.storagemod.inventory.PlatformInventoryAccess.BlockInventoryAccess.hasInventoryAt(level,pos,state,direction);
    }
}
