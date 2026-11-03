package dev.tpc.mixin;

import com.tom.storagemod.block.entity.*;
import com.tom.storagemod.inventory.*;
import dev.tpc.PersistentCables;
import dev.tpc.safety.*;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;

/** A loaded entry need not receive periodic BE ticks to serve its current guarded aggregate. */
@Mixin(value=InventoryConnectorBlockEntity.class,remap=false)
public abstract class ConnectorHandlerMixin {
    @Shadow(remap=false) private MultiInventoryAccess handler;
    @Shadow(remap=false) private void detectTouchingInventories(){throw new AssertionError();}
    @Shadow(remap=false) private void detectCableNetwork(){throw new AssertionError();}
    @Inject(method={"getMergedHandler","getInventoryAccess"},at=@At("HEAD"))
    private void tvsn$readyEntry(CallbackInfoReturnable<IInventoryAccess> cir){
        var self=(InventoryConnectorBlockEntity)(Object)this;
        if(!PersistentCables.enabled() || !(self.getLevel() instanceof ServerLevel level) || !NetworkSafety.live(self) || NetworkSafety.building() || Transaction.isOpen())return;
        if(handler instanceof MergedBridge bridge && !bridge.tpc$ready() && PersistentCables.manager(level).query(self.getBlockPos()).valid()){
            // Keep the native local discovery, filtering and aggregation path.
            // Never build a cached inventory view from provisional transaction data.
            detectTouchingInventories();detectCableNetwork();handler.build(self,List.of());
        }
    }
}
