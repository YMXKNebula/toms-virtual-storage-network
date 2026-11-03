package dev.tpc.integration;

import com.tom.storagemod.Content;
import com.tom.storagemod.block.AbstractStorageTerminalBlock;
import com.tom.storagemod.block.InventoryCableBlock;
import com.tom.storagemod.block.entity.StorageTerminalBlockEntity;
import com.tom.storagemod.inventory.*;
import dev.tpc.safety.GuardedStorage;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.transfer.v1.item.*;
import net.fabricmc.fabric.api.transfer.v1.storage.*;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import java.util.function.BooleanSupplier;

/** Real vanilla chest pairs, including borrowed handles that outlive their other half. */
public final class DoubleChestGameTests {
    private record Pair(BlockPos left,BlockPos right,ChestBlockEntity a,ChestBlockEntity b) {}

    private static Pair pair(GameTestHelper test,BlockPos left,Direction facing,boolean trapped) {
        var level=test.getLevel();var block=trapped?Blocks.TRAPPED_CHEST:Blocks.CHEST;
        var ls=block.defaultBlockState().setValue(ChestBlock.FACING,facing).setValue(ChestBlock.TYPE,ChestType.LEFT);
        BlockPos right=left.relative(ChestBlock.getConnectedDirection(ls));
        level.setBlock(left,ls,2);
        level.setBlock(right,ls.setValue(ChestBlock.TYPE,ChestType.RIGHT),2);
        var a=(ChestBlockEntity)level.getBlockEntity(left);var b=(ChestBlockEntity)level.getBlockEntity(right);
        a.setItem(0,new ItemStack(Items.DIAMOND,12));b.setItem(0,new ItemStack(Items.DIAMOND,7));
        test.assertTrue(ChestBlock.getContainer((ChestBlock)block,ls,level,left,true).getContainerSize()==54,"Fixture must be a real double chest");
        return new Pair(left,right,a,b);
    }

    private static PlatformInventoryAccess.BlockInventoryAccess access(GameTestHelper test,BlockPos p) {
        var a=new PlatformInventoryAccess.BlockInventoryAccess();a.onLoad(test.getLevel(),p,Direction.DOWN,null);return a;
    }

    private static long count(Storage<ItemVariant> storage) {
        long n=0;for(var v:storage)if(v.getResource().isOf(Items.DIAMOND))n+=v.getAmount();return n;
    }

    private static int physical(Pair pair) {return pair.a.getItem(0).getCount()+pair.b.getItem(0).getCount();}

    @GameTest(maxTicks=300)
    public void bothHalvesAndAllFacingsKeepStableInventoryViews(GameTestHelper test) {
        Direction[] facings={Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST};
        for(int i=0;i<facings.length;i++)for(int type=0;type<2;type++) {
            var p=pair(test,test.absolutePos(new BlockPos(2+type*3,3+i*3,3)),facings[i],type==1);
            var a=access(test,p.left);var b=access(test,p.right);
            var raw1=ItemStorage.SIDED.find(test.getLevel(),p.left,Direction.DOWN);
            var raw2=ItemStorage.SIDED.find(test.getLevel(),p.left,Direction.DOWN);
            test.assertTrue(raw1!=raw2,"Fixture must exercise fresh Fabric double-chest wrappers");
            test.assertTrue(a.getSlotCount()==54 && b.getSlotCount()==54,"Both halves of a double chest must expose 54 slots; actual="+a.getSlotCount()+"/"+b.getSlotCount());
            var storage=a.get();var first=storage.iterator().next().getUnderlyingView();
            test.assertTrue(((GuardedStorage)storage).identity()==((GuardedStorage)b.get()).identity(),"The two halves must share one physical inventory identity");
            test.assertTrue(count(storage)==19,"Both halves must contribute their real items exactly once");
            try(Transaction tx=Transaction.openOuter()) {
                test.assertTrue(storage.extract(ItemVariant.of(Items.DIAMOND),15,tx)==15,"Extraction must cross the half boundary");tx.commit();
            }
            test.assertTrue(physical(p)==4,"Extraction must conserve the real contents");
            try(Transaction tx=Transaction.openOuter()) {test.assertTrue(storage.extract(ItemVariant.of(Items.DIAMOND),4,tx)==4,"Rollback fixture must extract the remaining items");}
            test.assertTrue(physical(p)==4,"A rolled-back extraction must restore both halves");
            test.assertTrue(a.pushStack(new ItemStack(Items.DIAMOND,7)).isEmpty() && physical(p)==11,"Insertion must return items through the native transaction");
            test.runAfterDelay(60,()->{
                test.assertTrue(a.get()==storage,"Changing only the outer Fabric wrapper must retain the guarded storage");
                test.assertTrue(a.get().iterator().next().getUnderlyingView()==first,"Slot identity must survive repeated native lookups");
                a.tracker().getChangeTracker(test.getLevel());
                long displayed=a.tracker().streamWrappedStacks(false).mapToLong(StoredItemStack::getQuantity).sum();
                test.assertTrue(displayed==11 && count(a.get())==11 && physical(p)==11,"Repeated lookups must not inflate counts");
            });
        }
        test.runAfterDelay(61,test::succeed);
    }

    @GameTest(maxTicks=900)
    public void directTerminalCountsBothHalvesAndDrainsToZero(GameTestHelper test) {terminal(test,false);}
    @GameTest(maxTicks=900)
    public void wiredTerminalCountsBothHalvesAndDrainsToZero(GameTestHelper test) {terminal(test,true);}

    private static void terminal(GameTestHelper test,boolean wired) {
        var level=test.getLevel();BlockPos start=test.absolutePos(new BlockPos(1,4,2));
        level.setBlock(start,Content.terminal.get().defaultBlockState().setValue(AbstractStorageTerminalBlock.FACING,Direction.EAST),3);
        int offset=wired?2:1;
        if(wired)level.setBlock(start.east(),Content.invCable.get().defaultBlockState().setValue(InventoryCableBlock.EAST,true).setValue(InventoryCableBlock.WEST,true),3);
        level.setBlock(start.east(offset),Content.connector.get().defaultBlockState(),3);
        var p=pair(test,start.east(offset+1),Direction.NORTH,false);
        var t=(StorageTerminalBlockEntity)level.getBlockEntity(start);
        await(test,()->{t.getStacks();t.updateServer();return terminalCount(t)==19;},()->observe(test,t,p,19,200,()->{
            var taken=t.pullStack(new StoredItemStack(new ItemStack(Items.DIAMOND)),64);
            test.assertTrue(taken!=null && taken.getQuantity()==19 && physical(p)==0,"The terminal must extract exactly both real halves");
            test.assertTrue(t.pullStack(new StoredItemStack(new ItemStack(Items.DIAMOND)),1)==null,"No phantom items may remain extractable");
            await(test,()->{t.updateServer();return terminalCount(t)==0;},()->observe(test,t,p,0,40,()->{
                test.assertTrue(t.pushStack(new ItemStack(Items.DIAMOND,9)).isEmpty(),"The terminal must refill the double chest");
                await(test,()->{t.updateServer();return terminalCount(t)==9;},()->observe(test,t,p,9,60,test::succeed));
            }));
        }));
    }

    private static long terminalCount(StorageTerminalBlockEntity t) {return t.getStacks().values().stream().filter(s->s.getStack().is(Items.DIAMOND)).mapToLong(StoredItemStack::getQuantity).sum();}
    private static void observe(GameTestHelper test,StorageTerminalBlockEntity t,Pair p,long expected,int remaining,Runnable next) {
        t.getStacks();t.updateServer();
        test.assertTrue(terminalCount(t)==expected && physical(p)==expected,"Double-chest display and physical contents must stay equal: display="+terminalCount(t)+", physical="+physical(p));
        if(remaining==0)next.run();else test.runAfterDelay(1,()->observe(test,t,p,expected,remaining-1,next));
    }

    @GameTest(maxTicks=300)
    public void splittingAndReplacingOtherHalfRevokeBorrowedHandles(GameTestHelper test) {
        var level=test.getLevel();var p=pair(test,test.absolutePos(new BlockPos(2,3,2)),Direction.NORTH,false);
        var a=access(test,p.left);Storage<ItemVariant> old=a.get();
        test.assertTrue(count(old)==19,"Must borrow a live double chest before mutation");
        StorageView<ItemVariant> other=null;for(var v:old)if(v.getAmount()==7)other=v;
        test.assertTrue(other!=null,"Must borrow a view of the other physical half");
        var borrowed=other;var slot=a.tracker().findSlot(s->s.getStack().is(Items.DIAMOND),false);
        var rightState=level.getBlockState(p.right);
        level.setBlock(p.right,rightState.setValue(ChestBlock.TYPE,ChestType.SINGLE),2);
        assertDenied(test,old,borrowed);
        test.assertTrue(slot!=null && slot.extract(1).isEmpty(),"Previously borrowed native slots must also stop after a split");
        level.setBlock(p.right,rightState,2);
        assertDenied(test,old,borrowed);
        test.runAfterDelay(1,()->{
            var fresh=a.get();test.assertTrue(count(fresh)==19,"A fresh lookup must recover after rejoining");
            var freshView=fresh.iterator().next();
            level.removeBlockEntity(p.right);
            var replacement=new ChestBlockEntity(p.right,rightState);level.setBlockEntity(replacement);replacement.setItem(0,new ItemStack(Items.DIAMOND,3));
            assertDenied(test,fresh,freshView);
            test.assertTrue(p.a.getItem(0).getCount()==12 && replacement.getItem(0).getCount()==3,"Denied stale access must leave both real entities untouched");
            test.runAfterDelay(1,()->{test.assertTrue(count(a.get())==15,"Fresh storage must bind to the replacement half");test.succeed();});
        });
    }

    private static void assertDenied(GameTestHelper test,Storage<ItemVariant> storage,StorageView<ItemVariant> view) {
        try(Transaction tx=Transaction.openOuter()) {
            test.assertTrue(storage.extract(ItemVariant.of(Items.DIAMOND),1,tx)==0 && view.extract(ItemVariant.of(Items.DIAMOND),1,tx)==0,"A stale double-chest handle and view must both reject extraction");tx.commit();
        }
    }

    @GameTest(maxTicks=300)
    public void restoringSourceStateDoesNotReviveRetiredStorage(GameTestHelper test) {
        var level=test.getLevel();var p=pair(test,test.absolutePos(new BlockPos(2,3,2)),Direction.NORTH,false);
        var a=access(test,p.left);var old=a.get();var view=old.iterator().next();
        test.assertTrue(count(old)==19,"Must borrow the live combined inventory");
        var state=level.getBlockState(p.left);
        level.setBlock(p.left,state.setValue(ChestBlock.TYPE,ChestType.SINGLE),2);
        assertDenied(test,old,view);
        level.setBlock(p.left,state,2);
        assertDenied(test,old,view);
        test.assertTrue(physical(p)==19,"Restoring block state must not let a retired reference change contents");test.succeed();
    }

    @GameTest(maxTicks=300)
    public void singleDoubleSingleTransitionsRefreshTheInventory(GameTestHelper test) {
        var level=test.getLevel();var p=pair(test,test.absolutePos(new BlockPos(2,4,2)),Direction.NORTH,false);
        var a=access(test,p.left);var both=a.get();var borrowed=both.iterator().next();
        var leftState=level.getBlockState(p.left);var rightState=level.getBlockState(p.right);
        level.setBlock(p.left,leftState.setValue(ChestBlock.TYPE,ChestType.SINGLE),2);
        level.setBlock(p.right,rightState.setValue(ChestBlock.TYPE,ChestType.SINGLE),2);
        assertDenied(test,both,borrowed);
        test.runAfterDelay(1,()->{
            test.assertTrue(a.getSlotCount()==27 && count(a.get())==12,"A split chest must expose only its own 27 slots and real contents");
            var single=a.get();var singleView=single.iterator().next();
            level.setBlock(p.left,leftState,2);level.setBlock(p.right,rightState,2);
            assertDenied(test,single,singleView);assertDenied(test,both,borrowed);
            test.runAfterDelay(1,()->{
                test.assertTrue(a.getSlotCount()==54 && count(a.get())==19,"Rejoining must restore the exact combined inventory");
                test.assertTrue(physical(p)==19,"Splitting and rejoining must not move or duplicate items");test.succeed();
            });
        });
    }

    @GameTest(maxTicks=3000)
    public void unloadedOtherHalfIsNotLoadedByInventoryLookup(GameTestHelper test) {
        var level=test.getLevel();BlockPos left=new BlockPos(3_000_015,70,3_000_000);
        int x=left.getX()>>4,z=left.getZ()>>4;
        // A forced chunk also keeps its immediate neighbors loaded. Keep the source
        // on the outer FULL border, so its other half can genuinely unload.
        level.setChunkForced(x-2,z,true);level.setChunkForced(x+1,z,true);
        var p=pair(test,left,Direction.NORTH,false);var a=access(test,left);var old=a.get();
        test.assertTrue(count(old)==19,"Cross-chunk chest must initially expose both loaded halves");
        level.setChunkForced(x+1,z,false);
        await(test,()->level.getChunkSource().getChunkNow(x+1,z)==null,()->{
            test.assertTrue(!old.iterator().hasNext() && a.get()==null,"Unloading the other half must revoke old and new access");
            test.assertTrue(!PlatformInventoryAccess.BlockInventoryAccess.hasInventoryAt(level,left,level.getBlockState(left),Direction.DOWN),"Connector discovery must also reject the incomplete double chest");
            test.assertTrue(level.getChunkSource().getChunkNow(x+1,z)==null,"An inventory query must not load the other half's chunk");
            level.setChunkForced(x+1,z,true);
            await(test,()->{var c=level.getChunkSource().getChunkNow(x+1,z);return c!=null && c.getBlockEntity(p.right) instanceof ChestBlockEntity;},()->{
                test.assertTrue(count(a.get())==19,"Reloading the other half must restore fresh access and exact contents");
                test.assertTrue(!old.iterator().hasNext(),"A retired handle must not revive after reloading");
                level.setChunkForced(x-2,z,false);level.setChunkForced(x+1,z,false);test.succeed();
            });
        });
    }

    private static void await(GameTestHelper test,BooleanSupplier condition,Runnable next) {
        if(condition.getAsBoolean())next.run();else test.runAfterDelay(1,()->await(test,condition,next));
    }
}
