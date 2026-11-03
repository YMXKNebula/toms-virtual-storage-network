package dev.tpc.virtual;

import dev.tpc.PersistentCables;
import dev.tpc.core.*;
import com.tom.storagemod.block.*;
import com.tom.storagemod.block.entity.*;
import com.tom.storagemod.inventory.*;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.*;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Per-dimension coordinator. All authority lives here, not in block entities or UI caches. */
public final class VirtualNetwork implements AutoCloseable {
    private static final Map<ServerLevel,VirtualNetwork> WORLDS=new IdentityHashMap<>();
    public static VirtualNetwork get(ServerLevel level){if(!level.getServer().isSameThread())throw new IllegalStateException("Virtual network off server thread");return WORLDS.computeIfAbsent(level,VirtualNetwork::new);}
    public static void saved(MinecraftServer server,boolean flush){for(var w:WORLDS.values())if(w.level.getServer()==server){
        if(flush && w.healthy() && !w.receipts.isEmpty()){var tag=w.delta(UUID.randomUUID());tag.putBoolean("receipts_cleared",true);w.persist(tag);w.receipts.clear();}
        if(flush)w.savedVersion=w.version;w.journal.flush();
    }}
    public static void closeAll(){for(var w:List.copyOf(WORLDS.values()))w.close();WORLDS.clear();}
    public static void filterChanged(BlockFilter filter,BlockPos p){for(var w:WORLDS.values())if(w.level.getServer().isSameThread()){
        var chunk=w.level.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4);
        if(chunk!=null && BlockFilter.getFilterAt(w.level,p)==filter){w.discovery.changed(p);w.policies.changed(p);}
    }}
    public final ServerLevel level;
    public final WarehouseLedger<ItemVariant> ledger;
    public final VirtualEvents events=new VirtualEvents();
    public final WarehouseGroups groups;
    public final ConnectorDiscovery discovery;
    public final PhysicalPeers peers;
    public final AccessPolicies policies=new AccessPolicies();
    public long mergedBuildHits,mergedBuilds;
    private final LedgerJournal journal;
    private final Map<Long,Set<Long>> chunks=new HashMap<>();
    private final Set<Long> connectors=new HashSet<>();
    private final Set<Long> metadataDirty=new LinkedHashSet<>();
    private final Map<Long,NodeParticipant> participants=new HashMap<>();
    private final Map<Transaction,Batch> transactions=new IdentityHashMap<>();
    private record RemovalPlan(Set<Long> members,Set<Long> neighbors){}
    private final Map<Long,RemovalPlan> removalPlans=new HashMap<>();
    private final Map<Long,UUID> pendingRemovals=new HashMap<>();
    private int physicalChanges;
    private final LinkedHashSet<Long> pendingPhysical=new LinkedHashSet<>(),pendingActivation=new LinkedHashSet<>();
    private final LinkedHashMap<Topology.Lease,LedgerAccess> gateways=new LinkedHashMap<>(16,.75F,true);
    private final Map<Topology.Lease,Set<Long>> activationLeases=new IdentityHashMap<>();
    private final Map<Long,Set<LedgerStorage>> observers=new HashMap<>();
    private final LinkedHashMap<UUID,Overflow> overflow=new LinkedHashMap<>();
    private final ArrayDeque<UUID> dropQueue=new ArrayDeque<>();
    private record DropReceipt(UUID entity,UUID job,long position,long ordinal,ItemVariant variant,int count){}
    private final Map<UUID,DropReceipt> receipts=new LinkedHashMap<>(),receiptDirty=new LinkedHashMap<>();
    private final ArrayDeque<Long> visualUpdates=new ArrayDeque<>();
    private final Map<Long,Boolean> visualStates=new HashMap<>();
    private long version=1,eventSequence,savedVersion=-1;
    private long viewVersion;
    long nextViewVersion(){return ++viewVersion;}
    private long lastCheckpointTick;
    private Checkpoint checkpoint;
    private boolean quarantined,closed;
    private String recovery;

    private VirtualNetwork(ServerLevel level){
        this.level=level;
        ledger=new WarehouseLedger<>(v->v.toStack().getMaxStackSize(),level::getGameTime);
        discovery=new ConnectorDiscovery(level,policies);peers=new PhysicalPeers(this);
        groups=new WarehouseGroups((point,group)->{long p=PersistentCables.block(point).asLong();var n=ledger.get(p);if(n!=null && n.group!=group){n.group=group;metadataDirty.add(p);}});
        PersistentCables.manager(level);
        Path world=level.getServer().getWorldPath(LevelResource.ROOT);
        String dimension=level.dimension().identifier().toString();
        try{
            String identity=Files.readString(world.resolve("data/toms_persistent_cables/world.id"))+"/"+dimension;
            Path path=world.resolve("data/toms_virtual_storage").resolve(HexFormat.of().formatHex(dimension.getBytes(StandardCharsets.UTF_8))+".wal");
            journal=new LedgerJournal(path,identity);
            var restored=journal.open(frame->replay(frame.payload()));quarantined=!restored.trusted();recovery=restored.reason();
            for(var n:ledger.nodes())groups.restore(PersistentCables.point(BlockPos.of(n.position)),n.group);
            metadataDirty.clear();
            PersistentCables.LOG.info("TVSN {}: {}; {} capacity nodes, {} overflow jobs",dimension,recovery,ledger.nodeCount(),overflow.size());
        }catch(IOException ex){throw new IllegalStateException("Cannot open virtual ledger",ex);}
        events.subscribe(event->{if(event instanceof VirtualEvents.InventoryCommitted commit){var affected=Collections.newSetFromMap(new IdentityHashMap<LedgerStorage,Boolean>());for(long p:commit.barrels())affected.addAll(observers.getOrDefault(p,Set.of()));affected.forEach(LedgerStorage::changed);}});
    }
    public boolean healthy(){return level.getServer().isSameThread() && !closed && !quarantined && journal.healthy();}
    public boolean acceptingTransactions(){return healthy() && journal.acceptingTransactions();}
    public Set<Long> positionsInChunk(int x,int z){return Collections.unmodifiableSet(chunks.getOrDefault(ChunkPos.pack(x,z),Set.of()));}
    public boolean knownConnector(long position){return connectors.contains(position);}
    public void loaded(LevelChunk chunk){
        discovery.chunk(chunk.getPos().x(),chunk.getPos().z());peers.chunk(chunk.getPos().x(),chunk.getPos().z());
        policies.chunk(chunk.getPos().x(),chunk.getPos().z());
        for(long p:positionsInChunk(chunk.getPos().x(),chunk.getPos().z())){ledger.online(p,true);pendingActivation.add(p);}
    }
    public void unloaded(LevelChunk chunk){discovery.chunk(chunk.getPos().x(),chunk.getPos().z());peers.chunk(chunk.getPos().x(),chunk.getPos().z());policies.chunk(chunk.getPos().x(),chunk.getPos().z());for(long p:positionsInChunk(chunk.getPos().x(),chunk.getPos().z()))ledger.online(p,false);}
    private void index(long p){var b=BlockPos.of(p);chunks.computeIfAbsent(ChunkPos.pack(b.getX()>>4,b.getZ()>>4),k->new LinkedHashSet<>()).add(p);}
    private void unindex(long p){var b=BlockPos.of(p);long key=ChunkPos.pack(b.getX()>>4,b.getZ()>>4);var set=chunks.get(key);if(set!=null){set.remove(p);if(set.isEmpty())chunks.remove(key);}}

    public void beforeChange(BlockPos pos,BlockState old,BlockState next){
        if(old.getBlock()==VirtualContent.BARREL && next.getBlock()!=VirtualContent.BARREL && ledger.get(pos.asLong())!=null){
            // Capture an already verified PRE-cut membership. A fresh query rooted at
            // this barrel can exceed the immediate budget even though a connector's
            // view of exactly this warehouse is complete. Pending is not "no capacity".
            Set<Long> candidates=null;
            for(var gateway:gateways.values())if(gateway.storage.valid() && gateway.storage.scope.contains(pos.asLong())){candidates=gateway.storage.scope.membership();break;}
            if(candidates==null)for(var entry:activationLeases.entrySet())if(entry.getKey().valid() && entry.getValue().contains(pos.asLong())){candidates=entry.getValue();break;}
            if(candidates==null){
                var lease=PersistentCables.manager(level).query(pos);var found=new HashSet<Long>();
                if(lease.valid())for(var point:lease.functions()){long p=PersistentCables.block(point).asLong();if(ledger.get(p)!=null)found.add(p);}
                if(lease.valid())candidates=Set.copyOf(found);
            }
            // Scope/activation memberships are immutable; sharing them avoids copying
            // the whole warehouse for each removed barrel. Settlement filters survivors.
            var neighbors=new LinkedHashSet<Long>();var spec=PersistentCables.manager(level).read(PersistentCables.point(pos));
            if(spec!=null)for(Direction direction:Direction.values())if((spec.outgoing() & (1<<direction.ordinal()))!=0)neighbors.add(pos.relative(direction).asLong());
            removalPlans.put(pos.asLong(),new RemovalPlan(candidates,Set.copyOf(neighbors)));
            pendingRemovals.putIfAbsent(pos.asLong(),ledger.get(pos.asLong()).incarnation);
        }
    }
    public void changed(BlockPos pos,BlockState old,BlockState next){
        if(old.getBlock()==next.getBlock() && next.getBlock()==VirtualContent.BARREL)return; // Display state never changes topology or inventory.
        discovery.changed(pos);
        policies.changed(pos);
        if(!(old.getBlock() instanceof IInventoryCable) && !(next.getBlock() instanceof IInventoryCable))return;
        // Neighbour-dependent warehouse ports must change in the same tick, before any borrowed handle can commit.
        for(Direction d:Direction.values()){
            var adjacent=pos.relative(d);var chunk=level.getChunkSource().getChunkNow(adjacent.getX()>>4,adjacent.getZ()>>4);
            if(chunk!=null){var block=chunk.getBlockState(adjacent).getBlock();if(block==VirtualContent.BARREL || block instanceof InventoryCableConnectorBlock)PersistentCables.manager(level).changed(adjacent);}
        }
        if(Transaction.isOpen() || physicalChanges++>=PersistentCables.CONFIG.maxTopologyUpdatesPerTick){pendingPhysical.add(pos.asLong());return;}
        observe(pos,next);
        for(var gateway:List.copyOf(gateways.values()))if(!gateway.storage.valid())pendingActivation.addAll(gateway.storage.scope.membership());
        for(Direction d:Direction.values())if(ledger.get(pos.relative(d).asLong())!=null)pendingActivation.add(pos.relative(d).asLong());
    }
    public void observe(BlockPos pos,BlockState state){
        long p=pos.asLong();boolean connector=state.getBlock() instanceof InventoryConnectorBlock || state.getBlock() instanceof InventoryCableConnectorBlock;
        if(healthy() && connector!=connectors.contains(p)){
            if(connector)connectors.add(p);else connectors.remove(p);
            peers.chunk(pos.getX()>>4,pos.getZ()>>4);
            var tag=delta(UUID.randomUUID());tag.putLongArray(connector?"connectors_added":"connectors_removed",new long[]{p});persist(tag);
        }
        if(!healthy())return;
        var previous=ledger.get(p);var removed=pendingRemovals.remove(p);if(previous!=null && removed!=null && previous.incarnation.equals(removed))removeBarrel(p);
        if(state.getBlock()==VirtualContent.BARREL){
            var node=ledger.get(p);
            if(node==null){
                node=ledger.register(p,UUID.randomUUID(),0,Map.of());index(p);node.group=groups.register(PersistentCables.point(pos));
                metadataDirty.add(p); // Coalesce bulk placement; inventory commits include each touched node themselves.
                var stale=gateways.entrySet().iterator();while(stale.hasNext()){var entry=stale.next();if(entry.getKey().functions().contains(PersistentCables.point(pos))){retire(entry.getValue().storage);stale.remove();}}
                events.publish(new VirtualEvents.BarrelRegistered(id(UUID.randomUUID()),p,node.group));
            }
            ledger.online(p,true);pendingActivation.add(p);
        }else if(ledger.get(p)!=null)removeBarrel(p);
    }
    public void topologyChanged(){
        peers.changed();
        for(var gateway:gateways.values())if(!gateway.storage.valid())pendingActivation.addAll(gateway.storage.scope.membership());
        var it=activationLeases.entrySet().iterator();while(it.hasNext()){
            var old=it.next();if(old.getKey().valid())continue;
            for(long p:old.getValue()){pendingActivation.add(p);visual(p,false);}it.remove();
        }
    }

    public LedgerAccess gateway(BlockPos origin){
        if(!healthy())return null;var lease=PersistentCables.manager(level).query(origin);if(!lease.valid())return null;
        var cached=gateways.get(lease);if(cached!=null && cached.storage.valid())return cached;
        var members=new LinkedHashSet<Long>();for(var point:lease.functions()){long p=PersistentCables.block(point).asLong();if(ledger.get(p)!=null)members.add(p);}
        if(members.isEmpty())return null;
        var scope=ledger.scope(members);var storage=new LedgerStorage(this,scope,lease::valid);var access=new LedgerAccess(storage);
        gateways.put(lease,access);for(long p:members)observers.computeIfAbsent(p,k->Collections.newSetFromMap(new IdentityHashMap<>())).add(storage);
        while(gateways.size()>PersistentCables.CONFIG.maxCachedQueries){var iterator=gateways.entrySet().iterator();var old=iterator.next();iterator.remove();retire(old.getValue().storage);}
        return access;
    }
    public IInventoryConnector gatewayConnector(IInventoryConnector source){
        if(!(source instanceof net.minecraft.world.level.block.entity.BlockEntity be))return null;
        var access=gateway(be.getBlockPos());if(access==null)return null;
        var filter=BlockFilter.getFilterAt(level,be.getBlockPos());IInventoryAccess filtered=filter==null?access:new FilteredLedgerAccess(access,filter,level);
        return new IInventoryConnector(){
            public IInventoryAccess getMergedHandler(){return filtered;}
            public Collection<IInventoryAccess> getConnectedInventories(){return List.of(filtered);}
            public Collection<IInventoryConnector> getConnectedConnectors(){return List.of();}
            public boolean hasConnectedInventories(){return access.storage.valid();}
        };
    }
    private void retire(LedgerStorage storage){for(long p:storage.scope.membership()){var set=observers.get(p);if(set!=null){set.remove(storage);if(set.isEmpty())observers.remove(p);}}storage.close();}

    private final class NodeParticipant extends SnapshotParticipant<Map<ItemVariant,Long>>{
        final WarehouseLedger.Node<ItemVariant> node;
        NodeParticipant(WarehouseLedger.Node<ItemVariant> node){this.node=node;}
        protected Map<ItemVariant,Long> createSnapshot(){return node.snapshot();}
        protected void readSnapshot(Map<ItemVariant,Long> before){if(ledger.get(node.position)!=node){quarantined=true;throw new IllegalStateException("Capacity changed inside an open inventory transaction");}ledger.restore(node.position,before);}
    }
    private static final class Batch{final UUID id=UUID.randomUUID();final Map<Long,Map<ItemVariant,Long>> before=new LinkedHashMap<>();}
    void touch(WarehouseLedger.Node<ItemVariant> node,TransactionContext tx){
        if(!healthy())throw new IllegalStateException("Ledger unavailable");
        Transaction root=tx.getOpenTransaction(0);var batch=transactions.get(root);
        if(batch==null){batch=new Batch();transactions.put(root,batch);var captured=batch;tx.addOuterCloseCallback(result->finish(root,captured,result));}
        if(!batch.before.containsKey(node.position))batch.before.put(node.position,node.snapshot());
        participants.computeIfAbsent(node.position,p->new NodeParticipant(node)).updateSnapshots(tx);
    }
    private void finish(Transaction root,Batch batch,TransactionContext.Result result){
        transactions.remove(root);if(result!=TransactionContext.Result.COMMITTED)return;
        var changed=new LinkedHashSet<Long>();batch.before.forEach((p,before)->{var node=ledger.get(p);if(node==null || !before.equals(node.contents()))changed.add(p);});
        if(changed.isEmpty())return;
        persistNodes(changed,Set.of(),List.of(),Set.of(),batch.id);
        events.publish(new VirtualEvents.InventoryCommitted(id(batch.id),changed));
    }

    private void removeBarrel(long p){
        var removed=ledger.remove(p);if(removed==null)return;
        groups.remove(PersistentCables.point(BlockPos.of(p)));unindex(p);participants.remove(p);metadataDirty.remove(p);
        var plan=removalPlans.remove(p);
        var candidates=new LinkedHashSet<Long>();if(plan!=null && plan.members!=null)candidates.addAll(plan.members);candidates.removeIf(q->ledger.get(q)==null);
        var affected=new LinkedHashSet<Long>();var excess=new LinkedHashMap<ItemVariant,Long>();
        try(var scope=ledger.scope(candidates)){
            for(var entry:removed.contents().entrySet()){
                long placed=scope.insert(entry.getKey(),entry.getValue(),n->affected.add(n.position));
                if(placed<entry.getValue())excess.put(entry.getKey(),entry.getValue()-placed);
            }
        }
        var jobs=new ArrayList<Overflow>();UUID tx=UUID.randomUUID();
        if(!excess.isEmpty()){
            var job=new Overflow(tx,p,excess);
            if(plan!=null && plan.members==null)job.relocationSeeds=plan.neighbors;
            overflow.put(tx,job);dropQueue.addLast(tx);jobs.add(job);
        }
        persistNodes(affected,Set.of(p),jobs,Set.of(),tx);
        events.publish(new VirtualEvents.BarrelRemoved(id(tx),p,removed.group));
        events.publish(new VirtualEvents.InventoryCommitted(id(tx),affected));
        if(!jobs.isEmpty())events.publish(new VirtualEvents.OverflowChanged(id(tx),tx,false));
    }
    private static final class Overflow{
        final UUID id;final long position;final LinkedHashMap<ItemVariant,Long> remaining;
        long ordinal;UUID pendingEntity;ItemVariant pendingVariant;int pendingCount;
        Set<Long> relocationSeeds;
        Overflow(UUID id,long p,Map<ItemVariant,Long> remaining){this.id=id;position=p;this.remaining=new LinkedHashMap<>(remaining);}
    }
    private boolean relocate(Overflow job){
        var manager=PersistentCables.manager(level);var candidates=new LinkedHashSet<Long>();
        for(long seed:job.relocationSeeds){
            var p=BlockPos.of(seed);var chunk=level.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4);
            if(chunk!=null && !(chunk.getBlockState(p).getBlock() instanceof IInventoryCable))continue;
            if(manager.topology.find(PersistentCables.point(p))==null && ledger.get(seed)==null && !knownConnector(seed))continue;
            var lease=manager.query(p);if(!lease.valid())return false;
            for(var point:lease.functions()){long q=PersistentCables.block(point).asLong();if(ledger.get(q)!=null)candidates.add(q);}
        }
        var affected=new LinkedHashSet<Long>();
        try(var scope=ledger.scope(candidates)){
            var items=job.remaining.entrySet().iterator();
            while(items.hasNext()){
                var entry=items.next();long placed=scope.insert(entry.getKey(),entry.getValue(),n->affected.add(n.position));
                if(placed==entry.getValue())items.remove();else entry.setValue(entry.getValue()-placed);
            }
        }
        job.relocationSeeds=null;
        if(job.remaining.isEmpty()){
            overflow.remove(job.id);persistNodes(affected,Set.of(),List.of(),Set.of(job.id),job.id);
            events.publish(new VirtualEvents.OverflowChanged(id(job.id),job.id,true));
        }else persistNodes(affected,Set.of(),List.of(job),Set.of(),job.id);
        events.publish(new VirtualEvents.InventoryCommitted(id(job.id),affected));return true;
    }
    private void drops(){
        int budget=PersistentCables.CONFIG.maxOverflowEntitiesPerTick;
        int relocations=1;
        int attempts=Math.max(budget,PersistentCables.CONFIG.maxTopologyUpdatesPerTick);
        while(budget>0 && attempts-->0 && !dropQueue.isEmpty()){
            UUID jobId=dropQueue.removeFirst();var job=overflow.get(jobId);if(job==null)continue;
            if(job.relocationSeeds!=null){
                if(relocations<=0){dropQueue.addLast(jobId);continue;}
                relocations--;
                if(!relocate(job)){dropQueue.addLast(jobId);continue;}
                if(job.remaining.isEmpty())continue;
            }
            BlockPos p=BlockPos.of(job.position);
            if(level.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4)==null){dropQueue.addLast(jobId);continue;}
            if(job.remaining.isEmpty()){overflow.remove(job.id);persistNodes(Set.of(),Set.of(),List.of(),Set.of(job.id),job.id);continue;}
            if(job.pendingEntity==null){
                var first=job.remaining.entrySet().iterator().next();job.pendingVariant=first.getKey();job.pendingCount=(int)Math.min(first.getValue(),first.getKey().toStack().getMaxStackSize());
                job.pendingEntity=UUID.nameUUIDFromBytes((job.id+":"+job.ordinal).getBytes(StandardCharsets.UTF_8));persistNodes(Set.of(),Set.of(),List.of(job),Set.of(),job.id);
            }
            if(level.getEntityInAnyDimension(job.pendingEntity)==null){
                var random=new Random(job.pendingEntity.getLeastSignificantBits());
                var entity=new ItemEntity(level,p.getX()+.2+random.nextDouble()*.6,p.getY()+.25+random.nextDouble()*.5,p.getZ()+.2+random.nextDouble()*.6,job.pendingVariant.toStack(job.pendingCount));
                entity.setUUID(job.pendingEntity);entity.addTag("tvsn:"+job.id+":"+job.ordinal);
                if(!level.addFreshEntity(entity)){dropQueue.addLast(jobId);continue;}
            }
            budget--;
            var receipt=new DropReceipt(job.pendingEntity,job.id,job.position,job.ordinal,job.pendingVariant,job.pendingCount);receipts.put(receipt.entity,receipt);receiptDirty.put(receipt.entity,receipt);
            long left=job.remaining.get(job.pendingVariant)-job.pendingCount;if(left==0)job.remaining.remove(job.pendingVariant);else job.remaining.put(job.pendingVariant,left);
            job.pendingEntity=null;job.pendingVariant=null;job.pendingCount=0;job.ordinal++;
            if(job.remaining.isEmpty()){overflow.remove(job.id);persistNodes(Set.of(),Set.of(),List.of(),Set.of(job.id),job.id);events.publish(new VirtualEvents.OverflowChanged(id(job.id),job.id,true));}
            else {persistNodes(Set.of(),Set.of(),List.of(job),Set.of(),job.id);dropQueue.addLast(jobId);}
        }
    }
    public void tick(){
        if(!healthy() || Transaction.isOpen())return;
        physicalChanges=0;
        int physicalBudget=PersistentCables.CONFIG.maxTopologyUpdatesPerTick;
        while(!pendingPhysical.isEmpty() && physicalBudget-->0){var it=pendingPhysical.iterator();long p=it.next();it.remove();var pos=BlockPos.of(p);var c=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);if(c!=null)observe(pos,c.getBlockState(pos));else pendingPhysical.add(p);}
        groups.tick(PersistentCables.CONFIG.maxTopologyUpdatesPerTick);
        if(!metadataDirty.isEmpty()){
            var changed=new LinkedHashSet<Long>();var dirty=metadataDirty.iterator();while(dirty.hasNext() && changed.size()<PersistentCables.CONFIG.maxTopologyUpdatesPerTick){changed.add(dirty.next());dirty.remove();}
            var batch=new LinkedHashSet<Long>();for(long p:changed){batch.add(p);if(batch.size()==64){persistNodes(batch,Set.of(),List.of(),Set.of(),UUID.randomUUID());batch.clear();}}
            if(!batch.isEmpty())persistNodes(batch,Set.of(),List.of(),Set.of(),UUID.randomUUID());
            events.publish(new VirtualEvents.GroupChanged(id(UUID.randomUUID()),changed));
        }
        activation();drops();checkpointTick();if(level.getGameTime()%20==0)journal.flush();
    }
    private void activation(){
        int budget=PersistentCables.CONFIG.maxTopologyUpdatesPerTick;
        if(!pendingActivation.isEmpty()){
            long root=pendingActivation.iterator().next();
            if(ledger.get(root)==null)pendingActivation.remove(root);
            else{
                var lease=PersistentCables.manager(level).query(BlockPos.of(root));
                if(lease.valid()){
                    boolean active=lease.functions().stream().anyMatch(p->connectors.contains(PersistentCables.block(p).asLong()));
                    var members=new LinkedHashSet<Long>();for(var point:lease.functions()){
                        long p=PersistentCables.block(point).asLong();if(ledger.get(p)==null)continue;
                        members.add(p);pendingActivation.remove(p);visual(p,active);
                    }
                    activationLeases.put(lease,Set.copyOf(members));
                }
            }
        }
        while(budget-->0 && !visualUpdates.isEmpty()){
            long p=visualUpdates.remove();boolean active=visualStates.remove(p);var pos=BlockPos.of(p);var c=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
            if(c!=null){var state=c.getBlockState(pos);if(state.getBlock()==VirtualContent.BARREL && state.getValue(TomBarrelBlock.ACTIVE)!=active)level.setBlock(pos,state.setValue(TomBarrelBlock.ACTIVE,active),2);}
        }
    }
    private void visual(long p,boolean active){if(!visualStates.containsKey(p))visualUpdates.add(p);visualStates.put(p,active);}
    public String describe(BlockPos pos){var n=ledger.get(pos.asLong());return !healthy()?"TVSN: 库存已隔离，"+recovery:n==null?"TVSN: 等待登记":"TVSN: 仓储组 "+n.group+"，已用 "+ledger.usedSlots(pos.asLong())+" / 64 格";}
    public String status(){return "TVSN: barrels="+ledger.nodeCount()+", groups="+groups.groupCount()+", ledgerVersion="+version+", gateways="+gateways.size()+", overflowJobs="+overflow.size()+", quarantined="+!healthy()+", recovery="+recovery;}
    public long version(){return version;}
    public int overflowJobs(){return overflow.size();}
    public int unresolvedDropReceipts(){return receipts.size();}
    public Path exportRecovery()throws IOException{
        if(!level.getServer().isSameThread() || Transaction.isOpen())throw new IllegalStateException("Export requires the server thread outside an inventory transaction");
        Path root=level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/toms_virtual_storage/recovery").resolve(UUID.randomUUID().toString());Files.createDirectories(root);
        var tag=new CompoundTag();tag.putString("dimension",level.dimension().identifier().toString());tag.putString("status",status());tag.putLong("version",version);
        var nodes=new ListTag();ledger.nodes().forEach(n->nodes.add(node(n)));tag.put("nodes",nodes);var jobs=new ListTag();overflow.values().forEach(j->jobs.add(job(j)));tag.put("overflow",jobs);
        var audit=new ListTag();receipts.values().forEach(r->audit.add(receipt(r)));tag.put("drop_receipts",audit);
        Files.write(root.resolve("recovered-state.nbt"),encode(tag));Files.writeString(root.resolve("recovered-state.snbt"),tag.toString());journal.export(root.resolve("original-ledger.wal"));
        Files.writeString(root.resolve("README.txt"),"Diagnostic export only. The original ledger and world are unchanged. Replayed data and drop receipts may disagree with saved entities or player inventories after an unclean shutdown. Do not import or respawn these items automatically. Restore a matching complete world backup, or reconcile the retained WAL, entity UUID receipts and player inventories offline before recovery.\n"+status()+"\n");return root;
    }
    private VirtualEvents.Id id(UUID transaction){return new VirtualEvents.Id(transaction,++eventSequence);}

    private CompoundTag delta(UUID transaction){var tag=new CompoundTag();tag.putString("transaction",transaction.toString());tag.putLong("version",++version);return tag;}
    private ListTag items(Map<ItemVariant,Long> entries){
        var list=new ListTag();var ops=level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        entries.forEach((variant,quantity)->{var entry=new CompoundTag();entry.put("variant",ItemVariant.CODEC.encodeStart(ops,variant).getOrThrow());entry.putLong("quantity",quantity);list.add(entry);});return list;
    }
    private Map<ItemVariant,Long> readItems(ListTag entries){
        var result=new LinkedHashMap<ItemVariant,Long>();var ops=level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        for(var value:entries){if(!(value instanceof CompoundTag entry))throw new IllegalArgumentException("Invalid item record");var variant=ItemVariant.CODEC.parse(ops,entry.get("variant")).getOrThrow();long amount=entry.getLong("quantity").orElseThrow();if(variant.isBlank() || amount<=0 || result.put(variant,amount)!=null)throw new IllegalArgumentException("Invalid allocation");}return result;
    }
    private CompoundTag node(WarehouseLedger.Node<ItemVariant> node){return node(node.position,node.incarnation,node.group,node.snapshot());}
    private CompoundTag node(long position,UUID incarnation,long group,Map<ItemVariant,Long> contents){var tag=new CompoundTag();tag.putLong("position",position);tag.putString("incarnation",incarnation.toString());tag.putLong("group",group);tag.put("items",items(contents));return tag;}
    private CompoundTag job(Overflow job){
        return job(new FrozenOverflow(job.id,job.position,job.ordinal,Map.copyOf(job.remaining),job.pendingEntity,job.pendingVariant,job.pendingCount,job.relocationSeeds));
    }
    private CompoundTag job(FrozenOverflow job){
        var tag=new CompoundTag();tag.putString("id",job.id.toString());tag.putLong("position",job.position);tag.putLong("ordinal",job.ordinal);tag.put("items",items(job.remaining));
        if(job.relocationSeeds!=null)tag.putLongArray("relocation_seeds",job.relocationSeeds.stream().mapToLong(Long::longValue).toArray());
        if(job.pendingEntity!=null){tag.putString("pending",job.pendingEntity.toString());tag.put("pending_variant",ItemVariant.CODEC.encodeStart(level.registryAccess().createSerializationContext(NbtOps.INSTANCE),job.pendingVariant).getOrThrow());tag.putInt("pending_count",job.pendingCount);}return tag;
    }
    private void persistNodes(Collection<Long> changed,Collection<Long> removed,Collection<Overflow> jobs,Collection<UUID> completed,UUID transaction){
        var tag=delta(transaction);var records=new ListTag();for(long p:changed){var n=ledger.get(p);if(n!=null)records.add(node(n));}tag.put("nodes",records);
        tag.putLongArray("removed",removed.stream().mapToLong(Long::longValue).toArray());var drops=new ListTag();jobs.forEach(j->drops.add(job(j)));tag.put("overflow",drops);
        var done=new ListTag();completed.forEach(id->done.add(StringTag.valueOf(id.toString())));tag.put("completed",done);persist(tag);
        receiptDirty.clear();
    }
    private void persist(CompoundTag tag){
        try{
            if(!receiptDirty.isEmpty()){var pending=new ListTag();receiptDirty.values().forEach(r->pending.add(receipt(r)));tag.put("receipts_added",pending);}
            byte[] payload=encode(tag);journal.append(payload);
            if(checkpoint!=null){checkpoint.tailBytes+=payload.length;if(checkpoint.tailBytes>PersistentCables.CONFIG.maxCheckpointTailBytes)checkpoint=null;else checkpoint.tail.add(payload);}
        }
        catch(IOException | RuntimeException ex){quarantined=true;recovery="persistence failure: "+ex;throw new IllegalStateException("TVSN inventory quarantined to prevent unsafe continuation",ex);}
    }
    private static byte[] encode(CompoundTag tag)throws IOException{var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){NbtIo.write(tag,out);}return bytes.toByteArray();}
    private record FrozenNode(long position,UUID incarnation,long group,Map<ItemVariant,Long> contents){}
    private record FrozenOverflow(UUID id,long position,long ordinal,Map<ItemVariant,Long> remaining,UUID pendingEntity,ItemVariant pendingVariant,int pendingCount,Set<Long> relocationSeeds){}
    private CompoundTag receipt(DropReceipt r){var tag=new CompoundTag();tag.putString("entity",r.entity.toString());tag.putString("job",r.job.toString());tag.putLong("position",r.position);tag.putLong("ordinal",r.ordinal);tag.putInt("count",r.count);tag.put("variant",ItemVariant.CODEC.encodeStart(level.registryAccess().createSerializationContext(NbtOps.INSTANCE),r.variant).getOrThrow());return tag;}
    private final class Checkpoint {
        final long capturedVersion=version;
        final List<FrozenNode> nodes=ledger.nodes().stream().map(n->new FrozenNode(n.position,n.incarnation,n.group,n.snapshot())).toList();
        final List<FrozenOverflow> jobs=overflow.values().stream().map(j->new FrozenOverflow(j.id,j.position,j.ordinal,Map.copyOf(j.remaining),j.pendingEntity,j.pendingVariant,j.pendingCount,j.relocationSeeds)).toList();
        final List<DropReceipt> dropReceipts=List.copyOf(receipts.values());
        final List<byte[]> records=new ArrayList<>(),tail=new ArrayList<>();
        long tailBytes;int nodeCursor,jobCursor,receiptCursor;
        Checkpoint()throws IOException{
            var header=part();header.putLongArray("connectors_added",connectors.stream().mapToLong(Long::longValue).toArray());records.add(encode(header));
        }
        CompoundTag part(){var tag=new CompoundTag();tag.putLong("version",capturedVersion);tag.putString("transaction",UUID.randomUUID().toString());return tag;}
        boolean advance(int budget,long deadline)throws IOException{
            while(budget>0 && System.nanoTime()<deadline && (nodeCursor<nodes.size() || jobCursor<jobs.size() || receiptCursor<dropReceipts.size())){
                var tag=part();var blockRecords=new ListTag();var dropRecords=new ListTag();var receiptRecords=new ListTag();int count=0;
                while(nodeCursor<nodes.size() && budget>0 && count<64 && System.nanoTime()<deadline){
                    budget--;count++;
                    var n=nodes.get(nodeCursor++);blockRecords.add(node(n.position,n.incarnation,n.group,n.contents));
                }
                while(jobCursor<jobs.size() && budget>0 && count<64 && System.nanoTime()<deadline){budget--;count++;dropRecords.add(job(jobs.get(jobCursor++)));}
                while(receiptCursor<dropReceipts.size() && budget>0 && count<64 && System.nanoTime()<deadline){budget--;count++;receiptRecords.add(receipt(dropReceipts.get(receiptCursor++)));}
                if(count>0){tag.put("nodes",blockRecords);tag.put("overflow",dropRecords);tag.put("receipts_added",receiptRecords);records.add(encode(tag));}
            }
            return nodeCursor==nodes.size() && jobCursor==jobs.size() && receiptCursor==dropReceipts.size();
        }
    }
    public boolean requestCheckpoint(){
        if(!healthy() || Transaction.isOpen() || checkpoint!=null)return false;
        try{checkpoint=new Checkpoint();lastCheckpointTick=level.getGameTime();return true;}catch(IOException ex){quarantined=true;recovery="checkpoint capture failure: "+ex;return false;}
    }
    public long checkpointCount(){return journal.checkpoints();}
    private void checkpointTick(){
        if(checkpoint==null && level.getGameTime()-lastCheckpointTick>=PersistentCables.CONFIG.checkpointIntervalTicks)requestCheckpoint();
        if(checkpoint==null)return;
        try{if(checkpoint.advance(PersistentCables.CONFIG.checkpointNodesPerTick,System.nanoTime()+(long)(PersistentCables.CONFIG.maxTopologyProcessingTimeMs*1_000_000))){
            var complete=new ArrayList<>(checkpoint.records);complete.addAll(checkpoint.tail);journal.checkpoint(complete);checkpoint=null;
        }}catch(IOException ex){checkpoint=null;quarantined=true;recovery="checkpoint failure: "+ex;}
    }
    private void replay(byte[] bytes){
        try(var input=new DataInputStream(new ByteArrayInputStream(bytes))){
            var tag=NbtIo.read(input);version=Math.max(version,tag.getLong("version").orElseThrow());
            if(tag.getBoolean("receipts_cleared").orElse(false))receipts.clear();
            for(var value:tag.getListOrEmpty("receipts_added")){
                var r=(CompoundTag)value;var receipt=new DropReceipt(UUID.fromString(r.getString("entity").orElseThrow()),UUID.fromString(r.getString("job").orElseThrow()),r.getLong("position").orElseThrow(),r.getLong("ordinal").orElseThrow(),ItemVariant.CODEC.parse(level.registryAccess().createSerializationContext(NbtOps.INSTANCE),r.get("variant")).getOrThrow(),r.getInt("count").orElseThrow());
                if(receipt.count<1 || receipt.variant.isBlank())throw new IllegalArgumentException("Invalid drop receipt");receipts.put(receipt.entity,receipt);
            }
            for(long p:tag.getLongArray("removed").orElse(new long[0])){ledger.remove(p);unindex(p);}
            for(var value:tag.getListOrEmpty("nodes")){
                var entry=(CompoundTag)value;long p=entry.getLong("position").orElseThrow();UUID incarnation=UUID.fromString(entry.getString("incarnation").orElseThrow());long group=entry.getLong("group").orElseThrow();if(group<1)throw new IllegalArgumentException("Invalid group identity");
                ledger.remove(p);ledger.register(p,incarnation,group,readItems(entry.getListOrEmpty("items")));index(p);
            }
            for(long p:tag.getLongArray("connectors_added").orElse(new long[0]))connectors.add(p);
            for(long p:tag.getLongArray("connectors_removed").orElse(new long[0]))connectors.remove(p);
            for(var value:tag.getListOrEmpty("completed"))overflow.remove(UUID.fromString(value.asString().orElseThrow()));
            for(var value:tag.getListOrEmpty("overflow")){
                var entry=(CompoundTag)value;UUID id=UUID.fromString(entry.getString("id").orElseThrow());var job=new Overflow(id,entry.getLong("position").orElseThrow(),readItems(entry.getListOrEmpty("items")));job.ordinal=entry.getLong("ordinal").orElseThrow();
                if(entry.contains("relocation_seeds")){
                    if(entry.contains("pending"))throw new IllegalArgumentException("Relocation cannot already have an emitted drop intent");
                    var seeds=entry.getLongArray("relocation_seeds").orElseThrow();if(seeds.length>6)throw new IllegalArgumentException("Invalid relocation neighborhood");
                    var checked=new LinkedHashSet<Long>();for(long seed:seeds){if(BlockPos.of(seed).distManhattan(BlockPos.of(job.position))!=1)throw new IllegalArgumentException("Invalid relocation port");checked.add(seed);}job.relocationSeeds=Set.copyOf(checked);
                }
                if(entry.contains("pending")){job.pendingEntity=UUID.fromString(entry.getString("pending").orElseThrow());job.pendingVariant=ItemVariant.CODEC.parse(level.registryAccess().createSerializationContext(NbtOps.INSTANCE),entry.get("pending_variant")).getOrThrow();job.pendingCount=entry.getInt("pending_count").orElseThrow();if(job.pendingCount<1 || job.remaining.getOrDefault(job.pendingVariant,0L)<job.pendingCount)throw new IllegalArgumentException("Invalid overflow receipt");}
                if(!overflow.containsKey(id))dropQueue.addLast(id);overflow.put(id,job);
            }
        }catch(IOException ex){throw new IllegalArgumentException("Ledger payload cannot be decoded",ex);}
    }
    @Override public void close(){
        if(closed)return;
        try{if(healthy() && savedVersion==version && transactions.isEmpty() && pendingPhysical.isEmpty() && metadataDirty.isEmpty())journal.close();else journal.closeUnclean();}
        catch(IOException ex){PersistentCables.LOG.error("TVSN ledger could not be closed cleanly",ex);}
        finally{closed=true;for(var access:gateways.values())retire(access.storage);gateways.clear();}
    }
}
