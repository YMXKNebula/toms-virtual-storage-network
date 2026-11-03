package dev.tpc.mixin;

import com.tom.storagemod.block.InventoryCableConnectorBlock;
import com.tom.storagemod.block.entity.InventoryCableConnectorBlockEntity;
import com.tom.storagemod.inventory.IInventoryAccess;
import dev.tpc.PersistentCables;
import dev.tpc.safety.*;
import dev.tpc.virtual.*;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.*;

/** The native container face becomes a guarded gateway only when it faces our capacity block. */
@Mixin(value=InventoryCableConnectorBlockEntity.class,remap=false)
public abstract class VirtualCableFrontMixin {
    @Unique private boolean tvsn$front(){
        var be=(InventoryCableConnectorBlockEntity)(Object)this;
        if(!PersistentCables.enabled() || !(be.getLevel() instanceof ServerLevel level) || !NetworkSafety.live(be))return false;
        var p=be.getBlockPos().relative(be.getBlockState().getValue(InventoryCableConnectorBlock.FACING));
        var chunk=level.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4);
        return chunk!=null?chunk.getBlockState(p).getBlock()==VirtualContent.BARREL:VirtualNetwork.get(level).ledger.get(p.asLong())!=null;
    }
    @Inject(method="getMergedHandler",at=@At("HEAD"),cancellable=true)
    private void tvsn$handler(CallbackInfoReturnable<IInventoryAccess> cir){
        if(!tvsn$front())return;
        var be=(InventoryCableConnectorBlockEntity)(Object)this;var world=VirtualNetwork.get((ServerLevel)be.getLevel());
        var ticket=NetworkSafety.begin(be);
        try{var gateway=world.gatewayConnector(be);cir.setReturnValue(gateway==null?com.tom.storagemod.inventory.PlatformInventoryAccess.EMPTY:new GuardedAccess(gateway.getMergedHandler(),ticket));}
        finally{NetworkSafety.finish();}
    }
    @Inject(method="getConnectedInventories",at=@At("HEAD"),cancellable=true)
    private void tvsn$singleAuthority(CallbackInfoReturnable<Collection<IInventoryAccess>> cir){if(tvsn$front())cir.setReturnValue(List.of());}
    @Inject(method="hasConnectedInventories",at=@At("HEAD"),cancellable=true)
    private void tvsn$available(CallbackInfoReturnable<Boolean> cir){if(tvsn$front())cir.setReturnValue(VirtualNetwork.get((ServerLevel)((InventoryCableConnectorBlockEntity)(Object)this).getLevel()).gateway(((InventoryCableConnectorBlockEntity)(Object)this).getBlockPos())!=null);}
}
