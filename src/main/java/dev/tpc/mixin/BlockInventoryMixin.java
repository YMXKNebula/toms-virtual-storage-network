package dev.tpc.mixin;

import dev.tpc.PersistentCables;
import dev.tpc.safety.GuardedStorage;
import dev.tpc.safety.DoubleChestStorage;
import net.fabricmc.fabric.api.lookup.v1.block.BlockApiCache;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.tom.storagemod.inventory.PlatformInventoryAccess.BlockInventoryAccess;

@Mixin(value=BlockInventoryAccess.class,remap=false)
public abstract class BlockInventoryMixin {
    @Redirect(method="getRootHandler",at=@At(value="INVOKE",target="Lcom/tom/storagemod/inventory/PlatformInventoryAccess$BlockInventoryAccess;get()Lnet/fabricmc/fabric/api/transfer/v1/storage/Storage;"))
    private Storage<ItemVariant> tpc$proxyRoot(BlockInventoryAccess self){
        var storage=self.get();return storage instanceof GuardedStorage guard?guard.rootLookupStorage():storage;
    }
    @Shadow private BlockApiCache<Storage<ItemVariant>,Direction> cache;
    @Shadow private Direction d;
    @Unique private Storage<ItemVariant> tpc$raw,tpc$guarded;
    @Unique private DoubleChestStorage tpc$doubleChest;
    @Unique private Object tpc$loadToken;
    @Unique private Object tpc$state,tpc$entity,tpc$cache;
    @Inject(method="hasInventoryAt",at=@At("HEAD"),cancellable=true)
    private static void tpc$loadedInventory(Level level,BlockPos pos,BlockState state,Direction direction,CallbackInfoReturnable<Boolean> cir){
        if(PersistentCables.enabled() && level instanceof ServerLevel server){
            if(!server.getServer().isSameThread()){cir.setReturnValue(false);return;}
            var chunk=server.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
            if(chunk==null || !DoubleChestStorage.otherHalfLoaded(server,pos,chunk.getBlockState(pos)))cir.setReturnValue(false);
        }
    }
    @Inject(method="get()Lnet/fabricmc/fabric/api/transfer/v1/storage/Storage;",at=@At("HEAD"),cancellable=true)
    private void tpc$loadedOnly(CallbackInfoReturnable<Storage<ItemVariant>> cir){
        if(PersistentCables.enabled() && cache!=null) {
            var level=cache.getLevel();var pos=cache.getPos();
            if(!level.getServer().isSameThread()){cir.setReturnValue(null);return;}
            var chunk=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
            if(chunk==null || !DoubleChestStorage.otherHalfLoaded(level,pos,chunk.getBlockState(pos)))cir.setReturnValue(null);
        }
    }
    @Inject(method="get()Lnet/fabricmc/fabric/api/transfer/v1/storage/Storage;",at=@At("RETURN"),cancellable=true)
    private void tpc$borrow(CallbackInfoReturnable<Storage<ItemVariant>> cir){
        var raw=cir.getReturnValue();if(!PersistentCables.enabled() || cache==null || raw==null)return;
        Object token=PersistentCables.manager(cache.getLevel()).loadToken(cache.getPos());
        var levelNow=cache.getLevel();var posNow=cache.getPos();var chunkNow=levelNow.getChunkSource().getChunkNow(posNow.getX()>>4,posNow.getZ()>>4);
        var stateNow=chunkNow==null?null:chunkNow.getBlockState(posNow);var entityNow=chunkNow==null?null:chunkNow.getBlockEntity(posNow);
        boolean sameStorage=tpc$doubleChest!=null?tpc$doubleChest.matches(raw):raw==tpc$raw;
        if(!sameStorage || token!=tpc$loadToken || stateNow!=tpc$state || entityNow!=tpc$entity || cache!=tpc$cache){
            tpc$loadToken=token;
            tpc$state=stateNow;tpc$entity=entityNow;tpc$cache=cache;
            var captured=cache;var pos=captured.getPos();var level=captured.getLevel();
            var doubleChest=DoubleChestStorage.capture(level,pos,d,raw);tpc$doubleChest=doubleChest;
            tpc$raw=doubleChest==null?raw:doubleChest.storage();
            var chunk=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
            var state=chunk==null?null:chunk.getBlockState(pos);var be=chunk==null?null:chunk.getBlockEntity(pos);
            tpc$guarded=new GuardedStorage(tpc$raw,()->level.getServer().isSameThread() && cache==captured &&
                (doubleChest==null || doubleChest.valid()) &&
                PersistentCables.manager(level).loadToken(pos)==token &&
                level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4)==chunk && chunk!=null &&
                chunk.getBlockState(pos)==state && chunk.getBlockEntity(pos)==be &&
                (doubleChest==null?captured.find(d)==raw:doubleChest.matches(captured.find(d))));
        }
        cir.setReturnValue(tpc$guarded);
    }
}
