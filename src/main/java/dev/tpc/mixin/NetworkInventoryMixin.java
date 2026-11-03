package dev.tpc.mixin;

import dev.tpc.PersistentCables;
import dev.tpc.safety.*;
import com.tom.storagemod.inventory.*;
import com.tom.storagemod.inventory.PlatformInventoryAccess.BlockInventoryAccess;
import com.tom.storagemod.block.entity.IInventoryConnector;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.lang.ref.WeakReference;

@Mixin(value=NetworkInventory.class,remap=false)
public abstract class NetworkInventoryMixin {
    @Shadow private BlockInventoryAccess block;
    @Shadow private WeakReference<IInventoryConnector> networkAccess;
    @Shadow public abstract void scanNetwork(Level level,BlockPos pos);
    @Inject(method="scanNetwork",at=@At("HEAD"),cancellable=true)
    private void tvsn$scan(Level level,BlockPos pos,org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci){
        if(PersistentCables.enabled() && level instanceof ServerLevel server){
            networkAccess=null;
            if(tvsn$barrel(server,pos)){ci.cancel();return;}
            var lease=PersistentCables.manager(server).query(pos);
            for(var connector:dev.tpc.virtual.VirtualNetwork.get(server).peers.get(lease))if(connector.hasConnectedInventories()){networkAccess=new WeakReference<>(connector);break;}
            ci.cancel();
        }
    }
    @Inject(method="getAccess",at=@At("HEAD"),cancellable=true)
    private void tpc$refresh(Level level,BlockPos pos,CallbackInfoReturnable<IInventoryAccess> cir){
        if(PersistentCables.enabled() && level instanceof ServerLevel server){
            if(tvsn$barrel(server,pos)){networkAccess=null;cir.setReturnValue(PlatformInventoryAccess.EMPTY);return;}
            var storage=block.get();
            if(storage instanceof GuardedStorage guard){
                var access=guard.proxyAccess();
                if(access!=null){
                    var chunk=server.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
                    if(chunk!=null && chunk.getBlockState(pos).getBlock() instanceof com.tom.storagemod.block.IInventoryCable){
                        var lease=PersistentCables.manager(server).query(pos);
                        access=new GuardedAccess(access,lease::valid);
                    }
                    networkAccess=null;cir.setReturnValue(access);return;
                }
            }
            if(storage==null)scanNetwork(level,pos);
        }
    }
    @Unique private static boolean tvsn$barrel(ServerLevel level,BlockPos pos){
        var chunk=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
        return chunk!=null?chunk.getBlockState(pos).getBlock()==dev.tpc.virtual.VirtualContent.BARREL:dev.tpc.virtual.VirtualNetwork.get(level).ledger.get(pos.asLong())!=null;
    }
    @Inject(method="getAccess",at=@At("RETURN"),cancellable=true)
    private void tpc$guard(Level level,BlockPos pos,CallbackInfoReturnable<IInventoryAccess> cir){
        if(PersistentCables.enabled() && level instanceof ServerLevel server && cir.getReturnValue()!=block && networkAccess!=null && networkAccess.get() instanceof BlockEntity endpoint)
            cir.setReturnValue(new GuardedAccess(cir.getReturnValue(),NetworkSafety.origin(server,pos,endpoint)));
    }
}
