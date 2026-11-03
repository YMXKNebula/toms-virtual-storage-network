package dev.tpc.mixin;

import dev.tpc.safety.TransactionGuards;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Target verified against Fabric Transfer API 8.0.25 for Minecraft 26.3. */
@Mixin(targets="net.fabricmc.fabric.impl.transfer.transaction.TransactionManagerImpl$TransactionImpl",remap=false)
public abstract class TransactionCommitMixin {
    @Inject(method="commit",at=@At("HEAD"))
    private void tvsn$validateJointCommit(CallbackInfo ci){TransactionGuards.validate((Transaction)(Object)this);}
}
