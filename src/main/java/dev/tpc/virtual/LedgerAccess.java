package dev.tpc.virtual;

import com.tom.storagemod.inventory.*;
import com.tom.storagemod.inventory.filter.ItemPredicate;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.*;
import net.fabricmc.fabric.api.transfer.v1.transaction.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import java.util.*;
import java.util.stream.Stream;

/** Tom's native aggregation/filter machinery sees one indexed inventory per accessible network. */
public final class LedgerAccess implements PlatformInventoryAccess {
    final LedgerStorage storage;
    private final Tracker tracker=new Tracker();
    public LedgerAccess(LedgerStorage storage){this.storage=storage;}
    public boolean valid(){return storage.valid();}
    @Override public Storage<ItemVariant> get(){return storage;}
    @Override public IInventoryChangeTracker tracker(){return tracker;}
    @Override public IInventoryAccess getRootHandler(Set<IProxy> seen){return this;}
    @Override public int getSlotCount(){return storage.valid()?(int)Math.min(Integer.MAX_VALUE,storage.scope.capacity()):0;}
    @Override public int getFreeSlotCount(){return storage.valid()?(int)Math.min(Integer.MAX_VALUE,storage.scope.free()):0;}
    @Override public ItemStack pushStack(ItemStack stack){
        if(stack.isEmpty())return stack;
        try(Transaction tx=Transaction.openOuter()){long n=storage.insert(ItemVariant.of(stack),stack.getCount(),tx);if(n==0)return stack;tx.commit();return n==stack.getCount()?ItemStack.EMPTY:stack.copyWithCount((int)(stack.getCount()-n));}
    }
    @Override public ItemStack pullMatchingStack(ItemStack stack,long maximum){
        if(stack.isEmpty() || maximum<=0)return ItemStack.EMPTY;
        try(Transaction tx=Transaction.openOuter()){long n=storage.extract(ItemVariant.of(stack),Math.min(stack.getMaxStackSize(),maximum),tx);if(n==0)return ItemStack.EMPTY;tx.commit();return stack.copyWithCount((int)n);}
    }
    private final class Tracker implements IInventoryChangeTracker,IChangeNotifier {
        @Override public long getChangeTracker(Level level){return storage.getVersion();}
        @Override public Stream<StoredItemStack> streamWrappedStacks(boolean parallel){
            return storage.snapshot().entrySet().stream().map(e->new StoredItemStack(e.getKey().toStack(),e.getValue(),e.getKey().hashCode()));
        }
        @Override public long countItems(StoredItemStack filter){return storage.snapshot().getOrDefault(ItemVariant.of(filter.getStack()),0L);}
        @Override public void onSlotChanged(InventorySlot slot){}
        private InventorySlot find(ItemPredicate filter,boolean empty,Set<StorageView<ItemVariant>> seen){
            if(!storage.valid())return null;
            for(var entry:storage.snapshot().entrySet()){
                var view=storage.view(entry.getKey());if(seen.contains(view))continue;
                if(filter.test(new StoredItemStack(entry.getKey().toStack(),entry.getValue(),entry.getKey().hashCode())))return new Slot(view,seen);
            }
            return empty && storage.scope.free()>0?new Slot(null,seen):null;
        }
        @Override public InventorySlot findSlot(ItemPredicate filter,boolean empty){return find(filter,empty,Collections.emptySet());}
        @Override public InventorySlot findSlotAfter(InventorySlot previous,ItemPredicate filter,boolean empty,boolean loop){
            if(previous==null)return findSlot(filter,empty);var next=find(filter,empty,previous.nextSlot());return next==null && loop?findSlot(filter,empty):next;
        }
        @Override public InventorySlot findSlotDest(StoredItemStack stack){
            if(!storage.valid())return null;
            try(Transaction tx=Transaction.openOuter()){return storage.insert(ItemVariant.of(stack.getStack()),Math.max(1,stack.getQuantity()),tx)>0?new Slot(null,Collections.emptySet()):null;}
        }
        @Override public InventorySlot findSlotDestAfter(InventorySlot previous,StoredItemStack stack,boolean loop){return previous==null || loop?findSlotDest(stack):null;}
        private final class Slot extends InventorySlot {
            private final StorageView<ItemVariant> view;
            Slot(StorageView<ItemVariant> view,Set<StorageView<ItemVariant>> seen){super(storage,view,Tracker.this,seen);this.view=view;}
            @Override public ItemStack getStack(){return view==null || view.isResourceBlank()?ItemStack.EMPTY:view.getResource().toStack((int)Math.min(view.getAmount(),view.getResource().toStack().getMaxStackSize()));}
            @Override public long insert(ItemVariant variant,long maximum,TransactionContext tx){return view==null || view.getResource().equals(variant)?storage.insert(variant,maximum,tx):0;}
            @Override public long extract(ItemVariant variant,long maximum,TransactionContext tx){return view==null?0:view.extract(variant,maximum,tx);}
        }
    }
}
