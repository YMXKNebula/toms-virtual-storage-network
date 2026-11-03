package dev.tpc.mixin;

import dev.tpc.PersistentCables;
import dev.tpc.safety.*;
import com.tom.storagemod.inventory.*;
import com.tom.storagemod.block.entity.IInventoryConnector;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import java.util.*;

@Mixin(value=MultiInventoryAccess.class,remap=false)
public abstract class MergedInventoryMixin implements MergedBridge {
    @Shadow protected List<IInventoryAccess> connected;
    @Unique private NetworkSafety.Ticket tpc$ticket;
    @Unique private IInventoryConnector tvsn$self;
    @Unique private boolean tvsn$cacheable;
    @Unique private dev.tpc.virtual.LedgerAccess tvsn$ledger;
    public boolean tpc$valid(){return tpc$ticket==null || tpc$ticket.getAsBoolean();}
    public boolean tpc$ready(){return tvsn$self!=null && tpc$valid();}
    // Verified in the pinned published 2.12.2 JAR: this lambda deduplicates physical handlers.
    @Redirect(method="lambda$build$1",at=@At(value="INVOKE",target="Lcom/tom/storagemod/inventory/IInventoryAccess;get()Ljava/lang/Object;"))
    private static Object tpc$physicalIdentity(IInventoryAccess access){Object value=access.get();return value instanceof GuardedStorage storage?storage.identity():value;}
    @WrapMethod(method="build")
    private void tpc$build(IInventoryConnector self,Collection<IInventoryConnector> connectors,Operation<Void> original){
        if(!PersistentCables.enabled() || !(self instanceof net.minecraft.world.level.block.entity.BlockEntity be) || !(be.getLevel() instanceof net.minecraft.server.level.ServerLevel)){
            original.call(self,connectors);return;
        }
        var world=dev.tpc.virtual.VirtualNetwork.get((net.minecraft.server.level.ServerLevel)be.getLevel());
        if(tvsn$cacheable && tvsn$ledger!=null && tvsn$ledger.valid() && tvsn$self==self && tpc$ticket!=null && tpc$ticket.getAsBoolean() && self instanceof com.tom.storagemod.block.entity.InventoryConnectorBlockEntity connector && world.discovery.idle(connector)){
            world.mergedBuildHits++;return;
        }
        world.mergedBuilds++;
        var ticket=NetworkSafety.begin(self);
        try {
            var peers=new ArrayList<>(NetworkSafety.connectors(self));
            for(var peer:peers)if(peer instanceof net.minecraft.world.level.block.entity.BlockEntity endpoint)ticket.watch(endpoint);
            var gateway=dev.tpc.virtual.VirtualNetwork.get((net.minecraft.server.level.ServerLevel)be.getLevel()).gatewayConnector(self);
            if(gateway!=null)peers.add(gateway);
            original.call(self,peers);
            tvsn$ledger=connected.stream().filter(access->access instanceof dev.tpc.virtual.LedgerAccess).map(access->(dev.tpc.virtual.LedgerAccess)access).findFirst().orElse(null);
            tvsn$self=self;tvsn$cacheable=!connected.isEmpty() && connected.stream().allMatch(access->access instanceof dev.tpc.virtual.LedgerAccess);
            connected.replaceAll(access->new GuardedAccess(access,ticket));
            tpc$ticket=ticket;
            var tracker=((MultiInventoryAccess)(Object)this).tracker();
            if(tracker instanceof TrackerBridge bridge)bridge.tpc$reset(connected.size());
            tracker.getChangeTracker(be.getLevel());
        } finally {NetworkSafety.finish();}
    }
    @Inject(method="clear",at=@At("HEAD"))
    private void tvsn$clear(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci){tvsn$cacheable=false;if(tpc$ticket!=null)tpc$ticket.invalidate();}
}
