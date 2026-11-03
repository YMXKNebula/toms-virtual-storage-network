package dev.tpc.virtual;

import dev.tpc.PersistentCables;
import dev.tpc.core.Topology;
import dev.tpc.safety.NetworkSafety;
import com.tom.storagemod.block.entity.IInventoryConnector;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.ChunkPos;
import java.util.*;

/** Functional-node lookup cache over the existing lease; it never builds a second graph. */
public final class PhysicalPeers {
    private record Entry(Topology.Lease lease,List<IInventoryConnector> connectors,Set<Long> chunks,List<BlockPos> pending){}
    private final VirtualNetwork network;
    private final Map<Topology.Lease,Entry> entries=new IdentityHashMap<>();
    private final Map<Long,Set<Entry>> chunks=new HashMap<>();
    public long builds,hits;
    public PhysicalPeers(VirtualNetwork network){this.network=network;}
    public List<IInventoryConnector> get(Topology.Lease lease){
        if(!lease.valid())return List.of();var cached=entries.get(lease);
        if(cached!=null && cached.connectors.stream().allMatch(c->c instanceof BlockEntity be && NetworkSafety.live(be)) && !published(cached)){hits++;return cached.connectors;}
        if(cached!=null)remove(cached);
        var peers=new ArrayList<IInventoryConnector>();var dependencies=new HashSet<Long>();var pending=new ArrayList<BlockPos>();
        for(var point:lease.functions()){
            dependencies.add(ChunkPos.pack(point.x()>>4,point.z()>>4));var p=PersistentCables.block(point);
            // The persisted connector index can lag a loaded block entity during
            // startup or budgeted registration. Known capacity nodes remain a fast
            // exclusion, so a cache rebuild never fetches one BE per virtual barrel.
            if(!network.knownConnector(p.asLong()) && network.ledger.get(p.asLong())!=null)continue;
            var chunk=network.level.getChunkSource().getChunkNow(point.x()>>4,point.z()>>4);
            if(chunk==null){
                // Keep unloaded connector endpoints observable after this empty
                // lookup is cached; getChunkNow never loads their chunks.
                if(network.knownConnector(p.asLong()))pending.add(p.immutable());
                continue;
            }
            if(chunk.getBlockEntity(p) instanceof IInventoryConnector connector && connector instanceof BlockEntity be && NetworkSafety.live(be))peers.add(connector);
            else {
                var block=chunk.getBlockState(p).getBlock();
                if(block instanceof com.tom.storagemod.block.InventoryConnectorBlock || block instanceof com.tom.storagemod.block.InventoryCableConnectorBlock)pending.add(p.immutable());
            }
        }
        var entry=new Entry(lease,List.copyOf(peers),Set.copyOf(dependencies),List.copyOf(pending));entries.put(lease,entry);
        for(long p:dependencies)chunks.computeIfAbsent(p,k->Collections.newSetFromMap(new IdentityHashMap<>())).add(entry);builds++;return entry.connectors;
    }
    private boolean published(Entry entry){
        // A loaded connector may finish publishing its BE after the graph query.
        // Only recheck these few pending endpoints; an empty cached result must not
        // hide a now-working connector until somebody edits the network again.
        for(var p:entry.pending){var chunk=network.level.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4);
            if(chunk!=null && chunk.getBlockEntity(p) instanceof IInventoryConnector connector && connector instanceof BlockEntity be && NetworkSafety.live(be))return true;
        }return false;
    }
    public void chunk(int x,int z){for(var entry:List.copyOf(chunks.getOrDefault(ChunkPos.pack(x,z),Set.of())))remove(entry);}
    public void changed(){for(var entry:List.copyOf(entries.values()))if(!entry.lease.valid())remove(entry);}
    private void remove(Entry entry){entries.remove(entry.lease,entry);for(long p:entry.chunks){var set=chunks.get(p);if(set!=null){set.remove(entry);if(set.isEmpty())chunks.remove(p);}}}
}
