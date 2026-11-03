package dev.tpc.mixin;

import dev.tpc.PersistentCables;
import dev.tpc.virtual.VirtualNetwork;
import com.tom.storagemod.inventory.BlockFilter;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=BlockFilter.class,remap=false)
public abstract class FilterEventsMixin {
    @Shadow private BlockPos pos;
    @Inject(method={"setPriority","setSkip","setSide","setKeepLast","addConnected","markFilterDirty","loadFromState"},at=@At("RETURN"))
    private void tvsn$filterChanged(CallbackInfo ci){if(PersistentCables.enabled() && pos!=null)VirtualNetwork.filterChanged((BlockFilter)(Object)this,pos);}
}
