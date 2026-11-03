package dev.tpc.scale;

import dev.tpc.PersistentCables;
import dev.tpc.virtual.*;
import com.tom.storagemod.Content;
import com.tom.storagemod.block.AbstractStorageTerminalBlock;
import com.tom.storagemod.block.entity.*;
import com.tom.storagemod.inventory.StoredItemStack;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import java.nio.file.*;
import java.util.*;

/** Test-only normal DedicatedServer. No production save, no fake TPS from accelerated GameTest. */
public final class ScaleRun implements ModInitializer {
    public static String phase="startup";
    private static final Map<String,List<long[]>> samples=new LinkedHashMap<>();
    private static final List<Map<String,Object>> rows=new ArrayList<>();
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("tvsn-scale");
    private static final BlockPos BASE=new BlockPos(0,80,0);
    private final int ticks=Integer.getInteger("tvsn.scaleTicks",300);
    private final List<Long> queryNanos=new ArrayList<>();
    private ServerLevel level;private VirtualNetwork network;private InventoryConnectorBlockEntity connector;private StorageTerminalBlockEntity terminal;
    private int stage,age,placed;private long total,checkpointBefore;private boolean complete;
    public void onInitialize(){
        if(Boolean.getBoolean("tvsn.crashBoundary") || Boolean.getBoolean("tvsn.large") || Boolean.getBoolean("tvsn.queuedRestart"))return;
        ServerLifecycleEvents.SERVER_STARTED.register(s->{level=s.overworld();begin("no_network");});
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(s->write());
    }
    public static void sample(String p,long start,long end){
        if(p.equals(phase) && Set.of("no_network","10000_empty_idle","10000_terminal_open","10000_sparse_io","10000_checkpoint").contains(p))samples.computeIfAbsent(p,k->new ArrayList<>()).add(new long[]{start,end-start,Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()});
    }
    private void begin(String p){phase=p;age=0;LOG.info("PHASE {}: {} normal paced ticks",p,ticks);}
    private VirtualNetwork world(){return network==null?(network=VirtualNetwork.get(level)):network;}
    private void finish(){
        var a=samples.getOrDefault(phase,List.of());var row=new LinkedHashMap<String,Object>();row.put("phase",phase);row.put("samples",a.size());
        if(!a.isEmpty()){
            long[] sorted=a.stream().mapToLong(v->v[1]).sorted().toArray();double seconds=(a.getLast()[0]-a.getFirst()[0])/1e9;
            row.put("observedTPS",(a.size()-1)/seconds);row.put("meanMSPT",Arrays.stream(sorted).average().orElseThrow()/1e6);row.put("p95MSPT",sorted[(int)Math.ceil(sorted.length*.95)-1]/1e6);row.put("peakMSPT",sorted[sorted.length-1]/1e6);row.put("peakObservedHeapBytes",a.stream().mapToLong(v->v[2]).max().orElseThrow());
        }
        row.put("ledgerCandidates",world().ledger.candidatesVisited());row.put("nativeDiscoveryScans",world().discovery.scans);row.put("discoverySleeps",world().discovery.sleeps);row.put("mergedBuilds",world().mergedBuilds);row.put("mergedBuildHits",world().mergedBuildHits);
        rows.add(row);LOG.info("RESULT {}",row);phase="transition";write();
    }
    private void tick(MinecraftServer server){
        if(level==null || complete)return;age++;
        try{switch(stage){
            case 0->{if(age>ticks){finish();phase="fixture_construction";age=0;stage=1;for(int x=-1;x<=6;x++)for(int z=-1;z<=6;z++){level.setChunkForced(x,z,true);level.getChunk(x,z);}}}
            case 1->{int end=Math.min(10000,placed+256);for(;placed<end;placed++){var p=BASE.offset(placed%100,0,placed/100);level.setBlock(p,VirtualContent.BARREL.defaultBlockState(),2);if(level.getBlockEntity(p)!=null)throw new IllegalStateException("Virtual barrel has a block entity");}if(placed==10000){
                level.setBlock(BASE.west(),Content.connector.get().defaultBlockState(),3);connector=(InventoryConnectorBlockEntity)level.getBlockEntity(BASE.west());
                level.setBlock(BASE.west(2),Content.terminal.get().defaultBlockState().setValue(AbstractStorageTerminalBlock.FACING,Direction.EAST),3);terminal=(StorageTerminalBlockEntity)level.getBlockEntity(BASE.west(2));phase="network_warmup";age=0;stage=2;
            }}
            case 2->{if(age%40==0)LOG.info("WARMUP {} ticks; {}; {}",age,world().status(),PersistentCables.manager(level).status());if(age>160 && connector.getMergedHandler().getSlotCount()==640000 && world().ledger.nodeCount()==10000){stage=3;begin("10000_empty_idle");}else if(age>1600)throw new IllegalStateException("10000 barrel network did not become ready");}
            case 3->{if(age>ticks){finish();stage=4;phase="terminal_warmup";age=0;}}
            case 4->{terminal.getStacks();if(age>80){stage=5;begin("10000_terminal_open");}}
            case 5->{terminal.getStacks();if(age>ticks){finish();var v=ItemVariant.of(Items.DIAMOND);total=10000L*v.toStack().getMaxStackSize();Storage<ItemVariant> storage=connector.getMergedHandler().getPlatformHandler();try(var tx=Transaction.openOuter()){if(storage.insert(v,total,tx)!=total)throw new IllegalStateException("Not all 10000 capacity nodes accepted their native stack");tx.commit();}stage=6;phase="populated_warmup";age=0;}}
            case 6->{terminal.getStacks();if(age>80){stage=7;begin("10000_sparse_io");}}
            case 7->{terminal.getStacks();if(age%5==0){long start=System.nanoTime();var taken=terminal.pullStack(new StoredItemStack(new ItemStack(Items.DIAMOND)),1);if(taken==null || taken.getQuantity()!=1 || terminal.pushStack(taken)!=null)throw new IllegalStateException("Native terminal round-trip failed");queryNanos.add(System.nanoTime()-start);}if(age>ticks){finish();if(stock()!=total)throw new IllegalStateException("IO changed authoritative item totals");checkpointBefore=world().checkpointCount();if(!world().requestCheckpoint())throw new IllegalStateException("Checkpoint request rejected");stage=8;begin("10000_checkpoint");}}
            case 8->{terminal.getStacks();if(age>ticks && world().checkpointCount()>checkpointBefore){finish();if(stock()!=total)throw new IllegalStateException("Checkpoint changed authority");complete=true;phase="completed";write();LOG.info("COMPLETE: 10000 real barrels, native IO and checkpoint conservation verified");server.halt(false);}else if(age>1600)throw new IllegalStateException("Checkpoint never completed");}
        }}catch(Throwable ex){complete=true;phase="failed";LOG.error("Scale verification failed",ex);write();server.halt(false);}
    }
    private long stock(){return world().ledger.nodes().stream().mapToLong(n->n.amount(ItemVariant.of(Items.DIAMOND))).sum();}
    private void write(){try{
        var report=new LinkedHashMap<String,Object>();report.put("status",phase);report.put("realBarrels",placed);report.put("capacityPositions",placed*64L);report.put("connectors",connector==null?0:1);report.put("terminal",terminal==null?0:1);report.put("phases",rows);report.put("authoritativeDiamonds",level==null?0:stock());report.put("java",System.getProperty("java.runtime.version"));report.put("vm",System.getProperty("java.vm.name"));report.put("processors",Runtime.getRuntime().availableProcessors());report.put("maxHeapBytes",Runtime.getRuntime().maxMemory());report.put("cpu",System.getenv("PROCESSOR_IDENTIFIER"));report.put("scope","Normal paced 20 TPS dedicated server; no clients; same-process empty-world baseline; 100x100x1 real custom blocks; one real connector and terminal; test-only forced chunks; no production world; terminal getStacks each tick models an open terminal");
        if(!queryNanos.isEmpty()){long[] a=queryNanos.stream().mapToLong(Long::longValue).sorted().toArray();report.put("terminalRoundTrip",Map.of("samples",a.length,"meanMs",Arrays.stream(a).average().orElseThrow()/1e6,"p95Ms",a[(int)Math.ceil(a.length*.95)-1]/1e6,"peakMs",a[a.length-1]/1e6));}
        var root=Path.of("../../docs/tvsn/reports");Files.createDirectories(root);String label=System.getProperty("tvsn.scaleProfile","scale");Files.writeString(root.resolve(label+".json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
        var csv=new StringBuilder("phase,start_ns,full_tick_ns,heap_bytes\n");samples.forEach((p,a)->a.forEach(v->csv.append(p).append(',').append(v[0]).append(',').append(v[1]).append(',').append(v[2]).append('\n')));Files.writeString(root.resolve(label+"-ticks.csv"),csv);
    }catch(Exception e){throw new RuntimeException(e);}}
}
