package dev.tpc.integration;

import com.tom.storagemod.Content;
import com.tom.storagemod.block.*;
import com.tom.storagemod.block.entity.*;
import com.tom.storagemod.inventory.*;
import dev.tpc.virtual.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.transfer.v1.item.*;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.function.BooleanSupplier;

/** Regressions reported in the user's wired-terminal and split-warehouse layouts. */
public final class RegressionGameTests {
    private static void await(GameTestHelper t,BooleanSupplier ready,Runnable done){if(ready.getAsBoolean())done.run();else t.runAfterDelay(1,()->await(t,ready,done));}
    private static BlockState wire(){var s=Content.invCable.get().defaultBlockState();for(var d:Direction.values())s=s.setValue(InventoryCableBlock.DIR_TO_PROPERTY[d.ordinal()],true);return s;}
    private static void barrel(GameTestHelper t,BlockPos p){VirtualFixtureReset.clear(VirtualNetwork.get(t.getLevel()),p);t.getLevel().setBlock(p,VirtualContent.BARREL.defaultBlockState(),3);}
    private static IInventoryAccess access(StorageTerminalBlockEntity terminal){return ((dev.tpc.integration.mixin.TerminalTestAccess)terminal).tvsn$itemCache().getAccess(terminal.getLevel(),terminal.getBlockPos());}
    private static long shown(StorageTerminalBlockEntity terminal){terminal.getStacks();terminal.updateServer();return terminal.getStacks().values().stream().filter(s->s.getStack().is(Items.DIAMOND)).mapToLong(StoredItemStack::getQuantity).sum();}

    @GameTest(maxTicks=1800) public void wiredTerminalFindsAttachedMainConnector(GameTestHelper t){wired(t,false,Direction.EAST);}
    // Separate entry points exercise all four placement directions and both native connector types.
    @GameTest(maxTicks=1800) public void wiredTerminalFindsCableConnectorEast(GameTestHelper t){wired(t,true,Direction.EAST);}
    @GameTest(maxTicks=1800) public void wiredTerminalFindsCableConnectorWest(GameTestHelper t){wired(t,true,Direction.WEST);}
    @GameTest(maxTicks=1800) public void wiredTerminalFindsCableConnectorNorth(GameTestHelper t){wired(t,true,Direction.NORTH);}
    @GameTest(maxTicks=1800) public void wiredTerminalFindsCableConnectorSouth(GameTestHelper t){wired(t,true,Direction.SOUTH);}
    private static void wired(GameTestHelper t,boolean cable,Direction facing){
        var l=t.getLevel();var p=t.absolutePos(new BlockPos(5,6,5));var source=p.relative(facing,4);
        l.setBlock(p,Content.craftingTerminal.get().defaultBlockState().setValue(AbstractStorageTerminalBlock.FACING,facing),3);
        for(int i=1;i<4;i++)l.setBlock(p.relative(facing,i),wire(),3);
        barrel(t,source.relative(facing));barrel(t,source.relative(facing,2));
        var connectorState=cable?Content.invCableConnector.get().defaultBlockState().setValue(InventoryCableConnectorBlock.FACING,facing):Content.connector.get().defaultBlockState();
        if(cable)for(var d:Direction.values())connectorState=connectorState.setValue(InventoryCableBlock.DIR_TO_PROPERTY[d.ordinal()],true);
        l.setBlock(source,connectorState,3);
        var terminal=(StorageTerminalBlockEntity)l.getBlockEntity(p);var connector=(IInventoryConnector)l.getBlockEntity(source);
        await(t,()->connector.hasConnectedInventories() && access(terminal).getSlotCount()==128,()->{
            t.assertTrue(terminal.pushStack(new ItemStack(Items.DIAMOND,23)).isEmpty(),"A barrel-facing connector must be sufficient without an empty connector");
            await(t,()->shown(terminal)==23,()->t.runAfterDelay(80,()->{
                t.assertTrue(shown(terminal)==23,"A cable-mounted crafting terminal must keep its exact count");
                var result=terminal.pullStack(new StoredItemStack(new ItemStack(Items.DIAMOND)),23);t.assertTrue(result!=null && result.getQuantity()==23,"Stock must really be extractable");t.succeed();
            }));
        });
    }

    @GameTest(maxTicks=1800)
    public void joiningStockedWarehouseRefreshesCableConnectorTerminal(GameTestHelper t){
        var l=t.getLevel();var p=t.absolutePos(new BlockPos(1,6,2));var ca=p.east(3);var cb=p.east(8);
        barrel(t,ca.south());barrel(t,cb.south());
        for(var cp:new BlockPos[]{ca,cb}){
            var state=Content.invCableConnector.get().defaultBlockState().setValue(InventoryCableConnectorBlock.FACING,Direction.SOUTH);
            for(var d:Direction.values())state=state.setValue(InventoryCableBlock.DIR_TO_PROPERTY[d.ordinal()],true);l.setBlock(cp,state,3);
        }
        for(int i=1;i<3;i++)l.setBlock(p.east(i),wire(),3);
        l.setBlock(p,Content.craftingTerminal.get().defaultBlockState().setValue(AbstractStorageTerminalBlock.FACING,Direction.EAST),3);
        var terminal=(StorageTerminalBlockEntity)l.getBlockEntity(p);var b=(InventoryCableConnectorBlockEntity)l.getBlockEntity(cb);
        await(t,()->access(terminal).getSlotCount()==64 && b.getMergedHandler().getSlotCount()==64,()->{
            t.assertTrue(shown(terminal)==0 && b.getMergedHandler().pushStack(new ItemStack(Items.DIAMOND,31)).isEmpty(),"Prepare an empty terminal network and an isolated stocked warehouse");
            for(int i=4;i<8;i++)l.setBlock(p.east(i),wire(),3);
            await(t,()->access(terminal).getSlotCount()==128,()->{
                t.assertTrue(access(terminal).tracker().countItems(new StoredItemStack(new ItemStack(Items.DIAMOND)))==31,"The connector must already expose real stock after joining");
                t.runAfterDelay(40,()->{t.assertTrue(shown(terminal)==31,"A newly joined stocked ledger view must refresh the terminal even if its local revision equals the previous empty view; displayed="+shown(terminal));t.assertTrue(terminal.pullStack(new StoredItemStack(new ItemStack(Items.DIAMOND)),31).getQuantity()==31,"Displayed stock must be extractable");t.succeed();});
            });
        });
    }

    @GameTest(maxTicks=1800)
    public void mixedNativeStacksConsume64RealSlots(GameTestHelper t){
        var l=t.getLevel();var p=t.absolutePos(new BlockPos(3,6,3));barrel(t,p);l.setBlock(p.west(),Content.connector.get().defaultBlockState(),3);
        var c=(InventoryConnectorBlockEntity)l.getBlockEntity(p.west());var n=VirtualNetwork.get(l);
        await(t,()->c.getMergedHandler().getSlotCount()==64,()->{
            Storage<ItemVariant> storage=c.getMergedHandler().getPlatformHandler();ItemVariant stone=ItemVariant.of(Items.STONE),pearl=ItemVariant.of(Items.ENDER_PEARL),pick=ItemVariant.of(Items.DIAMOND_PICKAXE);
            try(var tx=Transaction.openOuter()){
                t.assertTrue(storage.insert(stone,stone.toStack().getMaxStackSize()+1,tx)==stone.toStack().getMaxStackSize()+1,"Stone must use a second slot");
                t.assertTrue(storage.insert(pearl,pearl.toStack().getMaxStackSize()+1,tx)==pearl.toStack().getMaxStackSize()+1,"Pearls must use their current native limit");
                long remaining=60L*pick.toStack().getMaxStackSize();t.assertTrue(storage.insert(pick,remaining+1,tx)==remaining,"Identical tools must occupy all remaining slots");tx.commit();
            }
            t.assertTrue(n.ledger.usedSlots(p.asLong())==64 && c.getMergedHandler().getFreeSlotCount()==0,"All 64 slots must be reported occupied");
            try(var tx=Transaction.openOuter()){
                t.assertTrue(storage.insert(ItemVariant.of(Items.DIRT),1,tx)==0,"A 65th slot must be denied");
                t.assertTrue(storage.insert(stone,stone.toStack().getMaxStackSize()-1,tx)==stone.toStack().getMaxStackSize()-1,"A partial stack can still be filled without a new slot");tx.commit();
            }
            long before=n.ledger.get(p.asLong()).amount(stone);try(var outer=Transaction.openOuter()){
                t.assertTrue(storage.extract(stone,before,outer)==before,"Prepare a nested rollback across multiple stacks");
                try(var inner=Transaction.openNested(outer)){t.assertTrue(storage.insert(ItemVariant.of(Items.DIRT),64,inner)==64,"The nested transaction must see released slots");inner.commit();}
            }
            t.assertTrue(n.ledger.get(p.asLong()).amount(stone)==before && n.ledger.get(p.asLong()).amount(ItemVariant.of(Items.DIRT))==0 && n.ledger.usedSlots(p.asLong())==64,"Rolling back must restore counts and slot accounting together");t.succeed();
        });
    }

    @GameTest(maxTicks=4500)
    public void breakingLargeWarehouseBridgeRedistributesBeforeDropping(GameTestHelper t){
        bridge(t,1);
    }
    @GameTest(maxTicks=4500)
    public void breakingMultipleConnectingBarrelsQueuesRedistributionInsteadOfDropping(GameTestHelper t){
        bridge(t,2);
    }
    private static void bridge(GameTestHelper t,int cuts){
        var l=t.getLevel();var base=new BlockPos(cuts==1?11_000_002:14_000_002,70,11_000_002);int first=base.getX()>>4,last=base.east(95).getX()>>4,z=base.getZ()>>4;
        for(int x=first;x<=last;x++)l.setChunkForced(x,z,true);
        for(int i=0;i<96;i++)barrel(t,base.east(i));l.setBlock(base.west(),Content.connector.get().defaultBlockState(),3);
        var c=(InventoryConnectorBlockEntity)l.getBlockEntity(base.west());var n=VirtualNetwork.get(l);var diamond=ItemVariant.of(Items.DIAMOND);long total=(cuts+1)*64L*diamond.toStack().getMaxStackSize();
        await(t,()->c.getMergedHandler().getSlotCount()==96*64,()->{
            Storage<ItemVariant> storage=c.getMergedHandler().getPlatformHandler();try(var tx=Transaction.openOuter()){t.assertTrue(storage.insert(diamond,total,tx)==total,"Prepare two fully occupied adjacent barrels in a large network");tx.commit();}
            for(int i=1;i<=cuts;i++){var middle=base.east(i);t.assertTrue(n.ledger.get(middle.asLong()).amount(diamond)>0,"Every removed connecting unit must own real items");l.setBlock(middle,Blocks.AIR.defaultBlockState(),3);}
            if(cuts>1)t.assertTrue(n.overflowJobs()>0,"The second large, uncached component must use durable deferred settlement");
            await(t,()->java.util.stream.IntStream.rangeClosed(1,cuts).allMatch(i->n.ledger.get(base.east(i).asLong())==null) && n.overflowJobs()==0,()->{
                long kept=0;for(int i=0;i<96;i++){var node=n.ledger.get(base.east(i).asLong());if(node!=null)kept+=node.amount(diamond);}
                t.assertTrue(kept==total,"A new graph search exceeding the immediate budget must not turn available capacity into dropped stock; kept="+kept+", expected="+total);
                for(int x=first;x<=last;x++)l.setChunkForced(x,z,false);t.succeed();
            });
        });
    }

    @GameTest(maxTicks=4500)
    public void unloadingLastConnectorRevokesWiredTerminalUntilAnyConnectorReturns(GameTestHelper t){
        unloading(t,false);
    }
    @GameTest(maxTicks=4500)
    public void unloadingLastBarrelFacingCableConnectorAlsoRevokesWiredTerminal(GameTestHelper t){
        unloading(t,true);
    }
    private static void unloading(GameTestHelper t,boolean cable){
        var l=t.getLevel();var p=new BlockPos(cable?13_000_002:12_000_002,70,12_000_002);var cp=p.east(65);var bp=cp.east();int first=p.getX()>>4,last=bp.getX()>>4,z=p.getZ()>>4;
        for(int x=first;x<=last;x++)l.setChunkForced(x,z,true);
        l.setBlock(p,Content.craftingTerminal.get().defaultBlockState().setValue(AbstractStorageTerminalBlock.FACING,Direction.EAST),3);
        for(int i=1;i<65;i++)l.setBlock(p.east(i),wire(),3);barrel(t,bp);
        var state=cable?Content.invCableConnector.get().defaultBlockState().setValue(InventoryCableConnectorBlock.FACING,Direction.EAST):Content.connector.get().defaultBlockState();
        if(cable)for(var d:Direction.values())state=state.setValue(InventoryCableBlock.DIR_TO_PROPERTY[d.ordinal()],true);l.setBlock(cp,state,3);
        var terminal=(StorageTerminalBlockEntity)l.getBlockEntity(p);var n=VirtualNetwork.get(l);var v=ItemVariant.of(Items.DIAMOND);
        await(t,()->access(terminal).getSlotCount()==64,()->{
            t.assertTrue(terminal.pushStack(new ItemStack(Items.DIAMOND,17)).isEmpty(),"A remote loaded connector must be enough");
            await(t,()->shown(terminal)==17,()->{
                Storage<ItemVariant> borrowed=access(terminal).getPlatformHandler();for(int x=first+1;x<=last;x++)l.setChunkForced(x,z,false);
                await(t,()->l.getChunkSource().getChunkNow(last,z)==null,()->{
                    try(var tx=Transaction.openOuter()){t.assertTrue(borrowed.extract(v,1,tx)==0,"Unloading the last connector must revoke borrowed storage immediately");tx.commit();}
                    await(t,()->shown(terminal)==0,()->{
                        t.assertTrue(!terminal.pushStack(new ItemStack(Items.DIAMOND,1)).isEmpty(),"A cable-only terminal must deny insertion with all connectors unloaded");
                        t.assertTrue(n.ledger.get(bp.asLong()).amount(v)==17,"Denied operations must preserve all offline stock");
                        var local=p.east(2).north();l.setBlock(local,Content.connector.get().defaultBlockState(),3);
                        await(t,()->shown(terminal)==17,()->{
                            t.assertTrue(l.getChunkSource().getChunkNow(last,z)==null,"An empty loaded connector must not force-load the warehouse");
                            l.setBlock(local,Blocks.AIR.defaultBlockState(),3);
                            await(t,()->shown(terminal)==0,()->{
                                l.setChunkForced(last,z,true);await(t,()->shown(terminal)==17,()->{l.setChunkForced(first,z,false);l.setChunkForced(last,z,false);t.succeed();});
                            });
                        });
                    });
                });
            });
        });
    }
}
