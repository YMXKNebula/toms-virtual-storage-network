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
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Real blocks, original terminals and Fabric transactions; no simulated inventory substitution. */
public final class VirtualGameTests {
    private static void await(GameTestHelper test,BooleanSupplier ready,Runnable next){if(ready.getAsBoolean())next.run();else test.runAfterDelay(1,()->await(test,ready,next));}
    private static void barrel(GameTestHelper test,BlockPos pos){VirtualFixtureReset.clear(VirtualNetwork.get(test.getLevel()),pos);test.getLevel().setBlock(pos,VirtualContent.BARREL.defaultBlockState(),3);}
    private static net.minecraft.world.level.block.state.BlockState wire(){return Content.invCable.get().defaultBlockState().setValue(InventoryCableBlock.EAST,true).setValue(InventoryCableBlock.WEST,true);}
    private static long count(StorageTerminalBlockEntity terminal){terminal.getStacks();terminal.updateServer();return terminal.getStacks().values().stream().filter(s->s.getStack().is(Items.DIAMOND)).mapToLong(StoredItemStack::getQuantity).sum();}

    @GameTest(maxTicks=1200)
    public void nativeGatewayHas64KindsAndNestedTransactionsRollback(GameTestHelper test){
        var level=test.getLevel();BlockPos p=test.absolutePos(new BlockPos(2,5,2));barrel(test,p);
        level.setBlock(p.west(),Content.connector.get().defaultBlockState(),3);
        test.assertTrue(level.getBlockEntity(p)==null,"Virtual barrels must have no block entity");
        test.assertTrue(ItemStorage.SIDED.find(level,p,Direction.DOWN)==null,"A barrel must not expose ordinary sided inventory slots");
        var connector=(InventoryConnectorBlockEntity)level.getBlockEntity(p.west());var network=VirtualNetwork.get(level);
        await(test,()->connector.getMergedHandler().getSlotCount()==64,()->{
            Storage<ItemVariant> storage=connector.getMergedHandler().getPlatformHandler();long before=network.version();
            try(Transaction outer=Transaction.openOuter()){
                test.assertTrue(storage.insert(ItemVariant.of(Items.DIAMOND),12,outer)==12,"Native gateway must accept real transaction insertion");
                try(Transaction inner=outer.openNested()){test.assertTrue(storage.extract(ItemVariant.of(Items.DIAMOND),5,inner)==5,"Nested extraction must see provisional contents");}
                test.assertTrue(network.ledger.get(p.asLong()).amount(ItemVariant.of(Items.DIAMOND))==12,"Nested rollback must restore its exact amount");
            }
            test.assertTrue(network.ledger.get(p.asLong()).contents().isEmpty() && network.version()==before,"Outer rollback must not publish inventory or a committed version");
            for(int i=0;i<64;i++){
                var item=new ItemStack(Items.PAPER);item.set(DataComponents.CUSTOM_NAME,Component.literal("TVSN variant "+i));
                try(Transaction tx=Transaction.openOuter()){test.assertTrue(storage.insert(ItemVariant.of(item),1,tx)==1,"Each full component variant must claim one sparse position");tx.commit();}
            }
            var extra=new ItemStack(Items.PAPER);extra.set(DataComponents.CUSTOM_NAME,Component.literal("TVSN variant 64"));
            try(Transaction tx=Transaction.openOuter()){test.assertTrue(storage.insert(ItemVariant.of(extra),1,tx)==0,"A 65th distinct variant must be rejected by a single barrel");tx.commit();}
            test.assertTrue(network.ledger.get(p.asLong()).contents().size()==64,"There must be precisely 64 actual variant records");test.succeed();
        });
    }
    @GameTest(maxTicks=1200)
    public void directTerminalVirtualCountsDoNotGrow(GameTestHelper test){terminal(test,false,false);}
    @GameTest(maxTicks=1200)
    public void wiredTerminalVirtualCountsDoNotGrow(GameTestHelper test){terminal(test,true,false);}
    @GameTest(maxTicks=1200)
    public void nativeCraftingTerminalUsesTheSameVirtualInventory(GameTestHelper test){terminal(test,false,true);}
    private static void terminal(GameTestHelper test,boolean wired,boolean crafting){
        var level=test.getLevel();BlockPos p=test.absolutePos(new BlockPos(1,5,2));
        level.setBlock(p,(crafting?Content.craftingTerminal.get():Content.terminal.get()).defaultBlockState().setValue(AbstractStorageTerminalBlock.FACING,Direction.EAST),3);
        int at=wired?2:1;if(wired)level.setBlock(p.east(),wire(),3);
        level.setBlock(p.east(at),Content.connector.get().defaultBlockState(),3);barrel(test,p.east(at+1));barrel(test,p.east(at+2));
        var terminal=(StorageTerminalBlockEntity)level.getBlockEntity(p);var network=VirtualNetwork.get(level);
        await(test,()->{var connector=(InventoryConnectorBlockEntity)level.getBlockEntity(p.east(at));return connector.getMergedHandler().getSlotCount()==128 && ((dev.tpc.integration.mixin.TerminalTestAccess)terminal).tvsn$itemCache().getAccess(level,p).getSlotCount()==128;},()->{
            test.assertTrue(terminal.pushStack(new ItemStack(Items.DIAMOND,19)).isEmpty(),"The original terminal must insert into the ledger");
            await(test,()->count(terminal)==19,()->observe(test,terminal,19,160,()->{
                var result=terminal.pullStack(new StoredItemStack(new ItemStack(Items.DIAMOND)),64);
                test.assertTrue(result!=null && result.getQuantity()==19,"The original terminal must extract exactly the real virtual contents");
                await(test,()->count(terminal)==0,()->{
                    test.assertTrue(terminal.pullStack(new StoredItemStack(new ItemStack(Items.DIAMOND)),1)==null,"No phantom amount may be extracted");
                    test.assertTrue(terminal.pushStack(new ItemStack(Items.DIAMOND,7)).isEmpty(),"Refilling must still use the same capacity");
                    await(test,()->count(terminal)==7,()->observe(test,terminal,7,60,()->{test.assertTrue(network.ledger.nodes().stream().filter(n->n.position==p.east(at+1).asLong() || n.position==p.east(at+2).asLong()).mapToLong(n->n.amount(ItemVariant.of(Items.DIAMOND))).sum()==7,"Ledger is the only authority");test.succeed();}));
                });
            }));
        });
    }
    private static void observe(GameTestHelper test,StorageTerminalBlockEntity terminal,long expected,int ticks,Runnable next){
        test.assertTrue(count(terminal)==expected,"Virtual terminal count must remain exact: expected="+expected+", actual="+count(terminal));
        if(ticks==0)next.run();else test.runAfterDelay(1,()->observe(test,terminal,expected,ticks-1,next));
    }
    @GameTest(maxTicks=1200)
    public void disconnectBeforeOuterCommitRollsBackTheEntireTransfer(GameTestHelper test){
        var level=test.getLevel();BlockPos connector=test.absolutePos(new BlockPos(2,7,2)),cable=connector.east(),barrel=cable.east(2),chest=barrel.east(2);
        level.setBlock(connector,Content.connector.get().defaultBlockState(),3);level.setBlock(cable,wire(),3);level.setBlock(cable.east(),Content.connector.get().defaultBlockState(),3);barrel(test,barrel);level.setBlock(chest,Blocks.CHEST.defaultBlockState(),3);
        var be=(InventoryConnectorBlockEntity)level.getBlockEntity(connector);var network=VirtualNetwork.get(level);var variant=ItemVariant.of(Items.DIAMOND);
        await(test,()->be.getMergedHandler().getSlotCount()==64,()->{
            test.assertTrue(be.getMergedHandler().pushStack(new ItemStack(Items.DIAMOND,12)).isEmpty(),"Commit-guard fixture must store its real items");
            Storage<ItemVariant> source=be.getMergedHandler().getPlatformHandler(),destination=ItemStorage.SIDED.find(level,chest,Direction.DOWN);
            boolean denied=false;
            try(Transaction tx=Transaction.openOuter()){
                test.assertTrue(source.extract(variant,5,tx)==5 && destination.insert(variant,5,tx)==5,"Both participants must have provisional changes");
                level.setBlock(cable,Blocks.AIR.defaultBlockState(),3);
                try{tx.commit();}catch(dev.tpc.safety.TransactionGuards.Revoked expected){denied=true;}
            }
            test.assertTrue(denied,"Revoked permissions must reject the outer commit before any participant commits");
            test.assertTrue(network.ledger.get(barrel.asLong()).amount(variant)==12 && ((net.minecraft.world.level.block.entity.ChestBlockEntity)level.getBlockEntity(chest)).isEmpty(),"The ledger and the real destination must both roll back");test.succeed();
        });
    }
    @GameTest(maxTicks=1200)
    public void activationRequiresAConnectorAndRevokesAfterDisconnect(GameTestHelper test){
        var level=test.getLevel();BlockPos a=test.absolutePos(new BlockPos(4,5,2)),b=a.east();barrel(test,a);barrel(test,b);
        test.runAfterDelay(60,()->{
            test.assertTrue(!level.getBlockState(a).getValue(TomBarrelBlock.ACTIVE) && !level.getBlockState(b).getValue(TomBarrelBlock.ACTIVE),"An isolated warehouse must remain dark");
            level.setBlock(a.west(),Content.connector.get().defaultBlockState(),3);
            await(test,()->level.getBlockState(a).getValue(TomBarrelBlock.ACTIVE) && level.getBlockState(b).getValue(TomBarrelBlock.ACTIVE),()->{
                level.setBlock(a.west(),Blocks.AIR.defaultBlockState(),3);
                await(test,()->!level.getBlockState(a).getValue(TomBarrelBlock.ACTIVE) && !level.getBlockState(b).getValue(TomBarrelBlock.ACTIVE),()->{
                    level.setBlock(a.west(),Content.connector.get().defaultBlockState(),3);await(test,()->level.getBlockState(a).getValue(TomBarrelBlock.ACTIVE) && level.getBlockState(b).getValue(TomBarrelBlock.ACTIVE),test::succeed);
                });
            });
        });
    }
    @GameTest(maxTicks=1200)
    public void removingCapacityRedistributesOnlyItsOwnAllocations(GameTestHelper test){
        var level=test.getLevel();BlockPos a=test.absolutePos(new BlockPos(2,6,2)),b=a.east();barrel(test,a);barrel(test,b);
        level.setBlock(a.west(),Content.connector.get().defaultBlockState(),3);var network=VirtualNetwork.get(level);
        await(test,()->{var g=network.gateway(a);return g!=null && g.getSlotCount()==128;},()->{
            var access=network.gateway(a);test.assertTrue(access.pushStack(new ItemStack(Items.DIAMOND,19)).isEmpty(),"Removal fixture must own 19 items");
            long chosen=network.ledger.get(a.asLong()).amount(ItemVariant.of(Items.DIAMOND))>0?a.asLong():b.asLong();BlockPos removed=BlockPos.of(chosen),survivor=removed.equals(a)?b:a;
            var old=access.get();level.setBlock(removed,Blocks.AIR.defaultBlockState(),3);
            try(Transaction tx=Transaction.openOuter()){test.assertTrue(old.extract(ItemVariant.of(Items.DIAMOND),1,tx)==0,"Removing capacity must revoke an old network handle immediately");tx.commit();}
            test.assertTrue(network.ledger.get(removed.asLong())==null && network.ledger.get(survivor.asLong()).amount(ItemVariant.of(Items.DIAMOND))==19,"Only the removed barrel's items must move into surviving legal capacity");
            test.assertTrue(network.overflowJobs()==0,"Available capacity must avoid unnecessary drops");test.succeed();
        });
    }
}
