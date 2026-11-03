package dev.tpc.mixin;

import dev.tpc.PersistentCables;
import dev.tpc.safety.NetworkSafety;
import com.tom.storagemod.block.entity.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Collection;

@Mixin(value={InventoryConnectorBlockEntity.class,InventoryCableConnectorBlockEntity.class},remap=false)
public abstract class ConnectorLinksMixin {
    @Inject(method="getConnectedConnectors",at=@At("HEAD"),cancellable=true)
    private void tpc$currentLinks(CallbackInfoReturnable<Collection<IInventoryConnector>> cir){
        if(PersistentCables.enabled() && (Object)this instanceof net.minecraft.world.level.block.entity.BlockEntity be && be.getLevel() instanceof net.minecraft.server.level.ServerLevel)
            cir.setReturnValue(NetworkSafety.connectors((IInventoryConnector)this));
    }
}
