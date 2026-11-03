package dev.tpc.mixin;

import dev.tpc.PersistentCables;
import dev.tpc.safety.*;
import com.tom.storagemod.inventory.*;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.stream.Stream;

@Mixin(targets="com.tom.storagemod.inventory.MultiInventoryAccess$MultiChangeTracker",remap=false)
public abstract class TrackerGuardMixin implements TrackerBridge {
    @Shadow @Final private MultiInventoryAccess this$0;
    @Shadow private long[] trackers;
    @Shadow private long lastUpdate,lastChange;
    public void tpc$reset(int count){trackers=new long[count+1];lastUpdate=Long.MIN_VALUE;lastChange=System.nanoTime();}
    @Unique private boolean tpc$invalid(){return PersistentCables.enabled() && this$0 instanceof MergedBridge bridge && !bridge.tpc$valid();}
    @Inject(method="getChangeTracker",at=@At("HEAD"),cancellable=true)
    private void tpc$version(Level level,CallbackInfoReturnable<Long> cir){if(tpc$invalid())cir.setReturnValue(Long.MIN_VALUE);}
    @Inject(method="streamWrappedStacks",at=@At("HEAD"),cancellable=true)
    private void tpc$items(boolean parallel,CallbackInfoReturnable<Stream<StoredItemStack>> cir){if(tpc$invalid())cir.setReturnValue(Stream.empty());}
    @Inject(method="countItems",at=@At("HEAD"),cancellable=true)
    private void tpc$count(StoredItemStack stack,CallbackInfoReturnable<Long> cir){if(tpc$invalid())cir.setReturnValue(0L);}
}
