package dev.tpc.scale;

import dev.tpc.PersistentCables;
import dev.tpc.virtual.*;
import com.tom.storagemod.Content;
import com.tom.storagemod.block.AbstractStorageTerminalBlock;
import com.tom.storagemod.block.entity.*;
import com.tom.storagemod.inventory.StoredItemStack;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import java.nio.file.*;
import java.util.*;

/** Test-only multi-dimension, actual Carpet ServerPlayer workload. No cross-dimension inventory link. */
public final class LargeRun implements ModInitializer {
    public static String phase="disabled";
    public static volatile Throwable creationFailure;
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("tvsn-large");
    private static final Map<String,List<long[]>> samples=new LinkedHashMap<>();
    private static final List<Map<String,Object>> results=new ArrayList<>();
    private static final Map<String,List<Long>> perWorldTicks=new LinkedHashMap<>();
    private final int perDimension=Integer.getInteger("tvsn.largePerDimension",100000),players=Integer.getInteger("tvsn.largePlayers",12),ticks=Integer.getInteger("tvsn.scaleTicks",600);
    private final boolean resume=Boolean.getBoolean("tvsn.largeResume");
    private final Map<VirtualNetwork,Long> checkpointBefore=new IdentityHashMap<>();
    private final List<Group> groups=new ArrayList<>();private final Map<ServerLevel,VirtualNetwork> worlds=new LinkedHashMap<>();
    private final List<Long> roundTrips=new ArrayList<>();private int stage,age,spawnCursor,placeCursor,seedCursor,stableTicks,lastLoaded=-1;private boolean complete;
    private static final class Group {
        final ServerLevel level;final BlockPos base;final int target;final String name;int placed;long expected;InventoryConnectorBlockEntity connector;StorageTerminalBlockEntity terminal;
        Group(ServerLevel l,int i,int count,String name){level=l;int outer=l.dimension().equals(Level.END)?8192:0;base=new BlockPos(outer+(i%2)*512,80,outer+(i/2)*512);target=count;this.name=name;}
    }
    public void onInitialize(){if(!Boolean.getBoolean("tvsn.large"))return;
        if(players<3 || players%3!=0 || perDimension<players/3)throw new IllegalArgumentException("Use a positive per-dimension capacity and players divisible by three");
        ServerLifecycleEvents.SERVER_STARTED.register(s->{try{
            Class.forName("carpet.CarpetSettings").getField("allowSpawningOfflinePlayers").setBoolean(null,true);
            var dimensions=List.of(Level.OVERWORLD,Level.NETHER,Level.END);int n=players/3,index=0;
            for(var key:dimensions){var l=Objects.requireNonNull(s.getLevel(key));worlds.put(l,VirtualNetwork.get(l));for(int i=0;i<n;i++)groups.add(new Group(l,i,perDimension/n+(i<perDimension%n?1:0),String.format(Locale.ROOT,"TVSN_B%02d",index++)));}
            if(resume){
                long expected=Long.getLong("tvsn.restartExpectedPerDimension",-1L);if(expected<=0 || expected%perDimension!=0)throw new IllegalArgumentException("Restart requires the previously recorded per-dimension item total, independent of stack-limit initialization");
                for(var w:worlds.values())if(!w.healthy() || w.ledger.nodeCount()!=perDimension)throw new IllegalStateException("Restart verification requires clean complete saved warehouses");for(var g:groups){g.placed=g.target;g.expected=(long)g.target*(expected/perDimension);}
            }
            begin("player_spawn_and_chunk_generation");LOG.info("Requested {} barrels per dimension ({} total), {} real Carpet server players, server view distance {}",perDimension,3L*perDimension,players,s.getPlayerList().getViewDistance());
        }catch(Exception ex){fail(s,ex);}});
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(s->{if(!complete){phase="interrupted";write(s);}});
    }
    public static void sample(String p,long start,long end){if(!p.equals("disabled") && p.equals(phase) && !Set.of("completed","failed","transition").contains(p))samples.computeIfAbsent(p,k->new ArrayList<>()).add(new long[]{start,end-start,Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()});}
    private void begin(String p){phase=p;age=0;LOG.info("PHASE {}: {} ticks per steady phase",p,ticks);}
    private int loaded(){return worlds.keySet().stream().mapToInt(l->l.getChunkSource().getLoadedChunksCount()).sum();}
    private void finish(MinecraftServer s){
        var a=samples.getOrDefault(phase,List.of());var row=new LinkedHashMap<String,Object>();row.put("phase",phase);row.put("samples",a.size());
        if(a.size()>1){long[] sorted=a.stream().mapToLong(v->v[1]).sorted().toArray();row.put("observedTPS",(a.size()-1)/((a.getLast()[0]-a.getFirst()[0])/1e9));row.put("meanMSPT",Arrays.stream(sorted).average().orElseThrow()/1e6);row.put("p95MSPT",sorted[(int)Math.ceil(sorted.length*.95)-1]/1e6);row.put("p99MSPT",sorted[(int)Math.ceil(sorted.length*.99)-1]/1e6);row.put("peakMSPT",sorted[sorted.length-1]/1e6);row.put("ticksOver50ms",Arrays.stream(sorted).filter(t->t>50_000_000).count());row.put("peakObservedHeapBytes",a.stream().mapToLong(v->v[2]).max().orElseThrow());}
        row.put("actualPlayers",s.getPlayerList().getPlayers().size());var dimensions=new LinkedHashMap<String,Object>();worlds.forEach((l,w)->dimensions.put(l.dimension().identifier().toString(),Map.of("chunkHolders",l.getChunkSource().getLoadedChunksCount(),"fullLoadedChunks",PersistentCables.manager(l).loadedChunkCount(),"barrels",w.ledger.nodeCount(),"nativeDiscoveryScans",w.discovery.scans,"discoverySleeps",w.discovery.sleeps,"mergedBuilds",w.mergedBuilds,"mergedBuildHits",w.mergedBuildHits,"status",w.status())));row.put("dimensions",dimensions);
        results.add(row);LOG.info("RESULT {}",row);phase="transition";write(s);
    }
    private void spawn(MinecraftServer s,Group g)throws Exception{
        var method=Class.forName("carpet.patches.EntityPlayerMPFake").getMethod("createFake",String.class,MinecraftServer.class,Vec3.class,double.class,double.class,ResourceKey.class,GameType.class,boolean.class);
        boolean accepted=(boolean)method.invoke(null,g.name,s,new Vec3(g.base.getX()+.5,g.base.getY()+(g.target+9999)/10000+3,g.base.getZ()+.5),0D,0D,g.level.dimension(),GameType.CREATIVE,true);
        if(!accepted)throw new IllegalStateException("Carpet rejected test player "+g.name);
    }
    private boolean fixturesLoaded(){for(var g:groups)for(int x=0;x<=6;x++)for(int z=0;z<=6;z++)if(g.level.getChunkSource().getChunkNow((g.base.getX()>>4)+x,(g.base.getZ()>>4)+z)==null)return false;return true;}
    private boolean ready(){for(var g:groups)if(g.connector==null || g.connector.getMergedHandler().getSlotCount()!=64L*g.target)return false;return true;}
    private void connect(Group g){var p=g.base.above((g.target+9999)/10000);g.level.setBlock(p,Content.connector.get().defaultBlockState(),3);g.connector=(InventoryConnectorBlockEntity)g.level.getBlockEntity(p);g.level.setBlock(p.west(),Content.terminal.get().defaultBlockState().setValue(AbstractStorageTerminalBlock.FACING,Direction.EAST),3);g.terminal=(StorageTerminalBlockEntity)g.level.getBlockEntity(p.west());}
    private void verify(){var variant=ItemVariant.of(Items.DIAMOND);for(var entry:worlds.entrySet()){
        long expected=groups.stream().filter(g->g.level==entry.getKey()).mapToLong(g->g.expected).sum();long actual=entry.getValue().ledger.nodes().stream().mapToLong(n->n.amount(variant)).sum();
        if(entry.getValue().ledger.nodeCount()!=perDimension || actual!=expected || !entry.getValue().healthy())throw new AssertionError("Inventory/dimension invariant failed: "+entry.getKey().dimension()+" expected="+expected+" actual="+actual+" "+entry.getValue().status());
    }}
    private void tick(MinecraftServer s){if(worlds.isEmpty() || complete)return;age++;
        try{switch(stage){
            case 0->{
                if(creationFailure!=null)throw new IllegalStateException("The asynchronous fake-player login failed",creationFailure);
                if(Files.exists(Path.of("stop-test.txt")))throw new IllegalStateException("Stopped by the isolated test controller");
                if(spawnCursor<groups.size() && age%5==0)spawn(s,groups.get(spawnCursor++));int count=loaded();stableTicks=count==lastLoaded?stableTicks+1:0;lastLoaded=count;
                if(age%100==0)LOG.info("Generation: {}/{} test players, {} loaded chunks, stable for {} ticks",s.getPlayerList().getPlayers().size(),players,count,stableTicks);
                if(age>300 && s.getPlayerList().getPlayers().size()==players && stableTicks>=80 && fixturesLoaded()){
                    if(resume){for(var g:groups)if(g.connector==null)connect(g);if(!ready())return;verify();finish(s);stage=4;begin("resumed_300000_idle_with_players");}
                    else{finish(s);stage=1;begin("players_loaded_no_warehouses");}
                }
                else if(age>6000)throw new IllegalStateException("Player/chunk generation did not settle");
            }
            case 1->{if(age>ticks){finish(s);stage=2;begin("concurrent_construction");}}
            case 2->{
                int budget=768,empty=0;
                while(budget>0 && empty<groups.size()){var g=groups.get(placeCursor++%groups.size());if(g.placed==g.target){empty++;continue;}empty=0;int batch=Math.min(64,Math.min(budget,g.target-g.placed));for(int i=0;i<batch;i++){int n=g.placed;var p=g.base.offset(n%100,n/10000,(n/100)%100);if(g.level.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4)==null)throw new IllegalStateException("Player stopped loading a construction chunk");g.level.setBlock(p,VirtualContent.BARREL.defaultBlockState(),2);if(g.level.getBlockEntity(p)!=null)throw new AssertionError("A virtual barrel has a BE");g.placed++;budget--;}}
                if(age%40==0)LOG.info("Constructed {}/{} real barrels; {} loaded chunks",groups.stream().mapToInt(g->g.placed).sum(),3L*perDimension,loaded());
                if(groups.stream().allMatch(g->g.placed==g.target)){for(var g:groups)connect(g);finish(s);stage=3;begin("topology_and_activation_settling");}
            }
            case 3->{if(age%100==0)worlds.forEach((l,w)->LOG.info("WARMUP {} {}",l.dimension().identifier(),PersistentCables.manager(l).status()));if(age>240 && ready()){verify();finish(s);stage=4;begin("300000_idle_with_players");}else if(age>2400)throw new IllegalStateException("The completed warehouses did not become ready");}
            case 4->{if(age>ticks){finish(s);for(var g:groups){var p=s.getPlayerList().getPlayerByName(g.name);if(p==null)throw new IllegalStateException("Test player vanished");p.openMenu(g.terminal);}stage=5;begin("300000_twelve_open_terminals");}}
            case 5->{for(var g:groups)if(!(s.getPlayerList().getPlayerByName(g.name).containerMenu instanceof com.tom.storagemod.menu.StorageTerminalMenu))throw new AssertionError("A simulated player's real terminal menu closed");if(age>ticks){finish(s);if(resume){stage=7;begin("resumed_300000_concurrent_io");}else{stage=6;begin("populate_native_stacks");}}}
            case 6->{if(seedCursor<groups.size()){var g=groups.get(seedCursor++);var variant=ItemVariant.of(Items.DIAMOND);g.expected=(long)g.target*variant.toStack().getMaxStackSize();Storage<ItemVariant> storage=g.connector.getMergedHandler().getPlatformHandler();try(var tx=Transaction.openOuter()){if(storage.insert(variant,g.expected,tx)!=g.expected)throw new AssertionError("Full native-stack population failed for "+g.name);tx.commit();}}
                else if(age>100){verify();finish(s);stage=7;begin("300000_twelve_players_concurrent_io");}}
            case 7->{if(age%5==0)for(var g:groups){long start=System.nanoTime();var taken=g.terminal.pullStack(new StoredItemStack(new ItemStack(Items.DIAMOND)),1);if(taken==null || taken.getQuantity()!=1 || g.terminal.pushStack(taken)!=null)throw new AssertionError("A player's terminal round-trip failed in "+g.level.dimension());roundTrips.add(System.nanoTime()-start);}if(age>ticks){finish(s);verify();stage=8;begin("300000_simultaneous_checkpoints");}}
            case 8->{for(var w:worlds.values())if(!checkpointBefore.containsKey(w)){long before=w.checkpointCount();if(w.requestCheckpoint())checkpointBefore.put(w,before);}if(age>ticks && checkpointBefore.size()==worlds.size() && worlds.values().stream().allMatch(w->w.checkpointCount()>checkpointBefore.get(w))){finish(s);verify();stage=9;begin("300000_explicit_world_save");}else if(age>2400)throw new IllegalStateException("Concurrent checkpoints failed to finish");}
            case 9->{if(age==1)s.saveEverything(false,true,false);if(age>100){finish(s);verify();complete=true;phase="completed";write(s);LOG.info("COMPLETE: {} real barrels in each of three dimensions; {} Carpet players; all authoritative item totals conserved",perDimension,players);s.halt(false);}}
        }}catch(Throwable ex){fail(s,ex);}
    }
    private void fail(MinecraftServer s,Throwable ex){complete=true;phase="failed";LOG.error("Large workload failed",ex);write(s);s.halt(false);}
    private void write(MinecraftServer s){try{
        var report=new LinkedHashMap<String,Object>();report.put("status",phase);report.put("requestedPerDimension",perDimension);report.put("requestedTotal",3L*perDimension);report.put("requestedPlayers",players);report.put("actualPlayers",s.getPlayerList().getPlayers().size());report.put("realBarrelsPlaced",groups.stream().mapToLong(g->g.placed).sum());report.put("capacityPositions",groups.stream().mapToLong(g->g.placed*64L).sum());report.put("viewDistance",s.getPlayerList().getViewDistance());report.put("maxHeapBytes",Runtime.getRuntime().maxMemory());report.put("processors",Runtime.getRuntime().availableProcessors());report.put("java",System.getProperty("java.runtime.version"));report.put("cpu",System.getenv("PROCESSOR_IDENTIFIER"));report.put("phases",results);
        report.put("warehouses",groups.stream().map(g->Map.of("dimension",g.level.dimension().identifier().toString(),"base",g.base.toShortString(),"player",g.name,"barrels",g.placed,"expectedDiamonds",g.expected)).toList());
        report.put("scope","Normal 20 TPS dedicated server; full supplied pack; real Carpet ServerPlayers with dummy client connections; four players per dimension spread over separate chunk regions; no cross-dimension storage; no real client rendering or network bandwidth; no mob farms; original generation and pack logic retained; construction uses 768 setBlock events per tick globally, excluded from steady-state rows");
        report.put("runtimeExclusions",List.of("e4mc 6.2.3+modern: its login command permission predicate calls removed CommandSourceStack.hasPermission(int); failed isolated run retained as large-pack-2.log"));
        report.put("runtimeMods",net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods().stream().map(m->m.getMetadata().getId()+" "+m.getMetadata().getVersion().getFriendlyString()).sorted().toList());
        report.put("restartVerification",resume);report.put("checkpointCaptureIncluded",true);report.put("checkpointIntervalTicks",PersistentCables.CONFIG.checkpointIntervalTicks);
        if(!roundTrips.isEmpty()){long[] a=roundTrips.stream().mapToLong(Long::longValue).sorted().toArray();report.put("terminalRoundTrips",Map.of("samples",a.length,"meanMs",Arrays.stream(a).average().orElseThrow()/1e6,"p95Ms",a[(int)Math.ceil(a.length*.95)-1]/1e6,"peakMs",a[a.length-1]/1e6));}
        var root=Path.of("../../docs/tvsn/reports");Files.createDirectories(root);String label=System.getProperty("tvsn.scaleProfile","large");Files.writeString(root.resolve(label+".json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));var csv=new StringBuilder("phase,start_ns,full_tick_ns,heap_bytes\n");samples.forEach((p,a)->a.forEach(v->csv.append(p).append(',').append(v[0]).append(',').append(v[1]).append(',').append(v[2]).append('\n')));Files.writeString(root.resolve(label+"-ticks.csv"),csv);
    }catch(Exception ex){LOG.error("Could not write large workload evidence",ex);}}
}
