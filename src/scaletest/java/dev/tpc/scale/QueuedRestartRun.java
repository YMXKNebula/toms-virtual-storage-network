package dev.tpc.scale;

import com.tom.storagemod.Content;
import com.tom.storagemod.block.entity.InventoryConnectorBlockEntity;
import dev.tpc.PersistentCables;
import dev.tpc.virtual.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.transfer.v1.item.*;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import java.nio.file.*;

/** A normal two-JVM restart while a real barrel-removal transfer is still queued. */
public final class QueuedRestartRun implements ModInitializer {
    private static final BlockPos BASE=new BlockPos(3_000_002,80,3_000_002);
    private ServerLevel level;private VirtualNetwork world;private boolean restart,done;private int age,stage;private long expected;
    private Path marker,report;
    public void onInitialize(){if(!Boolean.getBoolean("tvsn.queuedRestart"))return;
        ServerLifecycleEvents.SERVER_STARTED.register(s->{try{
            level=s.overworld();world=VirtualNetwork.get(level);marker=s.getWorldPath(LevelResource.ROOT).resolve("queued-transfer-restart.txt");restart=Files.exists(marker);
            Path root=Path.of("../../docs/tvsn/reports");Files.createDirectories(root);report=root.resolve(restart?"revision-20261030-queued-restart.txt":"revision-20261030-queued-seed.txt");
            int first=BASE.getX()>>4,last=BASE.east(95).getX()>>4,z=BASE.getZ()>>4;
            for(int x=first;x<=last;x++){level.setChunkForced(x,z,true);level.getChunk(x,z);}
            if(restart)expected=Long.parseLong(Files.readString(marker));
            else {expected=192L*Items.DIAMOND.getDefaultInstance().getMaxStackSize();for(int i=0;i<96;i++)level.setBlock(BASE.east(i),VirtualContent.BARREL.defaultBlockState(),3);level.setBlock(BASE.west(),Content.connector.get().defaultBlockState(),3);}
        }catch(Exception ex){throw new RuntimeException(ex);}});
        ServerTickEvents.END_SERVER_TICK.register(s->{if(level==null || done)return;age++;
            try{
                if(age>1600)throw new AssertionError("Queued transfer restart fixture timed out at stage "+stage);
                var variant=ItemVariant.of(Items.DIAMOND);
                if(restart){
                    if(age<60 || world.overflowJobs()!=0)return;
                    long count=0;for(int i=0;i<96;i++){var node=world.ledger.get(BASE.east(i).asLong());if(node!=null)count+=node.amount(variant);}
                    long dropped=level.getEntitiesOfClass(ItemEntity.class,new AABB(BASE).expandTowards(96,3,1).inflate(2)).stream().filter(e->e.getItem().is(Items.DIAMOND)).mapToLong(e->e.getItem().getCount()).sum();
                    if(count!=expected || dropped!=0)throw new AssertionError("Queued restart lost or dropped stock: expected="+expected+", kept="+count+", dropped="+dropped);
                    Files.writeString(report,"completed=true\nsecond_jvm=true\nkept="+count+"\nexpected="+expected+"\ndropped="+dropped+"\nqueued_jobs="+world.overflowJobs()+"\n");done=true;s.halt(false);return;
                }
                var c=(InventoryConnectorBlockEntity)level.getBlockEntity(BASE.west());
                if(stage==0 && age>60 && c.getMergedHandler().getSlotCount()==96*64){
                    Storage<ItemVariant> storage=c.getMergedHandler().getPlatformHandler();try(var tx=Transaction.openOuter()){if(storage.insert(variant,expected,tx)!=expected)throw new AssertionError("Could not prepare native multi-stack ownership");tx.commit();}
                    level.setBlock(BASE.east(),Blocks.AIR.defaultBlockState(),3);level.setBlock(BASE.east(2),Blocks.AIR.defaultBlockState(),3);
                    if(world.overflowJobs()==0)throw new AssertionError("The second removal must be durably queued");
                    // Hold verification unknown across the first save. The next JVM
                    // loads and verifies the real chunks, then resumes this same job.
                    PersistentCables.manager(level).topology.beginChunkValidation(BASE.getX()>>4,BASE.getZ()>>4);
                    if(!world.requestCheckpoint())throw new AssertionError("Could not capture queued job in checkpoint");stage=1;
                }
                if(stage==1 && world.checkpointCount()>0){
                    if(world.overflowJobs()!=1)throw new AssertionError("The pending transfer must survive until shutdown");
                    Files.writeString(marker,Long.toString(expected));Files.writeString(report,"completed=true\nqueued_jobs=1\ncheckpoint=true\nexpected="+expected+"\n");done=true;s.halt(false);
                }
            }catch(Throwable ex){try{Files.writeString(report,"completed=false\nerror="+ex+"\n");}catch(Exception ignored){}throw new RuntimeException(ex);}
        });
    }
}
