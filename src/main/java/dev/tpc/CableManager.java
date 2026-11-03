package dev.tpc;

import dev.tpc.core.*;
import com.tom.storagemod.block.IInventoryCable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.*;
import java.util.*;

public final class CableManager implements AutoCloseable {
    public final ServerLevel level;
    public final Topology topology;
    private final TopologyJournal journal;
    private final Map<Long,LevelChunk> loaded=new HashMap<>();
    private final Map<Long,Object> loadTokens=new HashMap<>();
    private final LinkedHashMap<Long,Scan> scans=new LinkedHashMap<>();
    private final LinkedHashSet<Point> pending=new LinkedHashSet<>();
    private final Set<Long> unverifiedChunks=new HashSet<>();
    private int syncUpdates,immediateNodes;
    private long syncNanos;
    private long startTime, lastNanos, observed;
    private boolean capacityWarning;
    private Topology.SnapshotJob snapshotJob;
    private String recovery;

    public CableManager(ServerLevel level) {
        this.level=level; topology=new Topology(PersistentCables.CONFIG.maxCachedQueries);
        Path root=level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/toms_persistent_cables");
        try {
            Files.createDirectories(root);Path identity=root.resolve("world.id");
            if(!Files.exists(identity))Files.writeString(identity,UUID.randomUUID().toString(),StandardOpenOption.CREATE_NEW);
            String dimension=level.dimension().identifier().toString();
            String key=HexFormat.of().formatHex(dimension.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Path path=root.resolve("dimensions").resolve(key+".wal");
            journal=new TopologyJournal(path,Files.readString(identity)+"/"+dimension);
            var r=journal.open(Integer.MAX_VALUE);recovery=r.reason();
            topology.restore(r.runs(),r.version()); topology.onEdit(edit->{journal.append(edit);dev.tpc.virtual.VirtualNetwork.get(level).topologyChanged();});
            PersistentCables.LOG.info("{}: {} ({} records)",dimension,recovery,topology.runCount());
        } catch(Exception e) { throw new IllegalStateException("Cannot initialize dimension-local topology",e); }
    }
    public void startTick() { syncUpdates=0;immediateNodes=0;syncNanos=0;startTime=System.nanoTime(); }
    public void loaded(LevelChunk chunk) {
        long key=chunk.getPos().pack();loaded.put(key,chunk);loadTokens.put(key,new Object());
        topology.beginChunkValidation(chunk.getPos().x(),chunk.getPos().z());
        scans.put(key,new Scan(chunk));
    }
    public void unloaded(LevelChunk chunk) {
        long key=chunk.getPos().pack();loaded.remove(key);loadTokens.remove(key);
        // An incomplete verification stays unknown. The run is suspended until the chunk is checked again.
        Scan scan=scans.remove(key);
        if(scan!=null)unverifiedChunks.add(key);
    }
    public NodeSpec read(Point p) {
        BlockPos pos=PersistentCables.block(p);
        LevelChunk chunk=level.getChunkSource().getChunkNow(p.x()>>4,p.z()>>4);
        if(chunk==null)return null;
        var state=chunk.getBlockState(pos);
        if(!(state.getBlock() instanceof IInventoryCable cable))return null;
        // Only the pinned upstream implementations have been audited for world-safe nextScan calls.
        if(state.getBlock()!=dev.tpc.virtual.VirtualContent.BARREL && !state.getBlock().getClass().getName().startsWith("com.tom.storagemod.block."))return null;
        if(state.getBlock()==dev.tpc.virtual.VirtualContent.BARREL){
            int allowed=0;var world=dev.tpc.virtual.VirtualNetwork.get(level);
            for(Direction d:Direction.values()){
                var adjacent=pos.relative(d);var peer=level.getChunkSource().getChunkNow(adjacent.getX()>>4,adjacent.getZ()>>4);
                boolean connects;
                if(peer!=null){var block=peer.getBlockState(adjacent).getBlock();connects=block==dev.tpc.virtual.VirtualContent.BARREL || block instanceof com.tom.storagemod.block.InventoryConnectorBlock || block instanceof com.tom.storagemod.block.InventoryCableConnectorBlock;}
                else connects=world.ledger.get(adjacent.asLong())!=null || world.knownConnector(adjacent.asLong());
                if(connects)allowed|=1<<d.ordinal();
            }
            return new NodeSpec(allowed,allowed,true,List.of());
        }
        int in=0,out=0;
        for(Direction d:Direction.values()) if(cable.canConnectFrom(state,d))in|=1<<d.ordinal();
        List<NodeSpec.Target> extra=new ArrayList<>();
        if(chunk.getBlockEntity(pos) instanceof dev.tpc.safety.InterfaceBridge bridge && bridge.tpc$localConnector() instanceof com.tom.storagemod.block.entity.InventoryConnectorBlockEntity connector &&
                dev.tpc.safety.NetworkSafety.live(connector) && connector.getInterfaces().stream().anyMatch(f->f.pos().equals(pos)))
            extra.add(new NodeSpec.Target(PersistentCables.point(connector.getBlockPos()),0));
        for(var face:cable.nextScan(level,state,pos)) {
            boolean adjacent=false;
            for(Direction d:Direction.values()) if(pos.relative(d).equals(face.pos()) && face.from()==d.getOpposite()) {out|=1<<d.ordinal();adjacent=true;break;}
            if(!adjacent && face.from()!=null)extra.add(new NodeSpec.Target(PersistentCables.point(face.pos()),face.from().ordinal()));
        }
        // A TVSN capacity block is reached through a cable connector's container face.
        // Only this custom block opens that face; ordinary cables retain Tom's direction rules.
        if(state.getBlock() instanceof com.tom.storagemod.block.InventoryCableConnectorBlock){
            Direction facing=state.getValue(com.tom.storagemod.block.InventoryCableConnectorBlock.FACING);BlockPos front=pos.relative(facing);
            var frontChunk=level.getChunkSource().getChunkNow(front.getX()>>4,front.getZ()>>4);
            boolean virtual=frontChunk!=null?frontChunk.getBlockState(front).getBlock()==dev.tpc.virtual.VirtualContent.BARREL:
                    dev.tpc.virtual.VirtualNetwork.get(level).ledger.get(front.asLong())!=null;
            if(virtual){in|=1<<facing.ordinal();out|=1<<facing.ordinal();}
        }
        return new NodeSpec(in,out,cable.isFunctionalNode(),extra);
    }
    public void changed(BlockPos position) {
        topology.checkThread();
        Point p=PersistentCables.point(position);
        if(level.getChunkSource().getChunkNow(p.x()>>4,p.z()>>4)==null)return;
        NodeSpec spec=read(p);CableRun old=topology.find(p);
        if(old==null && spec==null || old!=null && old.spec().equals(spec))return;
        topology.suspend(p); // Safety happens before scheduling or coalescing.
        if(syncUpdates++<PersistentCables.CONFIG.maxTopologyUpdatesPerTick && syncNanos<PersistentCables.CONFIG.maxTopologyProcessingTimeMs*1_000_000 && !scans.containsKey(ChunkPos.pack(p.x()>>4,p.z()>>4))) {
            long begin=System.nanoTime();topology.observe(p,spec);checkCapacity();syncNanos+=System.nanoTime()-begin;
        } else if(pending.size()<PersistentCables.CONFIG.maxQueuedPositions)pending.add(p);
        else {LevelChunk chunk=loaded.get(ChunkPos.pack(p.x()>>4,p.z()>>4));if(chunk!=null && !scans.containsKey(chunk.getPos().pack()))loaded(chunk);}
    }
    public Topology.Lease query(BlockPos origin) {
        topology.checkThread();Point p=PersistentCables.point(origin);
        if(topology.find(p)==null && level.getChunkSource().getChunkNow(p.x()>>4,p.z()>>4)!=null)changed(origin);
        long before=topology.searchSteps();
        var result=topology.query(p,Math.min(PersistentCables.CONFIG.immediateGraphNodes,Math.max(0,PersistentCables.CONFIG.maxGraphNodesProcessedPerTick-immediateNodes)));
        immediateNodes+=(int)(topology.searchSteps()-before);return result;
    }
    public Collection<BlockPos> nodes(BlockPos origin) {
        return query(origin).functions().stream().map(PersistentCables::block).toList();
    }
    private void checkCapacity() {
        if(!capacityWarning && topology.runCount()>PersistentCables.CONFIG.maxResidentRuns) {
            capacityWarning=true;
            PersistentCables.LOG.warn("TVSN compressed topology exceeds the resident warning threshold ({} records); processing continues within per-tick budgets. Resident memory still depends on compressed graph complexity.",topology.runCount());
        }
    }
    public void tick() {
        long begin=System.nanoTime(),deadline=begin+Math.max(0,(long)(PersistentCables.CONFIG.maxTopologyProcessingTimeMs*1_000_000)-syncNanos);
        int budget=PersistentCables.CONFIG.maxTopologyUpdatesPerTick;
        while(!pending.isEmpty() && budget-->0 && System.nanoTime()<deadline) {
            var it=pending.iterator();Point p=it.next();it.remove();
            if(level.getChunkSource().getChunkNow(p.x()>>4,p.z()>>4)!=null)topology.observe(p,read(p));
            else {unverifiedChunks.add(ChunkPos.pack(p.x()>>4,p.z()>>4));topology.beginChunkValidation(p.x()>>4,p.z()>>4);}
        }
        int scanBudget=PersistentCables.CONFIG.maxGraphNodesProcessedPerTick;
        while(!scans.isEmpty() && scanBudget-->0 && System.nanoTime()<deadline) {
            var it=scans.entrySet().iterator();Scan scan=it.next().getValue();
            if(scan.step()) {topology.endChunkValidation(scan.chunk.getPos().x(),scan.chunk.getPos().z());unverifiedChunks.remove(scan.chunk.getPos().pack());it.remove();}
        }
        topology.tick(PersistentCables.CONFIG.maxGraphNodesProcessedPerTick,deadline);
        if(snapshotJob==null && journal.needsCompaction() && pending.isEmpty() && scans.isEmpty())snapshotJob=topology.beginSnapshot();
        if(snapshotJob!=null && snapshotJob.advance(PersistentCables.CONFIG.maxGraphNodesProcessedPerTick,deadline)) {
            if(!snapshotJob.cancelled())journal.compact(snapshotJob.result());snapshotJob=null;
        }
        checkCapacity();lastNanos=System.nanoTime()-begin;
    }
    private final class Scan {
        final LevelChunk chunk;final boolean[] sections;int section,cell;
        Scan(LevelChunk chunk) {
            this.chunk=chunk;sections=new boolean[chunk.getSections().length];
            for(int i=0;i<sections.length;i++)sections[i]=chunk.getSections()[i].maybeHas(st->st.getBlock() instanceof IInventoryCable);
            for(long packed:dev.tpc.virtual.VirtualNetwork.get(level).positionsInChunk(chunk.getPos().x(),chunk.getPos().z())){
                int at=BlockPos.of(packed).getY()/16-chunk.getMinSectionY();if(at>=0 && at<sections.length)sections[at]=true;
            }
            for(CableRun r:topology.runsInChunk(chunk.getPos().x(),chunk.getPos().z())) {
                int low=Math.max(chunk.getMinY(),Math.min(r.start().y(),r.finish().y()));
                int high=Math.min(chunk.getMaxY()-1,Math.max(r.start().y(),r.finish().y()));
                for(int y=low>>4;y<=high>>4;y++){int index=y-chunk.getMinSectionY();if(index>=0 && index<sections.length)sections[index]=true;}
            }
        }
        boolean step() {
            while(section<sections.length && !sections[section])section++;
            if(section>=sections.length)return true;
            int x=cell&15,z=(cell>>4)&15,y=(cell>>8)&15;
            Point p=new Point(chunk.getPos().getMinBlockX()+x,chunk.getMinY()+section*16+y,chunk.getPos().getMinBlockZ()+z);
            var state=chunk.getBlockState(PersistentCables.block(p));dev.tpc.virtual.VirtualNetwork.get(level).observe(PersistentCables.block(p),state);
            topology.observe(p,read(p));observed++;
            if(++cell==4096){cell=0;section++;}return false;
        }
    }
    public void revalidateLoaded() {topology.invalidateAll();for(LevelChunk chunk:List.copyOf(loaded.values()))loaded(chunk);}
    public Object loadToken(BlockPos p) {return loadTokens.get(ChunkPos.pack(p.getX()>>4,p.getZ()>>4));}
    public int loadedChunkCount(){return loaded.size();}
    public String inspect(BlockPos pos) {return "position="+pos+", record="+topology.find(PersistentCables.point(pos))+", reachable functions="+nodes(pos).size();}
    public String status() {
        return "dimension="+level.dimension().identifier()+", indexed blocks="+topology.cableCount()+", compressed records="+topology.runCount()+
            ", loaded chunks="+loaded.size()+", verifying chunks="+scans.size()+", queued changes="+pending.size()+", topology version="+topology.version()+
            ", cached directed queries="+topology.queryCount()+", pending searches="+topology.pendingQueries()+", graph steps="+topology.searchSteps()+
            ", last maintenance ms="+(lastNanos/1_000_000.0)+", recovery="+recovery+", resident warning="+capacityWarning+", persistence failure="+journal.failure();
    }
    @Override public void close() {
        topology.invalidateAll();if(!pending.isEmpty() || !scans.isEmpty() || !unverifiedChunks.isEmpty())journal.disableCleanRecovery();
        if(journal.failure()==null)journal.compact(new Topology.Edit(List.of(),List.copyOf(topology.snapshot()),topology.version()));
        try{journal.close();}catch(Exception e){PersistentCables.LOG.error("Topology could not be marked clean",e);}
        scans.clear();pending.clear();loaded.clear();
    }
}
