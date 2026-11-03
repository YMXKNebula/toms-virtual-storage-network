package dev.tpc.mixin;

import dev.tpc.PersistentCables;
import com.tom.storagemod.inventory.InventoryCableNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.util.Collection;

@Mixin(value=InventoryCableNetwork.class,remap=false)
public abstract class CableNetworkMixin {
    @Shadow @Final private Level level;
    @Inject(method="getNetworkNodes",at=@At("HEAD"),cancellable=true)
    private void tpc$query(BlockPos origin,CallbackInfoReturnable<Collection<BlockPos>> cir){
        if(PersistentCables.enabled() && level instanceof ServerLevel server)cir.setReturnValue(PersistentCables.manager(server).nodes(origin));
    }
    @Inject(method="markNodeInvalid(Lnet/minecraft/core/BlockPos;)V",at=@At("HEAD"),cancellable=true)
    private void tpc$invalidate(BlockPos pos,CallbackInfo ci){
        if(PersistentCables.enabled() && level instanceof ServerLevel server){PersistentCables.manager(server).changed(pos);ci.cancel();}
    }
}
