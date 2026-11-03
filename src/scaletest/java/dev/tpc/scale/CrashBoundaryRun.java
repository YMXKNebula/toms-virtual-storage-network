package dev.tpc.scale;

import dev.tpc.virtual.*;
import com.tom.storagemod.Content;
import com.tom.storagemod.block.entity.InventoryConnectorBlockEntity;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import java.nio.file.*;

/** Deliberately halts only this isolated test JVM; re-entry verifies quarantine, never automatic replay. */
public final class CrashBoundaryRun implements ModInitializer {
    private ServerLevel level;private VirtualNetwork world;private int age,stage;private boolean restart;private long count;
    private static final BlockPos P=new BlockPos(2,80,2);
    public void onInitialize(){if(!Boolean.getBoolean("tvsn.crashBoundary"))return;
        ServerLifecycleEvents.SERVER_STARTED.register(s->{try{
            level=s.overworld();world=VirtualNetwork.get(level);restart=Files.exists(s.getWorldPath(LevelResource.ROOT).resolve("tvsn-crash.txt"));
            level.setChunkForced(0,0,true);level.getChunk(0,0);
            if(!restart){level.setBlock(P.below(),Blocks.STONE.defaultBlockState(),3);level.setBlock(P,VirtualContent.BARREL.defaultBlockState(),3);level.setBlock(P.west(),Content.connector.get().defaultBlockState(),3);}
        }catch(Exception ex){throw new RuntimeException(ex);}});
        ServerTickEvents.END_SERVER_TICK.register(s->{if(level==null)return;age++;
            try{
                if(!restart){
                    var c=(InventoryConnectorBlockEntity)level.getBlockEntity(P.west());
                    if(stage==0 && age>80 && c.getMergedHandler().getSlotCount()==64){if(!c.getMergedHandler().pushStack(new ItemStack(Items.EMERALD,17)).isEmpty())throw new AssertionError("Crash fixture rejected stock");level.setBlock(P,Blocks.AIR.defaultBlockState(),3);stage=1;}
                    if(stage==1 && world.overflowJobs()==0 && world.unresolvedDropReceipts()==1){
                        // Keep ledger receipts across the checkpoint, then intentionally stop before a world/entity save.
                        world.requestCheckpoint();stage=2;
                    }
                    if(stage==2 && world.checkpointCount()>0){
                        var exported=world.exportRecovery();Files.writeString(s.getWorldPath(LevelResource.ROOT).resolve("tvsn-crash.txt"),exported.toString());
                        com.mojang.logging.LogUtils.getLogger().info("TVSN_CRASH_INTENTIONAL: checkpoint retained a 17-emerald drop receipt; halting isolated JVM before entity/world save");Runtime.getRuntime().halt(97);
                    }
                }else if(age>100){
                    if(world.healthy())throw new AssertionError("An abruptly stopped ledger became live");
                    if(world.unresolvedDropReceipts()!=1)throw new AssertionError("Checkpoint lost the unresolved entity receipt");
                    var c=(InventoryConnectorBlockEntity)level.getBlockEntity(P.west());if(c!=null && c.getMergedHandler().getSlotCount()!=0)throw new AssertionError("Quarantine exposed inventory");
                    long actual=level.getEntitiesOfClass(ItemEntity.class,new AABB(P).inflate(8)).stream().filter(e->e.getItem().is(Items.EMERALD)).mapToLong(e->e.getItem().getCount()).sum();if(age==101)count=actual;
                    if(age>160){if(actual!=count)throw new AssertionError("Unclean replay spawned duplicate drops");var export=world.exportRecovery();Files.writeString(Path.of("../../docs/tvsn/reports/crash-boundary.txt"),"PASS: second JVM quarantined the unclean ledger; one durable 17-emerald drop receipt survived checkpoint/replay; no automatic item respawn; exported="+export+"; existing saved emerald entities="+actual+"\n");s.halt(false);level=null;}
                }
                if(age>1200)throw new AssertionError("Crash boundary scenario timed out");
            }catch(Exception ex){throw new RuntimeException(ex);}
        });
    }
}
