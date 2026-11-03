package dev.tpc.integration;

import dev.tpc.PersistentCables;
import com.tom.storagemod.Content;
import com.tom.storagemod.block.InventoryCableBlock;
import com.tom.storagemod.block.entity.InventoryConnectorBlockEntity;
import com.tom.storagemod.inventory.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import java.util.function.BooleanSupplier;
import java.nio.file.*;
import net.minecraft.world.level.storage.LevelResource;

public final class CableGameTests {
    @GameTest(maxTicks=1600)
    public void directConnectorTerminalDisplayDoesNotGrow(GameTestHelper test) {
        setupDirectTerminalCountTest(test,false);
    }
    @GameTest(maxTicks=1600)
    public void directInterfaceTerminalDisplayDoesNotGrow(GameTestHelper test) {
        setupDirectTerminalCountTest(test,true);
    }
    private static void setupDirectTerminalCountTest(GameTestHelper test,boolean viaInterface) {
        var level=test.getLevel();BlockPos start=test.absolutePos(new BlockPos(1,15,1));
        level.setBlock(start,Content.terminal.get().defaultBlockState().setValue(com.tom.storagemod.block.AbstractStorageTerminalBlock.FACING,Direction.EAST),3);
        int offset=viaInterface?2:1;
        if(viaInterface)level.setBlock(start.east(),Content.invInterface.get().defaultBlockState(),3);
        level.setBlock(start.east(offset),Content.connector.get().defaultBlockState(),3);level.setBlock(start.east(offset+1),Blocks.CHEST.defaultBlockState(),3);
        var chest=(ChestBlockEntity)level.getBlockEntity(start.east(offset+1));chest.setItem(0,new ItemStack(Items.DIAMOND,12));
        var terminal=(com.tom.storagemod.block.entity.StorageTerminalBlockEntity)level.getBlockEntity(start);
        await(test,()->{terminal.getStacks();terminal.updateServer();return terminal.getStacks().values().stream().anyMatch(s->s.getStack().is(Items.DIAMOND));},
                ()->observeTerminalDisplay(test,terminal,chest,12,600,()->{
                    var taken=terminal.pullStack(new StoredItemStack(new ItemStack(Items.DIAMOND)),64);
                    test.assertTrue(taken!=null && taken.getQuantity()==12 && chest.isEmpty(),"Exactly the twelve real items must be extractable");
                    test.assertTrue(terminal.pullStack(new StoredItemStack(new ItemStack(Items.DIAMOND)),1)==null,"Phantom items must not be extractable after the real chest is empty");
                    await(test,()->{terminal.getStacks();terminal.updateServer();return terminalCount(terminal)==0;},
                            ()->observeTerminalDisplay(test,terminal,chest,0,60,()->{
                                test.assertTrue(terminal.pushStack(new ItemStack(Items.DIAMOND,7)).isEmpty(),"Seven items must return to the real chest");
                                await(test,()->{terminal.getStacks();terminal.updateServer();return terminalCount(terminal)==7;},
                                        ()->observeTerminalDisplay(test,terminal,chest,7,60,test::succeed));
                            }));
                }));
    }
    @GameTest(maxTicks=1600)
    public void realTerminalDisplayDoesNotGrowWhileInventoryIsUnchanged(GameTestHelper test) {
        var level=test.getLevel();BlockPos start=test.absolutePos(new BlockPos(1,13,1));
        level.setBlock(start,Content.terminal.get().defaultBlockState().setValue(com.tom.storagemod.block.AbstractStorageTerminalBlock.FACING,Direction.EAST),3);
        var wire=Content.invCable.get().defaultBlockState().setValue(InventoryCableBlock.EAST,true).setValue(InventoryCableBlock.WEST,true);
        for(int i=1;i<4;i++)level.setBlock(start.east(i),wire,3);
        level.setBlock(start.east(4),Content.connector.get().defaultBlockState(),3);level.setBlock(start.east(5),Blocks.CHEST.defaultBlockState(),3);
        var chest=(ChestBlockEntity)level.getBlockEntity(start.east(5));chest.setItem(0,new ItemStack(Items.DIAMOND,12));
        var terminal=(com.tom.storagemod.block.entity.StorageTerminalBlockEntity)level.getBlockEntity(start);
        await(test,()->{
            terminal.getStacks();terminal.updateServer();
            return terminal.getStacks().values().stream().anyMatch(s->s.getStack().is(Items.DIAMOND));
        },()->observeTerminalDisplay(test,terminal,chest,12,600));
    }
    private static void observeTerminalDisplay(GameTestHelper test,com.tom.storagemod.block.entity.StorageTerminalBlockEntity terminal,ChestBlockEntity chest,int expected,int remaining) {
        observeTerminalDisplay(test,terminal,chest,expected,remaining,test::succeed);
    }
    private static long terminalCount(com.tom.storagemod.block.entity.StorageTerminalBlockEntity terminal){
        return terminal.getStacks().values().stream().filter(s->s.getStack().is(Items.DIAMOND)).mapToLong(StoredItemStack::getQuantity).sum();
    }
    private static void observeTerminalDisplay(GameTestHelper test,com.tom.storagemod.block.entity.StorageTerminalBlockEntity terminal,ChestBlockEntity chest,int expected,int remaining,Runnable done) {
        terminal.getStacks();terminal.updateServer();
        long displayed=terminalCount(terminal);
        test.assertTrue(chest.getItem(0).getCount()==expected,"The real chest must remain unchanged during idle display checks");
        test.assertTrue(displayed==expected,"Native terminal count inflated during repeated idle refresh: expected="+expected+", displayed="+displayed+", remaining="+remaining);
        if(remaining==0){done.run();return;}
        test.runAfterDelay(1,()->observeTerminalDisplay(test,terminal,chest,expected,remaining-1,done));
    }
    @GameTest(maxTicks=800)
    public void nativePriorityAndKeepLastArePreserved(GameTestHelper test) {
        var level=test.getLevel();BlockPos start=test.absolutePos(new BlockPos(1,11,1)),connector=start.east(),high=connector.east(),low=connector.north();
        level.setBlock(start,Content.invCable.get().defaultBlockState().setValue(InventoryCableBlock.EAST,true).setValue(InventoryCableBlock.WEST,true),3);
        level.setBlock(connector,Content.connector.get().defaultBlockState(),3);
        level.setBlock(high,Blocks.CHEST.defaultBlockState(),3);level.setBlock(low,Blocks.CHEST.defaultBlockState(),3);
        var highChest=(ChestBlockEntity)level.getBlockEntity(high);var lowChest=(ChestBlockEntity)level.getBlockEntity(low);
        highChest.setItem(0,new ItemStack(Items.IRON_INGOT,5));lowChest.setItem(0,new ItemStack(Items.IRON_INGOT,5));
        var highFilter=BlockFilter.getOrCreateFilterAt(level,high);highFilter.setPriority(com.tom.storagemod.util.Priority.HIGHEST);highFilter.setKeepLast(true);
        BlockFilter.getOrCreateFilterAt(level,low).setPriority(com.tom.storagemod.util.Priority.LOWEST);
        NetworkInventory terminal=new NetworkInventory();terminal.onLoad(level,start,Direction.DOWN,null);
        await(test,()->terminal.getAccess(level,start).getSlotCount()>=54,()->{
            var extracted=terminal.getAccess(level,start).pullMatchingStack(new ItemStack(Items.IRON_INGOT),6);
            test.assertTrue(extracted.getCount()==6,"Filtered extraction must complete using the native handlers");
            test.assertTrue(highChest.getItem(0).getCount()==1 && lowChest.getItem(0).getCount()==3,"Highest priority must be used first and keep-last must preserve one item");
            PersistentCables.LOG.info("TPC_NATIVE_FILTER verified: highest priority first, keep-last respected, total count conserved");test.succeed();
        });
    }
    @GameTest(maxTicks=800)
    public void interfaceProxyCyclePreservesNativeDeduplication(GameTestHelper test) {
        var level=test.getLevel();BlockPos start=test.absolutePos(new BlockPos(1,9,1));
        var wire=Content.invCable.get().defaultBlockState().setValue(InventoryCableBlock.EAST,true).setValue(InventoryCableBlock.WEST,true);
        level.setBlock(start,wire,3);level.setBlock(start.east(),Content.connector.get().defaultBlockState(),3);
        level.setBlock(start.east(2),Blocks.CHEST.defaultBlockState(),3);level.setBlock(start.east().north(),Content.invInterface.get().defaultBlockState(),3);
        var chest=(ChestBlockEntity)level.getBlockEntity(start.east(2));chest.setItem(0,new ItemStack(Items.LAPIS_LAZULI,16));
        NetworkInventory terminal=new NetworkInventory();terminal.onLoad(level,start,Direction.DOWN,null);
        await(test,()->terminal.getAccess(level,start).getSlotCount()>0,()->test.runAfterDelay(80,()->{
            var access=terminal.getAccess(level,start);
            test.assertTrue(access.getSlotCount()==27,"An interface exposing its own network must not duplicate slots or recurse");
            var extracted=access.pullMatchingStack(new ItemStack(Items.LAPIS_LAZULI),4);
            test.assertTrue(extracted.getCount()==4 && chest.getItem(0).getCount()==12,"Proxy cycle elimination must preserve real inventory conservation");
            test.succeed();
        }));
    }
    @GameTest(maxTicks=600)
    public void itemClumpHopperAndNetworkConserveItems(GameTestHelper test) {
        if(!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("item_clumps")){test.succeed();return;}
        var level=test.getLevel();BlockPos start=test.absolutePos(new BlockPos(1,7,1));
        final int clumpLimit;
        try {
            var config=com.google.gson.JsonParser.parseString(Files.readString(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("item-clumps.json"))).getAsJsonObject();
            Object rule=Class.forName("net.instantgratification.item_clumps.ItemClumpsFabric").getField("MAX_CLUMP_SIZE").get(null);
            // Item Clumps deliberately leaves this rule unregistered when Stack Size Adjuster is present.
            clumpLimit=rule==null?-1:(int)Class.forName("net.dasik.social.api.gamerule.DynamicGameRuleManager").getMethod("getInt",net.minecraft.world.level.Level.class,net.minecraft.world.level.gamerules.GameRule.class).invoke(null,level,rule);
            if(rule!=null)test.assertTrue(clumpLimit==config.get("maxClumpSize").getAsInt(),"The active Item Clumps game rule must match this profile");
            Object radius=Class.forName("net.instantgratification.item_clumps.ItemClumpsFabric").getField("MERGE_RADIUS").get(null);
            int actualRadius=(int)Class.forName("net.dasik.social.api.gamerule.DynamicGameRuleManager").getMethod("getInt",net.minecraft.world.level.Level.class,net.minecraft.world.level.gamerules.GameRule.class).invoke(null,level,radius);
            test.assertTrue(actualRadius==config.get("mergeRadius").getAsInt(),"The effective merge radius must follow this test profile");
        }catch(ReflectiveOperationException|java.io.IOException e){throw new RuntimeException(e);}
        var wire=Content.invCable.get().defaultBlockState().setValue(InventoryCableBlock.EAST,true).setValue(InventoryCableBlock.WEST,true);
        level.setBlock(start,wire,3);level.setBlock(start.east(),Content.connector.get().defaultBlockState(),3);
        level.setBlock(start.east(2),Blocks.HOPPER.defaultBlockState(),3);
        var hopper=(net.minecraft.world.level.block.entity.HopperBlockEntity)level.getBlockEntity(start.east(2));
        int limit=new ItemStack(Items.COPPER_INGOT).getMaxStackSize();
        int groundCount=Math.min(Integer.MAX_VALUE/2,limit)+7;
        var entity=new net.minecraft.world.entity.item.ItemEntity(level,start.getX()+.5,start.getY()+2,start.getZ()+.5,new ItemStack(Items.COPPER_INGOT,groundCount));
        test.assertTrue(net.minecraft.world.level.block.entity.HopperBlockEntity.addItem(hopper,entity),"Item Clumps' hopper extraction must accept a ground stack");
        test.assertTrue(hopper.getItem(0).getCount()==1 && entity.getItem().getCount()==groundCount-1,"Ground clump and real hopper counts must conserve the exact total");
        NetworkInventory terminal=new NetworkInventory();terminal.onLoad(level,start,Direction.DOWN,null);
        await(test,()->terminal.getAccess(level,start).getSlotCount()>0,()->{
            var extracted=terminal.getAccess(level,start).pullMatchingStack(new ItemStack(Items.COPPER_INGOT),1);
            test.assertTrue(extracted.getCount()==1 && hopper.isEmpty(),"A clump-fed hopper must be actionable through the guarded cable network");
            test.assertTrue(entity.getItem().getCount()+extracted.getCount()==groundCount,"Network extraction must not duplicate or lose clumped items");
            PersistentCables.LOG.info("TPC_CLUMPS_COMPAT verified: configured clump maximum={}, inventory maximum={}, ground count={}, hopper/network round trip conserved",clumpLimit,limit,groundCount);test.succeed();
        });
    }
    @GameTest(maxTicks=600)
    public void dynamicStackLimitsAndItemComponentsArePreserved(GameTestHelper test) {
        var level=test.getLevel();BlockPos start=test.absolutePos(new BlockPos(1,5,1));
        boolean adjusted=net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("stack-size-adjuster");
        int maximum=new ItemStack(Items.COBBLESTONE).getMaxStackSize();
        if(adjusted) {
            try{
                var file=net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("stack-size-adjuster.json");
                var config=com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                test.assertTrue(maximum==config.get("items64Limit").getAsInt(),"The active 64-category limit must match this test profile");
                test.assertTrue(new ItemStack(Items.ENDER_PEARL).getMaxStackSize()==config.get("items16Limit").getAsInt(),"16-stack category must match the current configuration");
                test.assertTrue(new ItemStack(Items.DIAMOND_SWORD).getMaxStackSize()==config.get("items1Limit").getAsInt(),"1-stack category must match the current configuration");
            }catch(java.io.IOException e){throw new RuntimeException(e);}
        }
        var wire=Content.invCable.get().defaultBlockState().setValue(InventoryCableBlock.EAST,true).setValue(InventoryCableBlock.WEST,true);
        level.setBlock(start,wire,3);level.setBlock(start.east(),wire,3);level.setBlock(start.east(2),Content.connector.get().defaultBlockState(),3);level.setBlock(start.east(3),Blocks.CHEST.defaultBlockState(),3);
        var chest=(ChestBlockEntity)level.getBlockEntity(start.east(3));chest.setItem(0,new ItemStack(Items.COBBLESTONE,maximum));
        ItemStack named=new ItemStack(Items.PAPER,maximum);named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("TPC component identity"));
        chest.setItem(1,named);chest.setItem(2,new ItemStack(Items.PAPER,maximum));
        NetworkInventory terminal=new NetworkInventory();terminal.onLoad(level,start,Direction.DOWN,null);
        await(test,()->terminal.getAccess(level,start).getSlotCount()>0,()->{
            var access=terminal.getAccess(level,start);int amount=Math.max(1,(int)((long)maximum*3/4));
            var extracted=access.pullMatchingStack(new ItemStack(Items.COBBLESTONE),amount);
            test.assertTrue(extracted.getCount()==amount && chest.getItem(0).getCount()==maximum-amount,"Extraction must respect the actual dynamic limit without a 64 clamp");
            test.assertTrue(access.pushStack(extracted).isEmpty() && chest.getItem(0).getCount()==maximum,"Insertion must restore the exact legal stack");
            int namedAmount=Math.max(1,maximum/4);var namedResult=access.pullMatchingStack(named,namedAmount);
            test.assertTrue(namedResult.getCount()==namedAmount && namedResult.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME),"Components must survive extraction");
            test.assertTrue(chest.getItem(2).getCount()==maximum,"A different component variant must not be merged");
            PersistentCables.LOG.info("TPC_STACK_COMPAT verified: native maximum={}, extracted={}, component variants preserved",maximum,amount);test.succeed();
        });
    }
    @GameTest(maxTicks=2500)
    public void persistentFixtureSurvivesARealServerRestart(GameTestHelper test) {
        var level=test.getLevel();BlockPos start=new BlockPos(2_000_000,64,-2_000_000);int length=512;
        int first=start.getX()>>4,last=(start.getX()+length)>>4,z=start.getZ()>>4;
        Path marker=level.getServer().getWorldPath(LevelResource.ROOT).resolve("tpc-persistence-fixture-v1.txt");
        boolean recovering=Files.exists(marker);
        if(!recovering) {
            for(int x=first;x<=last;x++){level.setChunkForced(x,z,true);level.getChunk(x,z);}
            var wire=Content.invCable.get().defaultBlockState().setValue(InventoryCableBlock.EAST,true).setValue(InventoryCableBlock.WEST,true);
            for(int i=0;i<length;i++)level.setBlock(start.offset(i,0,0),wire,3);
            level.setBlock(start.offset(length,0,0),Content.connector.get().defaultBlockState(),3);
            level.setBlock(start.offset(length+1,0,0),Blocks.CHEST.defaultBlockState(),3);
            ((ChestBlockEntity)level.getBlockEntity(start.offset(length+1,0,0))).setItem(0,new ItemStack(Items.GOLD_INGOT,40));
        } else {
            test.assertTrue(level.getChunkSource().getChunkNow((first+last)/2,z)==null,"Recovery must start with the persisted middle unloaded");
            level.setChunkForced(first,z,true);level.getChunk(first,z);level.setChunkForced(last,z,true);level.getChunk(last,z);
        }
        NetworkInventory terminal=new NetworkInventory();terminal.onLoad(level,start,Direction.DOWN,null);
        await(test,()->terminal.getAccess(level,start).getSlotCount()>0,()->{
            if(recovering) {
                var access=terminal.getAccess(level,start);var taken=access.pullMatchingStack(new ItemStack(Items.GOLD_INGOT),4);
                test.assertTrue(taken.getCount()==4,"Restored topology must access the real saved chest");
                test.assertTrue(access.pushStack(taken).isEmpty(),"Round-trip must put the same items back");
                test.assertTrue(level.getChunkSource().getChunkNow((first+last)/2,z)==null,"Restart recovery must not load the middle");
                PersistentCables.LOG.info("TPC_RESTART_FIXTURE VERIFIED: persisted 512-cable world, middle never loaded, real extract/insert conserved");
            } else {
                try{Files.writeString(marker,"512 real cable blocks; saved by the GameTest server shutdown");}catch(Exception e){throw new RuntimeException(e);}
                PersistentCables.LOG.info("TPC_RESTART_FIXTURE SEEDED: run this suite again to verify a real process restart");
            }
            for(int x=first;x<=last;x++)level.setChunkForced(x,z,false);test.succeed();
        });
    }
    @GameTest(maxTicks=500)
    public void directionChangesUseOriginalCableApi(GameTestHelper test) {
        var level=test.getLevel();BlockPos start=test.absolutePos(new BlockPos(1,4,1));
        var wire=Content.invCable.get().defaultBlockState().setValue(InventoryCableBlock.EAST,true).setValue(InventoryCableBlock.WEST,true);
        level.setBlock(start,wire,3);level.setBlock(start.east(2),wire,3);level.setBlock(start.east(3),Content.connector.get().defaultBlockState(),3);
        var directional=Content.invCableConnector.get().defaultBlockState().setValue(com.tom.storagemod.block.InventoryCableConnectorBlock.FACING,Direction.EAST)
            .setValue(InventoryCableBlock.EAST,true).setValue(InventoryCableBlock.WEST,true);
        level.setBlock(start.east(),directional,3);
        test.runAtTickTime(100,()->{
            var m=PersistentCables.manager(level);test.assertFalse(m.nodes(start).contains(start.east(3)),"A connector's inventory-facing side must not be a cable bridge");
            level.setBlock(start.east(),directional.setValue(com.tom.storagemod.block.InventoryCableConnectorBlock.FACING,Direction.NORTH),3);
            await(test,()->m.nodes(start).contains(start.east(3)),test::succeed);
        });
    }
    private static void await(GameTestHelper test,BooleanSupplier condition,Runnable next) {
        if(condition.getAsBoolean())next.run();else test.runAfterDelay(1,()->await(test,condition,next));
    }
    @GameTest(maxTicks=3000)
    public void unloadedMiddleAndContainerAreHandledDifferently(GameTestHelper test) {
        var level=test.getLevel();int length=512;
        BlockPos candidate=test.absolutePos(new BlockPos(4096,2,64));
        BlockPos start=new BlockPos(candidate.getX() & ~15,candidate.getY(),candidate.getZ());
        int chunkZ=start.getZ()>>4,first=start.getX()>>4,last=(start.getX()+length)>>4;
        for(int x=first;x<=last;x++){level.setChunkForced(x,chunkZ,true);level.getChunk(x,chunkZ);}
        var wire=Content.invCable.get().defaultBlockState().setValue(InventoryCableBlock.EAST,true).setValue(InventoryCableBlock.WEST,true);
        for(int i=0;i<length;i++)level.setBlock(start.offset(i,0,0),wire,3);
        BlockPos connectorPos=start.offset(length,0,0),chestPos=connectorPos.east();
        level.setBlock(connectorPos,Content.connector.get().defaultBlockState(),3);level.setBlock(chestPos,Blocks.CHEST.defaultBlockState(),3);
        var chest=(ChestBlockEntity)level.getBlockEntity(chestPos);chest.setItem(0,new ItemStack(Items.EMERALD,32));
        NetworkInventory terminal=new NetworkInventory();terminal.onLoad(level,start,Direction.DOWN,null);
        var manager=PersistentCables.manager(level);
        await(test,()->manager.nodes(start).contains(connectorPos) && terminal.getAccess(level,start).getSlotCount()>0,()->{
            for(int x=first+1;x<last;x++)level.setChunkForced(x,chunkZ,false);
            int middle=(first+last)/2;
            await(test,()->level.getChunkSource().getChunkNow(middle,chunkZ)==null,()->{
                var access=terminal.getAccess(level,start);
                test.assertTrue(access.pullMatchingStack(new ItemStack(Items.EMERALD),4).getCount()==4,"Indexed real cable must cross an actually unloaded middle chunk: "+manager.status());
                test.assertTrue(level.getChunkSource().getChunkNow(middle,chunkZ)==null,"Query must not reload the middle chunk");
                test.assertTrue(chest.getItem(0).getCount()==28,"Cross-chunk extraction must conserve items");
                var borrowed=access.tracker().findSlot(s->s.getStack().is(Items.EMERALD),false);
                level.setChunkForced(last,chunkZ,false);
                await(test,()->level.getChunkSource().getChunkNow(last,chunkZ)==null,()->{
                    test.assertTrue(access.pullMatchingStack(new ItemStack(Items.EMERALD),1).isEmpty(),"Unloaded connector must not be actionable");
                    test.assertTrue(borrowed!=null && borrowed.extract(1).isEmpty(),"Unloaded chest's borrowed slot must be revoked");
                    level.setChunkForced(last,chunkZ,true);level.getChunk(last,chunkZ);
                    // A FULL chunk can precede activation of its native block-entity ticker.
                    // Wait for actual native inventory discovery rather than a fixed tick count.
                    await(test,()->manager.nodes(start).contains(connectorPos) && terminal.getAccess(level,start).getSlotCount()>0,()->{
                        var fresh=terminal.getAccess(level,start);
                        var endpoint=(InventoryConnectorBlockEntity)level.getBlockEntity(connectorPos);
                        long count=fresh.tracker().streamWrappedStacks(false).filter(s->s.getStack().is(Items.EMERALD)).mapToLong(StoredItemStack::getQuantity).sum();
                        test.assertTrue(fresh.pullMatchingStack(new ItemStack(Items.EMERALD),2).getCount()==2,"Remote reloading must recover fresh access: slots="+fresh.getSlotCount()+", listed="+count+", endpoints="+endpoint.getConnectedBlocks()+", "+manager.status());
                        test.assertTrue(level.getChunkSource().getChunkNow(middle,chunkZ)==null,"Recovery must also leave the middle unloaded");
                        level.setChunkForced(first,chunkZ,false);level.setChunkForced(last,chunkZ,false);test.succeed();
                    });
                });
            });
        });
    }
    @GameTest(maxTicks=600)
    public void inventoryDisconnectInvalidatesBorrowedReferences(GameTestHelper test) {
        var level=test.getLevel();
        BlockPos start=test.absolutePos(new BlockPos(1,2,1));
        var wire=Content.invCable.get().defaultBlockState().setValue(InventoryCableBlock.EAST,true).setValue(InventoryCableBlock.WEST,true);
        for(int i=0;i<4;i++)level.setBlock(start.offset(i,0,0),wire,3);
        BlockPos connectorPos=start.offset(4,0,0),chestPos=start.offset(5,0,0);
        level.setBlock(connectorPos,Content.connector.get().defaultBlockState(),3);
        level.setBlock(chestPos,Blocks.CHEST.defaultBlockState(),3);
        var chest=(ChestBlockEntity)level.getBlockEntity(chestPos);chest.setItem(0,new ItemStack(Items.DIAMOND,64));
        NetworkInventory terminal=new NetworkInventory();terminal.onLoad(level,start,Direction.DOWN,null);
        test.runAtTickTime(100,()->{
            var manager=PersistentCables.manager(level);
            // The manager indexes the actual placed blocks; no synthetic graph is used here.
            test.assertTrue(manager.nodes(start).contains(connectorPos),"Actual cable nodes must be indexed");
            IInventoryAccess access=terminal.getAccess(level,start);
            ItemStack extracted=access.pullMatchingStack(new ItemStack(Items.DIAMOND),3);
            test.assertTrue(extracted.getCount()==3 && chest.getItem(0).getCount()==61,"Real chest extraction must conserve items");
            InventorySlot borrowed=access.tracker().findSlot(s->s.getStack().is(Items.DIAMOND),false);
            Storage<ItemVariant> storage=access.getPlatformHandler();
            var view=storage.iterator().next();var underlying=view.getUnderlyingView();
            test.assertTrue(borrowed!=null,"Must borrow an actionable slot before disconnect");
            level.setBlock(start.offset(2,0,0),Blocks.AIR.defaultBlockState(),3);
            test.assertTrue(access.pullMatchingStack(new ItemStack(Items.DIAMOND),1).isEmpty(),"Old access must fail in the mutation tick");
            test.assertTrue(borrowed.extract(1).isEmpty(),"Previously returned slot must fail after disconnect");
            try(Transaction tx=Transaction.openOuter()) {
                test.assertTrue(storage.extract(ItemVariant.of(Items.DIAMOND),1,tx)==0,"Old Fabric storage must fail");tx.commit();
            }
            try(Transaction tx=Transaction.openOuter()) {
                test.assertTrue(view.extract(ItemVariant.of(Items.DIAMOND),1,tx)==0 && underlying.extract(ItemVariant.of(Items.DIAMOND),1,tx)==0,"Borrowed and underlying views must stay guarded");tx.commit();
            }
            test.assertTrue(chest.getItem(0).getCount()==61,"Denied requests must not change inventory");
            level.setBlock(start.offset(2,0,0),wire,3);
            // Other fixtures may exhaust this dimension's same-tick processing budget.
            // Safety is immediate; recovery can continue within subsequent tick budgets.
            test.assertTrue(borrowed.extract(1).isEmpty(),"A retired slot lease must never become valid again");
        });
        test.runAtTickTime(150,()->{
            var access=terminal.getAccess(level,start);
            test.assertTrue(access.pullMatchingStack(new ItemStack(Items.DIAMOND),2).getCount()==2,"Fresh access must recover");
            test.assertTrue(chest.getItem(0).getCount()==59,"Recovered requests must conserve inventory");
        });
        test.runAtTickTime(151,()->{
            var access=terminal.getAccess(level,start);access.tracker().getChangeTracker(level);
            long count=access.tracker().streamWrappedStacks(false).filter(s->s.getStack().is(Items.DIAMOND)).mapToLong(StoredItemStack::getQuantity).sum();
            test.assertTrue(count==59,"Repeated guarded iteration must preserve slot identity and avoid phantom list counts: "+count);
            access.pullMatchingStack(new ItemStack(Items.DIAMOND),1);
        });
        test.runAtTickTime(152,()->{
            var access=terminal.getAccess(level,start);access.tracker().getChangeTracker(level);
            long count=access.tracker().streamWrappedStacks(false).filter(s->s.getStack().is(Items.DIAMOND)).mapToLong(StoredItemStack::getQuantity).sum();
            test.assertTrue(count==58,"Updated display count must match the real chest: "+count);test.succeed();
        });
    }
}
