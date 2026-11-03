package dev.tpc.scale.mixin;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** Test-only diagnostics for an otherwise discarded asynchronous Carpet login failure. */
@Pseudo
@Mixin(targets="carpet.patches.EntityPlayerMPFake",remap=false)
public abstract class FakePlayerFailureMixin {
    @WrapOperation(method="createFake",at=@At(value="INVOKE",target="Ljava/util/concurrent/CompletableFuture;whenCompleteAsync(Ljava/util/function/BiConsumer;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private static CompletableFuture<?> tvsn$capture(CompletableFuture<?> future,BiConsumer<?,?> callback,Executor executor,Operation<CompletableFuture<?>> original){
        var result=original.call(future,callback,executor);result.whenComplete((value,error)->{if(error!=null)dev.tpc.scale.LargeRun.creationFailure=error;});return result;
    }
}
