package dev.tpc.mixin;

import dev.tpc.PersistentCables;
import dev.tpc.safety.*;
import com.tom.storagemod.block.entity.*;
import com.tom.storagemod.inventory.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.lang.ref.WeakReference;

@Mixin(value=InventoryInterfaceBlockEntity.class,remap=false)
public abstract class InterfaceInventoryMixin implements InterfaceBridge {
    @Shadow private WeakReference<IInventoryConnector> connectorAccess;
    @Shadow private WeakReference<IInventoryConnector> networkAccess;
    public IInventoryConnector tpc$localConnector(){return connectorAccess==null?null:connectorAccess.get();}
    @Inject(method="setConnectorAccess",at=@At("RETURN"))
    private void tpc$changed(IInventoryConnector connector,CallbackInfo ci){
        BlockEntity self=(BlockEntity)(Object)this;
        if(PersistentCables.enabled() && self.getLevel() instanceof ServerLevel level)PersistentCables.manager(level).changed(self.getBlockPos());
    }
    @Inject(method="getInventoryAccess",at=@At("RETURN"),cancellable=true)
    private void tpc$access(CallbackInfoReturnable<IInventoryAccess> cir){
        BlockEntity self=(BlockEntity)(Object)this;
        if(PersistentCables.enabled() && self.getLevel() instanceof ServerLevel level && networkAccess!=null && networkAccess.get() instanceof BlockEntity endpoint)
            cir.setReturnValue(new GuardedAccess(cir.getReturnValue(),NetworkSafety.origin(level,self.getBlockPos(),endpoint)));
    }
    @Inject(method="getConnectorRef",at=@At("RETURN"),cancellable=true)
    private void tpc$reference(CallbackInfoReturnable<IInventoryConnector> cir){
        BlockEntity self=(BlockEntity)(Object)this;
        if(PersistentCables.enabled() && self.getLevel() instanceof ServerLevel level && cir.getReturnValue() instanceof BlockEntity endpoint &&
                !NetworkSafety.origin(level,self.getBlockPos(),endpoint).getAsBoolean())cir.setReturnValue(null);
    }
}
