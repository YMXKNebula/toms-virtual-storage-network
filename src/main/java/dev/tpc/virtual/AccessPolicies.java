package dev.tpc.virtual;

import dev.tpc.safety.NetworkSafety;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import java.lang.ref.WeakReference;
import java.util.*;

/** One-way revocation of borrowed handles when a native inventory discovery dependency changes. */
public final class AccessPolicies {
    private final Map<BlockPos,List<WeakReference<NetworkSafety.Ticket>>> watchers=new HashMap<>();
    private final Map<Long,Set<BlockPos>> chunks=new HashMap<>();
    public void watch(BlockPos p,NetworkSafety.Ticket ticket){
        var list=watchers.computeIfAbsent(p.immutable(),k->new ArrayList<>());list.removeIf(w->w.get()==null);
        if(list.stream().noneMatch(w->w.get()==ticket))list.add(new WeakReference<>(ticket));
        chunks.computeIfAbsent(ChunkPos.pack(p.getX()>>4,p.getZ()>>4),k->new HashSet<>()).add(p.immutable());
    }
    public void changed(BlockPos p){
        var list=watchers.remove(p);if(list!=null)for(var ref:list){var ticket=ref.get();if(ticket!=null)ticket.invalidate();}
        long key=ChunkPos.pack(p.getX()>>4,p.getZ()>>4);var set=chunks.get(key);if(set!=null){set.remove(p);if(set.isEmpty())chunks.remove(key);}
    }
    public void chunk(int x,int z){for(var p:List.copyOf(chunks.getOrDefault(ChunkPos.pack(x,z),Set.of())))changed(p);}
}
