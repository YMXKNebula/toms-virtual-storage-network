package dev.tpc.integration;

import com.tom.storagemod.Content;
import com.tom.storagemod.block.*;
import com.tom.storagemod.block.entity.*;
import com.tom.storagemod.inventory.*;
import com.tom.storagemod.util.Priority;
import dev.tpc.virtual.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import java.util.function.BooleanSupplier;

/** The upstream ticker, filter and priority implementations execute unchanged in these fixtures. */
public final class NativeAutomationTests {
    private static void await(GameTestHelper t,BooleanSupplier ready,Runnable next){if(ready.getAsBoolean())next.run();else t.runAfterDelay(1,()->await(t,ready,next));}
    private static void barrel(GameTestHelper t,BlockPos p){VirtualFixtureReset.clear(VirtualNetwork.get(t.getLevel()),p);t.getLevel().setBlock(p,VirtualContent.BARREL.defaultBlockState(),3);}
    private static net.minecraft.world.level.block.state.BlockState wire(){return Content.invCable.get().defaultBlockState().setValue(InventoryCableBlock.EAST,true).setValue(InventoryCableBlock.WEST,true);}
    @GameTest(maxTicks=1400)
    public void everyVariantCanFill64SlotsUsingTheCurrentNativeStackLimit(GameTestHelper t){
        var l=t.getLevel();var p=t.absolutePos(new BlockPos(3,6,2));barrel(t,p.east());l.setBlock(p,Content.connector.get().defaultBlockState(),3);var c=(InventoryConnectorBlockEntity)l.getBlockEntity(p);
        await(t,()->c.getMergedHandler().getSlotCount()==64,()->{
            Storage<ItemVariant> storage=c.getMergedHandler().getPlatformHandler();var world=VirtualNetwork.get(l);
            for(var item:new Item[]{Items.DIAMOND,Items.ENDER_PEARL,Items.DIAMOND_SWORD,Items.POTION}){
                var v=ItemVariant.of(item);int limit=v.toStack().getMaxStackSize();
                long capacity=64L*limit;
                try(var tx=Transaction.openOuter()){t.assertTrue(storage.insert(v,Long.MAX_VALUE,tx)==capacity,"A single barrel must accept 64 current native stacks of "+item);tx.commit();}
                try(var tx=Transaction.openOuter()){t.assertTrue(storage.insert(v,1,tx)==0,"A full barrel must reject a 65th stack");tx.commit();}
                t.assertTrue(world.ledger.get(p.east().asLong()).amount(v)==capacity && c.getMergedHandler().getFreeSlotCount()==0,"The committed allocation must match all 64 native slots");
                var taken=c.getMergedHandler().pullMatchingStack(v.toStack(),Long.MAX_VALUE);t.assertTrue(taken.getCount()==limit,"An ordinary ItemStack extraction must respect its native stack limit");t.assertTrue(c.getMergedHandler().pushStack(taken).isEmpty(),"The extracted stack must be returnable without changing totals");
                try(var tx=Transaction.openOuter()){t.assertTrue(storage.extract(v,Long.MAX_VALUE,tx)==capacity,"Every legitimately inserted item must be extractable");tx.commit();}
            }
            t.assertTrue(world.ledger.get(p.east().asLong()).contents().isEmpty(),"Draining all tested variants must leave no phantom quantities");t.succeed();
        });
    }
    @GameTest(maxTicks=1800)
    public void twoTerminalsAndConnectorsOnOneContiguousWarehouseNeverDoubleCounts(GameTestHelper t){
        var l=t.getLevel();BlockPos a=t.absolutePos(new BlockPos(4,6,3)),b=a.east();barrel(t,a);barrel(t,b);var c1=a.west();var c2=b.east();
        l.setBlock(c1,Content.connector.get().defaultBlockState(),3);l.setBlock(c2,Content.connector.get().defaultBlockState(),3);
        l.setBlock(c1.west(),Content.terminal.get().defaultBlockState().setValue(AbstractStorageTerminalBlock.FACING,Direction.EAST),3);l.setBlock(c2.east(),Content.terminal.get().defaultBlockState().setValue(AbstractStorageTerminalBlock.FACING,Direction.WEST),3);
        var left=(InventoryConnectorBlockEntity)l.getBlockEntity(c1);var right=(InventoryConnectorBlockEntity)l.getBlockEntity(c2);var tl=(StorageTerminalBlockEntity)l.getBlockEntity(c1.west());var tr=(StorageTerminalBlockEntity)l.getBlockEntity(c2.east());
        await(t,()->left.getMergedHandler().getSlotCount()==128 && right.getMergedHandler().getSlotCount()==128 && ((dev.tpc.integration.mixin.TerminalTestAccess)tl).tvsn$itemCache().getAccess(l,c1.west()).getSlotCount()==128 && ((dev.tpc.integration.mixin.TerminalTestAccess)tr).tvsn$itemCache().getAccess(l,c2.east()).getSlotCount()==128,()->{
            t.assertTrue(left.getMergedHandler().pushStack(new ItemStack(Items.DIAMOND,17)).isEmpty() && right.getMergedHandler().pushStack(new ItemStack(Items.DIAMOND,19)).isEmpty(),"Both native gateways must accept legitimate stock");
            t.runAfterDelay(1,()->observePair(t,tl,tr,36,160,()->{
                t.assertTrue(left.getMergedHandler().pullMatchingStack(new ItemStack(Items.DIAMOND),11).getCount()==11 && right.getMergedHandler().pullMatchingStack(new ItemStack(Items.DIAMOND),25).getCount()==25,"The two gateways must share the same extractable total");
                t.runAfterDelay(1,()->observePair(t,tl,tr,0,60,()->{t.assertTrue(VirtualNetwork.get(l).ledger.get(a.asLong()).amount(ItemVariant.of(Items.DIAMOND))+VirtualNetwork.get(l).ledger.get(b.asLong()).amount(ItemVariant.of(Items.DIAMOND))==0,"Both terminal totals must agree with drained authority");t.succeed();}));
            }));
        });
    }
    private static void observePair(GameTestHelper t,StorageTerminalBlockEntity a,StorageTerminalBlockEntity b,long expected,int ticks,Runnable next){
        a.getStacks();a.updateServer();b.getStacks();b.updateServer();long left=a.getStacks().values().stream().filter(s->s.getStack().is(Items.DIAMOND)).mapToLong(StoredItemStack::getQuantity).sum(),right=b.getStacks().values().stream().filter(s->s.getStack().is(Items.DIAMOND)).mapToLong(StoredItemStack::getQuantity).sum();
        t.assertTrue(left==expected && right==expected,"Both open terminals must show exactly "+expected+", left="+left+", right="+right);if(ticks==0)next.run();else t.runAfterDelay(1,()->observePair(t,a,b,expected,ticks-1,next));
    }
    @GameTest(maxTicks=1600)
    public void vanillaHoppersCanUseTheOriginalFabricConnectorItemInterface(GameTestHelper t){
        var l=t.getLevel();var p=t.absolutePos(new BlockPos(3,6,2));barrel(t,p.east());l.setBlock(p,Content.connector.get().defaultBlockState(),3);
        l.setBlock(p.above(),Blocks.HOPPER.defaultBlockState(),3);l.setBlock(p.below(),Blocks.HOPPER.defaultBlockState().setValue(net.minecraft.world.level.block.HopperBlock.FACING,Direction.EAST),3);
        l.setBlock(p.below().east(),Blocks.CHEST.defaultBlockState(),3);var input=(HopperBlockEntity)l.getBlockEntity(p.above());var output=(ChestBlockEntity)l.getBlockEntity(p.below().east());
        for(var external:new BlockPos[]{p.above(),p.below(),p.below().east()})BlockFilter.getOrCreateFilterAt(l,external).setSkip(true);
        await(t,()->((InventoryConnectorBlockEntity)l.getBlockEntity(p)).getMergedHandler().getSlotCount()==64,()->{input.setItem(0,new ItemStack(Items.DIAMOND,5));
        await(t,()->output.getItem(0).is(Items.DIAMOND) && output.getItem(0).getCount()==5,()->{
            t.assertTrue(input.isEmpty() && ((HopperBlockEntity)l.getBlockEntity(p.below())).isEmpty() && VirtualNetwork.get(l).ledger.get(p.east().asLong()).amount(ItemVariant.of(Items.DIAMOND))==0,"Native hopper transfers through the connector must conserve all five items");t.succeed();
        });});
    }
    @GameTest(maxTicks=1400)
    public void activeBarrelsRejectDirectCablesTerminalsAndBothHopperTypes(GameTestHelper t){
        var l=t.getLevel();var p=t.absolutePos(new BlockPos(4,7,4));barrel(t,p);l.setBlock(p.west(),Content.connector.get().defaultBlockState(),3);
        var c=(InventoryConnectorBlockEntity)l.getBlockEntity(p.west());
        l.setBlock(p.east(),wire(),3);l.setBlock(p.north(),Content.terminal.get().defaultBlockState().setValue(AbstractStorageTerminalBlock.FACING,Direction.SOUTH),3);
        l.setBlock(p.below(),Blocks.HOPPER.defaultBlockState(),3);
        l.setBlock(p.south(),Content.basicInvHopper.get().defaultBlockState().setValue(AbstractInventoryHopperBlock.FACING,Direction.SOUTH),3);
        l.setBlock(p.south(2),Blocks.CHEST.defaultBlockState(),3);((BasicInventoryHopperBlockEntity)l.getBlockEntity(p.south())).setFilter(new ItemStack(Items.DIAMOND));
        await(t,()->c.getMergedHandler().getSlotCount()==64 && l.getBlockState(p).getValue(TomBarrelBlock.ACTIVE),()->{
            t.assertTrue(c.getMergedHandler().pushStack(new ItemStack(Items.DIAMOND,9)).isEmpty(),"Restriction fixture must contain real stock");
            var direct=new NetworkInventory();direct.onLoad(l,p,Direction.UP,null);t.assertTrue(direct.getAccess(l,p).getSlotCount()==0,"Tom's NetworkInventory must never read a barrel directly");
            t.assertTrue(ItemStorage.SIDED.find(l,p,Direction.DOWN)==null,"No direct sided storage may be exposed");
            t.runAfterDelay(160,()->{
                t.assertTrue(!dev.tpc.PersistentCables.manager(l).query(p.east()).functions().contains(dev.tpc.PersistentCables.point(p)),"An adjacent cable must not attach to an active barrel");
                var terminal=(StorageTerminalBlockEntity)l.getBlockEntity(p.north());terminal.getStacks();t.assertTrue(terminal.getStacks().isEmpty(),"A terminal touching only a barrel must not reach its connector");
                t.assertTrue(((HopperBlockEntity)l.getBlockEntity(p.below())).isEmpty() && ((ChestBlockEntity)l.getBlockEntity(p.south(2))).isEmpty() && VirtualNetwork.get(l).ledger.get(p.asLong()).amount(ItemVariant.of(Items.DIAMOND))==9,"Neither hopper may move items directly from an activated barrel");t.succeed();
            });
        });
    }
    @GameTest(maxTicks=1400)
    public void cableConnectorContainerFaceAccessesVirtualCapacityAndRevokes(GameTestHelper t){
        var l=t.getLevel();var p=t.absolutePos(new BlockPos(3,6,2));barrel(t,p.east());
        l.setBlock(p,Content.invCableConnector.get().defaultBlockState().setValue(InventoryCableConnectorBlock.FACING,Direction.EAST),3);
        var c=(InventoryCableConnectorBlockEntity)l.getBlockEntity(p);
        await(t,()->c.getMergedHandler().getSlotCount()==64 && l.getBlockState(p.east()).getValue(TomBarrelBlock.ACTIVE),()->{
            t.assertTrue(c.getMergedHandler().pushStack(new ItemStack(Items.DIAMOND,13)).isEmpty(),"The cable connector's front must accept stock");
            Storage<ItemVariant> borrowed=c.getMergedHandler().getPlatformHandler();l.setBlock(p,Blocks.AIR.defaultBlockState(),3);
            try(var tx=Transaction.openOuter()){t.assertTrue(borrowed.extract(ItemVariant.of(Items.DIAMOND),1,tx)==0,"Removing the connector must revoke a borrowed front handler");tx.commit();}
            t.assertTrue(VirtualNetwork.get(l).ledger.get(p.east().asLong()).amount(ItemVariant.of(Items.DIAMOND))==13,"Denied extraction must preserve authority");t.succeed();
        });
    }
    @GameTest(maxTicks=2400)
    public void originalTomHopperTransfersBothWaysWithoutInventoryOnTheBarrel(GameTestHelper t){
        var l=t.getLevel();var hopper=t.absolutePos(new BlockPos(3,6,2));var bp=hopper.east(3);
        barrel(t,bp);l.setBlock(hopper.east(2),Content.connector.get().defaultBlockState(),3);l.setBlock(hopper.east(),wire(),3);
        l.setBlock(hopper.west(),Blocks.CHEST.defaultBlockState(),3);var chest=(ChestBlockEntity)l.getBlockEntity(hopper.west());chest.setItem(0,new ItemStack(Items.DIAMOND,17));
        l.setBlock(hopper,Content.basicInvHopper.get().defaultBlockState().setValue(AbstractInventoryHopperBlock.FACING,Direction.EAST),3);
        var network=VirtualNetwork.get(l);var variant=ItemVariant.of(Items.DIAMOND);
        await(t,()->chest.isEmpty() && network.ledger.get(bp.asLong()).amount(variant)==17,()->{
            l.setBlock(hopper,Blocks.AIR.defaultBlockState(),3);
            l.setBlock(hopper,Content.basicInvHopper.get().defaultBlockState().setValue(AbstractInventoryHopperBlock.FACING,Direction.WEST),3);
            ((BasicInventoryHopperBlockEntity)l.getBlockEntity(hopper)).setFilter(new ItemStack(Items.DIAMOND));
            await(t,()->chest.getItem(0).getCount()==17 && network.ledger.get(bp.asLong()).amount(variant)==0,()->{
                t.assertTrue(l.getBlockEntity(bp)==null,"Automation must still not introduce a barrel inventory/ticker");t.succeed();
            });
        });
    }
    @GameTest(maxTicks=1400)
    public void changingNativeKeepLastFilterRevokesBorrowedGateway(GameTestHelper t){
        var l=t.getLevel();var p=t.absolutePos(new BlockPos(3,6,2));barrel(t,p.east());l.setBlock(p,Content.connector.get().defaultBlockState(),3);
        var c=(InventoryConnectorBlockEntity)l.getBlockEntity(p);
        await(t,()->c.getMergedHandler().getSlotCount()==64,()->{
            t.assertTrue(c.getMergedHandler().pushStack(new ItemStack(Items.DIAMOND,9)).isEmpty(),"Filter fixture must be populated");Storage<ItemVariant> old=c.getMergedHandler().getPlatformHandler();
            var filter=BlockFilter.getOrCreateFilterAt(l,p);t.assertTrue(filter!=null,"The native connector must support native filter attachment");filter.setKeepLast(true);
            try(var tx=Transaction.openOuter()){t.assertTrue(old.extract(ItemVariant.of(Items.DIAMOND),1,tx)==0,"Changing a native filter must revoke a cached handler immediately");tx.commit();}
            await(t,()->c.getMergedHandler().getSlotCount()==64,()->{
                var taken=c.getMergedHandler().pullMatchingStack(new ItemStack(Items.DIAMOND),64);
                t.assertTrue(taken.getCount()==8 && VirtualNetwork.get(l).ledger.get(p.east().asLong()).amount(ItemVariant.of(Items.DIAMOND))==1,"Native keep-last must leave exactly one real item");t.succeed();
            });
        });
    }
    @GameTest(maxTicks=1400)
    public void nativePriorityOrdersTheVirtualGatewayAgainstRealContainers(GameTestHelper t){
        var l=t.getLevel();var p=t.absolutePos(new BlockPos(3,6,2));barrel(t,p.east());l.setBlock(p.west(),Blocks.CHEST.defaultBlockState(),3);l.setBlock(p,Content.connector.get().defaultBlockState(),3);
        var c=(InventoryConnectorBlockEntity)l.getBlockEntity(p);var chest=(ChestBlockEntity)l.getBlockEntity(p.west());var filter=BlockFilter.getOrCreateFilterAt(l,p);var chestFilter=BlockFilter.getOrCreateFilterAt(l,p.west());chestFilter.setPriority(Priority.LOW);filter.setPriority(Priority.HIGH);
        await(t,()->c.getMergedHandler().getSlotCount()==91,()->{
            var order=((MultiInventoryAccess)c.getMergedHandler()).getConnected().stream().map(IInventoryAccess::getSlotCount).toList();var remainder=c.getMergedHandler().pushStack(new ItemStack(Items.EMERALD,7));
            t.assertTrue(remainder.isEmpty() && chest.isEmpty(),"A higher-priority virtual gateway must receive inserts first; order="+order+", chest="+chest.getItem(0)+", remainder="+remainder+", ledger="+VirtualNetwork.get(l).ledger.get(p.east().asLong()).contents());
            filter.setPriority(Priority.LOW);
            chestFilter.setPriority(Priority.HIGHEST); // Tom composes connector and container priorities.
            await(t,()->c.getMergedHandler().getSlotCount()==91,()->{
                t.assertTrue(c.getMergedHandler().pushStack(new ItemStack(Items.GOLD_INGOT,5)).isEmpty() && chest.getItem(0).is(Items.GOLD_INGOT) && chest.getItem(0).getCount()==5,"Lowering gateway priority must let the native chest receive inserts first");t.succeed();
            });
        });
    }
}
