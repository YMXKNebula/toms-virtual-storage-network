package dev.tpc.mixin;

import dev.tpc.PersistentCables;
import dev.tpc.safety.NetworkSafety;
import com.tom.storagemod.block.entity.*;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Set;

@Mixin(value={InventoryConnectorBlockEntity.class,InventoryCableConnectorBlockEntity.class},remap=false)
public abstract class ConnectorPeersMixin {
    @Shadow(remap=false) private Set<IInventoryConnector> linkedConnectors;
    @Inject(method="detectCableNetwork",at=@At("HEAD"),cancellable=true)
    private void tvsn$indexedPeers(CallbackInfo ci){
        var be=(net.minecraft.world.level.block.entity.BlockEntity)(Object)this;
        if(PersistentCables.enabled() && be.getLevel() instanceof ServerLevel){linkedConnectors.clear();linkedConnectors.addAll(NetworkSafety.connectors((IInventoryConnector)this));ci.cancel();}
    }
}
