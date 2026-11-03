package dev.tpc.integration.mixin;

import com.tom.storagemod.block.entity.StorageTerminalBlockEntity;
import com.tom.storagemod.inventory.NetworkInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read the exact terminal access path to wait for native BE initialization, without changing it. */
@Mixin(value=StorageTerminalBlockEntity.class,remap=false)
public interface TerminalTestAccess {
    @Accessor("itemCache") NetworkInventory tvsn$itemCache();
}
