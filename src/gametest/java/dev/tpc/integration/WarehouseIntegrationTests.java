package dev.tpc.integration;

import dev.tpc.PersistentCables;
import dev.tpc.virtual.*;
import com.tom.storagemod.Content;
import com.tom.storagemod.block.*;
import com.tom.storagemod.block.entity.*;
import com.tom.storagemod.inventory.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.transfer.v1.item.*;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import java.nio.file.*;
import java.util.function.BooleanSupplier;

public final class WarehouseIntegrationTests {
    private static void await(GameTestHelper t,BooleanSupplier ready,Runnable next){if(ready.getAsBoolean())next.run();else t.runAfterDelay(1,()->await(t,ready,next));}
    private static net.minecraft.world.level.block.state.BlockState wire(){return Content.invCable.get().defaultBlockState().setValue(InventoryCableBlock.EAST,true).setValue(InventoryCableBlock.WEST,true);}
    private static void barrel(GameTestHelper t,BlockPos p){VirtualFixtureReset.clear(VirtualNetwork.get(t.getLevel()),p);t.getLevel().setBlock(p,VirtualContent.BARREL.defaultBlockState(),3);}
    private static long displayed(IInventoryAccess access,net.minecraft.server.level.ServerLevel level){access.tracker().getChangeTracker(level);return access.tracker().streamWrappedStacks(false).filter(s->s.getStack().is(Items.DIAMOND)).mapToLong(StoredItemStack::getQuantity).sum();}
    @GameTest(maxTicks=4500)
    public void bulkRemovalKeepsPendingOwnershipAndBudgetsOverflow(GameTestHelper t){
        var l=t.getLevel();int iteration;
        try{Path counter=l.getServer().getWorldPath(LevelResource.ROOT).resolve("tvsn-bulk-test-id.txt");iteration=Files.exists(counter)?Integer.parseInt(Files.readString(counter))+1:0;Files.writeString(counter,Integer.toString(iteration));}catch(Exception ex){throw new RuntimeException(ex);}
        var base=new BlockPos(6_000_002+iteration*64,70,6_000_002);int cx=base.getX()>>4,cz=base.getZ()>>4;
        for(int x=cx-1;x<=cx+3;x++)for(int z=cz-1;z<=cz+1;z++)l.setChunkForced(x,z,true);
        for(int x=-1;x<=50;x++)for(int z=-1;z<=10;z++)l.setBlock(base.offset(x,-1,z),Blocks.STONE.defaultBlockState(),2);
        for(int i=0;i<500;i++)barrel(t,base.offset(i%50,0,i/50));l.setBlock(base.west(),Content.connector.get().defaultBlockState(),3);
        var c=(InventoryConnectorBlockEntity)l.getBlockEntity(base.west());var n=VirtualNetwork.get(l);var v=ItemVariant.of(Items.DIAMOND);long amount=500L*v.toStack().getMaxStackSize();
        await(t,()->c.getMergedHandler().getSlotCount()==32000,()->{
            Storage<ItemVariant> storage=c.getMergedHandler().getPlatformHandler();try(var tx=Transaction.openOuter()){t.assertTrue(storage.insert(v,amount,tx)==amount,"Every bulk-removal barrel must own a full native stack");tx.commit();}
            for(int i=0;i<500;i++)l.setBlock(base.offset(i%50,0,i/50),Blocks.AIR.defaultBlockState(),3);
            try(var tx=Transaction.openOuter()){t.assertTrue(storage.extract(v,1,tx)==0,"Bulk edits must revoke access before deferred settlement");tx.commit();}
            await(t,()->n.overflowJobs()==0 && java.util.stream.IntStream.range(0,500).allMatch(i->n.ledger.get(base.offset(i%50,0,i/50).asLong())==null),()->{
                long drops=l.getEntitiesOfClass(ItemEntity.class,new AABB(base).expandTowards(51,4,11).inflate(3)).stream().filter(e->e.getItem().is(Items.DIAMOND)).mapToLong(e->e.getItem().getCount()).sum();t.assertTrue(drops==amount,"Deferred removal must conserve all "+amount+" items, actual drops="+drops);t.succeed();
            });
        });
    }

    @GameTest(maxTicks=1600)
    public void networkSplitKeepsSpecificBarrelOwnershipAndReconnectDoesNotDuplicate(GameTestHelper t){
        var l=t.getLevel();BlockPos a=t.absolutePos(new BlockPos(1,6,2)),b=a.east(6),gap=a.east(3);
        barrel(t,a);barrel(t,b);
        l.setBlock(a.east(),Content.connector.get().defaultBlockState(),3);l.setBlock(b.west(),Content.connector.get().defaultBlockState(),3);
        l.setBlock(a.east(2),wire(),3);l.setBlock(b.west(2),wire(),3);
        var ca=(InventoryConnectorBlockEntity)l.getBlockEntity(a.east());var cb=(InventoryConnectorBlockEntity)l.getBlockEntity(b.west());var n=VirtualNetwork.get(l);var variant=ItemVariant.of(Items.DIAMOND);
        await(t,()->ca.getMergedHandler().getSlotCount()==64 && cb.getMergedHandler().getSlotCount()==64,()->{
            t.assertTrue(ca.getMergedHandler().pushStack(new ItemStack(Items.DIAMOND,11)).isEmpty() && cb.getMergedHandler().pushStack(new ItemStack(Items.DIAMOND,22)).isEmpty(),"Separate warehouses must accept separate stock");
            l.setBlock(gap,wire(),3);
            await(t,()->ca.getMergedHandler().getSlotCount()==128 && cb.getMergedHandler().getSlotCount()==128,()->{
                t.assertTrue(displayed(ca.getMergedHandler(),l)==33 && displayed(cb.getMergedHandler(),l)==33,"Two gateways must each show the same single authority, not a doubled inventory");
                t.assertTrue(ca.getMergedHandler().pullMatchingStack(new ItemStack(Items.DIAMOND),5).getCount()==5,"A joined gateway must extract real ledger stock");
                long ownedA=n.ledger.get(a.asLong()).amount(variant),ownedB=n.ledger.get(b.asLong()).amount(variant);
                Storage<ItemVariant> borrowed=ca.getMergedHandler().getPlatformHandler();l.setBlock(gap,Blocks.AIR.defaultBlockState(),3);
                try(Transaction tx=Transaction.openOuter()){t.assertTrue(borrowed.extract(variant,1,tx)==0,"A stale joined gateway must be denied in the split tick");tx.commit();}
                await(t,()->ca.getMergedHandler().getSlotCount()==64 && cb.getMergedHandler().getSlotCount()==64,()->{
                    t.assertTrue(displayed(ca.getMergedHandler(),l)==ownedA && displayed(cb.getMergedHandler(),l)==ownedB && ownedA+ownedB==28,"Splitting must preserve per-barrel ownership without rebalancing totals");
                    l.setBlock(gap,wire(),3);await(t,()->ca.getMergedHandler().getSlotCount()==128 && cb.getMergedHandler().getSlotCount()==128,()->{
                        t.assertTrue(displayed(ca.getMergedHandler(),l)==28 && displayed(cb.getMergedHandler(),l)==28,"Rejoining must aggregate exactly the surviving stock");t.succeed();
                    });
                });
            });
        });
    }
    @GameTest(maxTicks=4500)
    public void completelyUnloadedWarehouseRemainsUsableWithoutLoadingItsChunks(GameTestHelper t){
        var l=t.getLevel();BlockPos start=new BlockPos(4_000_002,70,4_000_002),target=start.east(512);int first=start.getX()>>4,last=target.getX()>>4,z=start.getZ()>>4,middle=(first+last)/2;
        for(int x=first;x<=last;x++)l.setChunkForced(x,z,true);
        l.setBlock(start,Content.connector.get().defaultBlockState(),3);for(int i=1;i<511;i++)l.setBlock(start.east(i),wire(),3);l.setBlock(target.west(),Content.connector.get().defaultBlockState(),3);barrel(t,target);
        var c=(InventoryConnectorBlockEntity)l.getBlockEntity(start);var n=VirtualNetwork.get(l);var variant=ItemVariant.of(Items.DIAMOND);
        await(t,()->c.getMergedHandler().getSlotCount()==64,()->{
            t.assertTrue(c.getMergedHandler().pushStack(new ItemStack(Items.DIAMOND,7)).isEmpty(),"Offline fixture must start with committed inventory");
            for(int x=first+1;x<=last;x++)l.setChunkForced(x,z,false);
            await(t,()->l.getChunkSource().getChunkNow(last,z)==null && l.getChunkSource().getChunkNow(middle,z)==null,()->{
                await(t,()->c.getMergedHandler().getSlotCount()==64,()->{
                    t.assertTrue(c.getMergedHandler().pullMatchingStack(new ItemStack(Items.DIAMOND),3).getCount()==3,"The terminal-side gateway must extract while the whole warehouse is unloaded");
                    t.assertTrue(c.getMergedHandler().pushStack(new ItemStack(Items.DIAMOND,2)).isEmpty() && n.ledger.get(target.asLong()).amount(variant)==6,"Offline insertion and extraction must update the same authoritative allocation");
                    t.assertTrue(l.getChunkSource().getChunkNow(last,z)==null && l.getChunkSource().getChunkNow(middle,z)==null,"Virtual access must not load warehouse or middle chunks");
                    l.setChunkForced(middle,z,true);
                    await(t,()->PersistentCables.manager(l).query(start).valid() && c.getMergedHandler().getSlotCount()==64,()->{
                        BlockPos cut=new BlockPos(middle*16+8,start.getY(),start.getZ());Storage<ItemVariant> borrowed=c.getMergedHandler().getPlatformHandler();l.setBlock(cut,Blocks.AIR.defaultBlockState(),3);
                        try(Transaction tx=Transaction.openOuter()){t.assertTrue(borrowed.extract(variant,1,tx)==0,"A cut in the physical middle must deny an old offline warehouse handle");tx.commit();}
                        t.assertTrue(n.ledger.get(target.asLong()).amount(variant)==6,"A physical cable cut must never change item existence");
                        await(t,()->c.getMergedHandler().getSlotCount()==0,()->{
                            l.setBlock(cut,wire(),3);await(t,()->c.getMergedHandler().getSlotCount()==64,()->{
                                t.assertTrue(c.getMergedHandler().pullMatchingStack(new ItemStack(Items.DIAMOND),1).getCount()==1 && n.ledger.get(target.asLong()).amount(variant)==5,"Repair must restore only the correct stock");
                                t.assertTrue(l.getChunkSource().getChunkNow(last,z)==null,"Repair must also leave the warehouse unloaded");l.setChunkForced(first,z,false);l.setChunkForced(middle,z,false);t.succeed();
                            });
                        });
                    });
                });
            });
        });
    }
    @GameTest(maxTicks=1400)
    public void overflowDropsAreBudgetedAndDuplicateRemovalDoesNotRepeatThem(GameTestHelper t){
        var l=t.getLevel();BlockPos a=t.absolutePos(new BlockPos(2,7,2)),middle=a.east(),c=a.east(2);var n=VirtualNetwork.get(l);
        for(int dx=-1;dx<=3;dx++)for(int dz=-1;dz<=1;dz++)l.setBlock(a.offset(dx,-1,dz),Blocks.STONE.defaultBlockState(),3);
        barrel(t,a);barrel(t,middle);barrel(t,c);l.setBlock(a.west(),Content.connector.get().defaultBlockState(),3);
        var connector=(InventoryConnectorBlockEntity)l.getBlockEntity(a.west());String prefix="TVSN overflow "+middle.asLong()+" / ";
        await(t,()->connector.getMergedHandler().getSlotCount()==192,()->{
            Storage<ItemVariant> storage=connector.getMergedHandler().getPlatformHandler();
            try(Transaction tx=Transaction.openOuter()){
                for(int i=0;i<192;i++){var paper=new ItemStack(Items.PAPER);paper.set(DataComponents.CUSTOM_NAME,Component.literal(prefix+i));t.assertTrue(storage.insert(ItemVariant.of(paper),1,tx)==1,"Fill every logical kind position through the native gateway");}tx.commit();
            }
            t.assertTrue(n.ledger.get(middle.asLong()).contents().size()==64,"The removed unit must actually own 64 distinct full kind positions");
            l.setBlock(middle,Blocks.AIR.defaultBlockState(),3);n.changed(middle,VirtualContent.BARREL.defaultBlockState(),Blocks.AIR.defaultBlockState());
            int[] previous={0};watchDrops(t,middle,prefix,n,previous,()->{
                t.assertTrue(n.ledger.get(a.asLong()).contents().size()==64 && n.ledger.get(c.asLong()).contents().size()==64,"Removing the middle unit must preserve both surviving allocations");
                t.assertTrue(dropCount(t,middle,prefix)==64,"Every removed item must be delivered once, never duplicated by a repeated event");t.succeed();
            });
        });
    }
    private static int dropCount(GameTestHelper t,BlockPos p,String prefix){return (int)t.getLevel().getEntitiesOfClass(ItemEntity.class,new AABB(p).inflate(4)).stream().filter(e->{var name=e.getItem().get(DataComponents.CUSTOM_NAME);return name!=null && name.getString().startsWith(prefix);}).count();}
    private static void watchDrops(GameTestHelper t,BlockPos p,String prefix,VirtualNetwork n,int[] previous,Runnable done){
        int count=dropCount(t,p,prefix);t.assertTrue(count-previous[0]<=PersistentCables.CONFIG.maxOverflowEntitiesPerTick,"Per-tick entity budget must be respected");previous[0]=count;
        if(count==64 && n.overflowJobs()==0)done.run();else t.runAfterDelay(1,()->watchDrops(t,p,prefix,n,previous,done));
    }
    @GameTest(maxTicks=1500)
    public void realServerRestartRestoresOneAuthorityAndExactBarrelOwnership(GameTestHelper t){
        var l=t.getLevel();BlockPos p=new BlockPos(5_000_042,70,-5_000_002);int x=p.getX()>>4,z=p.getZ()>>4;
        Path marker=l.getServer().getWorldPath(LevelResource.ROOT).resolve("tvsn-ledger-restart-v2.txt");boolean first=!Files.exists(marker);l.setChunkForced(x,z,true);
        if(first){l.setBlock(p,VirtualContent.BARREL.defaultBlockState(),3);l.setBlock(p.west(),Content.connector.get().defaultBlockState(),3);}
        var n=VirtualNetwork.get(l);
        await(t,()->l.getBlockEntity(p.west()) instanceof InventoryConnectorBlockEntity connector && connector.getMergedHandler().getSlotCount()==64,()->{
            var connector=(InventoryConnectorBlockEntity)l.getBlockEntity(p.west());var variant=ItemVariant.of(Items.EMERALD);
            if(first){
                t.assertTrue(connector.getMergedHandler().pushStack(new ItemStack(Items.EMERALD,33)).isEmpty(),"Prepare real persisted virtual contents");
                t.assertTrue(n.requestCheckpoint(),"Start a real budgeted checkpoint");
                t.assertTrue(connector.getMergedHandler().pushStack(new ItemStack(Items.EMERALD,11)).isEmpty(),"A concurrent committed delta must survive checkpoint replacement");
                try{Files.writeString(marker,"44 emeralds: checkpoint captured 33; a live delta added 11 before replacement");}catch(Exception ex){throw new RuntimeException(ex);}
            }
            else{
                t.assertTrue(n.ledger.get(p.asLong()).amount(variant)==44,"A second JVM must recover the exact saved ownership, including the checkpoint tail");
                t.assertTrue(connector.getMergedHandler().pullMatchingStack(new ItemStack(Items.EMERALD),3).getCount()==3,"Recovered stock must be actionable");t.assertTrue(connector.getMergedHandler().pushStack(new ItemStack(Items.EMERALD,3)).isEmpty(),"Restore fixture after verifying native round trip");
            }
            t.assertTrue(n.ledger.get(p.asLong()).amount(variant)==44,"Restart checks must conserve exact quantities");
            await(t,()->!first || n.checkpointCount()>0,()->{l.setChunkForced(x,z,false);t.succeed();});
        });
    }
    @GameTest(maxTicks=1200)
    public void confirmedRecipeIsRecognizedByTheOriginalCraftingTerminal(GameTestHelper t){
        var l=t.getLevel();BlockPos p=t.absolutePos(new BlockPos(1,5,2));
        l.setBlock(p,Content.craftingTerminal.get().defaultBlockState().setValue(AbstractStorageTerminalBlock.FACING,Direction.EAST),3);l.setBlock(p.east(),Content.connector.get().defaultBlockState(),3);barrel(t,p.east(2));
        var terminal=(CraftingTerminalBlockEntity)l.getBlockEntity(p);
        for(int i=0;i<9;i++)terminal.setCraftSlot(i,new ItemStack(i==4?Items.BARREL:(i%2==0?Items.IRON_INGOT:Items.ENDER_PEARL)));
        await(t,()->terminal.getCraftResult().getItem(0).is(VirtualContent.BARREL_ITEM),()->{t.assertTrue(terminal.getCraftResult().getItem(0).getCount()==1,"The confirmed recipe must yield exactly one Tom's Barrel");t.succeed();});
    }
}
