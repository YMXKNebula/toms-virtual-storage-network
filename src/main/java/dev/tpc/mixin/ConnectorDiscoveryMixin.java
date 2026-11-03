package dev.tpc.mixin;

import dev.tpc.PersistentCables;
import dev.tpc.virtual.VirtualNetwork;
import com.tom.storagemod.block.entity.InventoryConnectorBlockEntity;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Set;

@Mixin(value=InventoryConnectorBlockEntity.class,remap=false)
public abstract class ConnectorDiscoveryMixin {
    @WrapMethod(method="detectTouchingInventories")
    private void tvsn$discovery(Operation<Void> original){
        var be=(InventoryConnectorBlockEntity)(Object)this;
        if(PersistentCables.enabled() && be.getLevel() instanceof ServerLevel level && !VirtualNetwork.get(level).discovery.begin(be))return;original.call();
    }
    @Inject(method="detectTouchingInventories",at=@At("RETURN"))
    private void tvsn$footprint(CallbackInfo ci,@Local(name="checkedBlocks") Set<BlockPos> checked){
        var be=(InventoryConnectorBlockEntity)(Object)this;if(PersistentCables.enabled() && be.getLevel() instanceof ServerLevel level)VirtualNetwork.get(level).discovery.adopt(be,checked);
    }
}
