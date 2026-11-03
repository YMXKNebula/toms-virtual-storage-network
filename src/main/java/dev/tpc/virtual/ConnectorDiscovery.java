package dev.tpc.virtual;

import com.tom.storagemod.block.IInventoryCable;
import com.tom.storagemod.block.entity.InventoryConnectorBlockEntity;
import com.tom.storagemod.inventory.BlockFilter;
import dev.tpc.PersistentCables;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import java.lang.ref.WeakReference;
import java.util.*;

/** Audited native discovery footprints, adapted from the retired optimizer; no inventory index or second cable graph. */
public final class ConnectorDiscovery {
    private static final class Record {
        final BlockPos owner;final WeakReference<InventoryConnectorBlockEntity> entity;final Set<BlockPos> dependencies;
        final Set<Long> chunks;final int range,stable;final boolean trims;final Set<?> blockedBlocks,blockedMods;
        final Set<com.tom.storagemod.inventory.IInventoryAccess> inventories;final boolean safe;
        Record(InventoryConnectorBlockEntity be,Set<BlockPos> deps,int stable,boolean safe){
            owner=be.getBlockPos().immutable();entity=new WeakReference<>(be);dependencies=Set.copyOf(deps);this.stable=stable;
            var config=com.tom.storagemod.Config.get();range=config.invConnectorScanRange;trims=config.onlyTrims;blockedBlocks=Set.copyOf(config.getBlockedBlocks());blockedMods=Set.copyOf(config.getBlockedMods());
            var keys=new HashSet<Long>();for(var p:deps)keys.add(ChunkPos.pack(p.getX()>>4,p.getZ()>>4));chunks=Set.copyOf(keys);
            inventories=Set.copyOf(be.getConnectedInventories());this.safe=safe;
        }
    }
    private final ServerLevel level;
    private final AccessPolicies policies;
    private final Map<BlockPos,Record> records=new HashMap<>();
    private final Map<BlockPos,Set<Record>> points=new HashMap<>();
    private final Map<Long,Set<Record>> chunks=new HashMap<>();
    public long scans,sleeps,invalidations;
    public ConnectorDiscovery(ServerLevel level,AccessPolicies policies){this.level=level;this.policies=policies;}
    public boolean idle(InventoryConnectorBlockEntity be){
        var r=records.get(be.getBlockPos());if(r==null)return false;
        var cfg=com.tom.storagemod.Config.get();
        if(r.entity.get()!=be || r.range!=cfg.invConnectorScanRange || r.trims!=cfg.onlyTrims || !r.blockedBlocks.equals(Set.copyOf(cfg.getBlockedBlocks())) || !r.blockedMods.equals(Set.copyOf(cfg.getBlockedMods())) || BlockFilter.getFilterAt(level,be.getBlockPos())!=null){remove(r);policies.changed(r.owner);return false;}
        return r.safe && r.stable>=PersistentCables.CONFIG.unchangedScansBeforeIdle;
    }
    public boolean begin(InventoryConnectorBlockEntity be){if(idle(be)){sleeps++;return false;}scans++;return true;}
    public void adopt(InventoryConnectorBlockEntity be,Set<BlockPos> checked){
        var previous=records.get(be.getBlockPos());if(previous!=null)remove(previous);
        // External inventories and interfaces retain Tom's native discovery and validation.
        boolean safe=be.getConnectedInventories().isEmpty() && be.getInterfaces().isEmpty() && BlockFilter.getFilterAt(level,be.getBlockPos())==null;
        for(var p:checked){
            if(p.equals(be.getBlockPos()))continue;var chunk=level.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4);if(chunk==null){safe=false;continue;}
            var state=chunk.getBlockState(p);var block=state.getBlock();
            boolean cable=block instanceof IInventoryCable && block.getClass().getName().startsWith("com.tom.storagemod.block.");
            boolean trim=block.getClass()==com.tom.storagemod.block.TrimBlock.class || block.getClass()==com.tom.storagemod.block.PaintedTrimBlock.class;
            if(!(state.isAir() || block==VirtualContent.BARREL || cable || trim) || BlockFilter.getFilterAt(level,p)!=null)safe=false;
        }
        var deps=new HashSet<>(checked);deps.add(be.getBlockPos());boolean same=previous!=null && previous.dependencies.equals(deps) && previous.inventories.equals(Set.copyOf(be.getConnectedInventories()));
        int stable=same?previous.stable+1:0;if(previous!=null && !same)policies.changed(previous.owner);
        var record=new Record(be,deps,stable,safe);records.put(record.owner,record);
        for(var p:record.dependencies)points.computeIfAbsent(p,k->Collections.newSetFromMap(new IdentityHashMap<>())).add(record);
        for(long chunk:record.chunks)chunks.computeIfAbsent(chunk,k->Collections.newSetFromMap(new IdentityHashMap<>())).add(record);
    }
    public void changed(BlockPos p){for(var r:List.copyOf(points.getOrDefault(p,Set.of()))){policies.changed(r.owner);remove(r);}}
    public void chunk(int x,int z){for(var r:List.copyOf(chunks.getOrDefault(ChunkPos.pack(x,z),Set.of()))){policies.changed(r.owner);remove(r);}}
    private void remove(Record r){
        if(!records.remove(r.owner,r))return;invalidations++;
        for(var p:r.dependencies){var set=points.get(p);if(set!=null){set.remove(r);if(set.isEmpty())points.remove(p);}}
        for(long p:r.chunks){var set=chunks.get(p);if(set!=null){set.remove(r);if(set.isEmpty())chunks.remove(p);}}
    }
}
