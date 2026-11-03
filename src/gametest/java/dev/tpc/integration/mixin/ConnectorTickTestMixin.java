package dev.tpc.integration.mixin;
import com.tom.storagemod.block.entity.InventoryConnectorBlockEntity;
import dev.tpc.integration.ConnectorTestHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=InventoryConnectorBlockEntity.class,remap=false)
public abstract class ConnectorTickTestMixin {
    @Inject(method="updateServer",at=@At("HEAD"),cancellable=true)
    private void test$loadedButNotTicking(CallbackInfo ci){if(ConnectorTestHooks.frozen.contains(((InventoryConnectorBlockEntity)(Object)this).getBlockPos()))ci.cancel();}
}
