package dev.tpc.safety;

import com.tom.storagemod.inventory.*;
import com.tom.storagemod.inventory.filter.ItemPredicate;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.*;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

public final class GuardedAccess implements IInventoryAccess {
    private final IInventoryAccess original;
    private final BooleanSupplier valid;
    private final IInventoryChangeTracker tracker=new Tracker();
    public GuardedAccess(IInventoryAccess original,BooleanSupplier valid){this.original=original;this.valid=valid;}
    @Override public Object get(){if(!valid.getAsBoolean())return Storage.empty();Storage<ItemVariant> storage=original.getPlatformHandler();return new GuardedStorage(storage,valid);}
    @Override public IInventoryAccess getRootHandler(Set<IProxy> seen){return NetworkSafety.building() && valid.getAsBoolean()?original.getRootHandler(seen):this;}
    @Override public int getFreeSlotCount(){return valid.getAsBoolean()?original.getFreeSlotCount():0;}
    @Override public int getSlotCount(){return valid.getAsBoolean()?original.getSlotCount():0;}
    @Override public IInventoryChangeTracker tracker(){return tracker;}
    @Override public ItemStack pullMatchingStack(ItemStack stack,long maximum){
        if(stack.isEmpty() || maximum<=0 || !valid.getAsBoolean())return ItemStack.EMPTY;
        return IInventoryAccess.super.pullMatchingStack(stack,Math.min(maximum,stack.getMaxStackSize()));
    }
    private InventorySlot wrap(InventorySlot slot){return slot==null?null:new Slot(slot);}
    private InventorySlot unwrap(InventorySlot slot){return slot instanceof Slot s?s.slot:slot;}
    private final class Tracker implements IInventoryChangeTracker {
        public long getChangeTracker(Level level){return valid.getAsBoolean()?original.tracker().getChangeTracker(level):Long.MIN_VALUE;}
        public Stream<StoredItemStack> streamWrappedStacks(boolean parallel){return valid.getAsBoolean()?original.tracker().streamWrappedStacks(parallel):Stream.empty();}
        public long countItems(StoredItemStack filter){return valid.getAsBoolean()?original.tracker().countItems(filter):0;}
        public InventorySlot findSlot(ItemPredicate filter,boolean empty){return valid.getAsBoolean()?wrap(original.tracker().findSlot(filter,empty)):null;}
        public InventorySlot findSlotAfter(InventorySlot previous,ItemPredicate filter,boolean empty,boolean loop){return valid.getAsBoolean()?wrap(original.tracker().findSlotAfter(unwrap(previous),filter,empty,loop)):null;}
        public InventorySlot findSlotDest(StoredItemStack stack){return valid.getAsBoolean()?wrap(original.tracker().findSlotDest(stack)):null;}
        public InventorySlot findSlotDestAfter(InventorySlot previous,StoredItemStack stack,boolean loop){return valid.getAsBoolean()?wrap(original.tracker().findSlotDestAfter(unwrap(previous),stack,loop)):null;}
    }
    private final class Slot extends InventorySlot {
        final InventorySlot slot;
        Slot(InventorySlot slot){super(null,null,ignored->{if(GuardedAccess.this.valid.getAsBoolean() && GuardedAccess.this.original.tracker() instanceof IChangeNotifier notifier)notifier.onSlotChanged(slot);},Collections.emptySet());this.slot=slot;}
        @Override public ItemStack getStack(){return valid.getAsBoolean()?slot.getStack():ItemStack.EMPTY;}
        @Override public ItemStack insert(ItemStack stack){
            if(!valid.getAsBoolean())return stack;
            try(Transaction tx=Transaction.openOuter()) {long n=insert(ItemVariant.of(stack),stack.getCount(),tx);if(n==0 || !valid.getAsBoolean())return stack;tx.commit();notifyOriginal();return n==stack.getCount()?ItemStack.EMPTY:stack.copyWithCount((int)(stack.getCount()-n));}
        }
        @Override public boolean transferTo(int amount,InventorySlot to){
            return valid.getAsBoolean() && super.transferTo(amount,to);
        }
        @Override public ItemStack extract(int amount){
            var stack=getStack();if(stack.isEmpty() || amount<=0)return ItemStack.EMPTY;
            try(Transaction tx=Transaction.openOuter()){long n=extract(ItemVariant.of(stack),Math.min(amount,stack.getMaxStackSize()),tx);if(n==0 || !valid.getAsBoolean())return ItemStack.EMPTY;tx.commit();notifyOriginal();return stack.copyWithCount((int)n);}
        }
        private void notifyOriginal(){if(original.tracker() instanceof IChangeNotifier notifier)notifier.onSlotChanged(slot);}
        boolean ownerValid(){return valid.getAsBoolean();}
        @Override public long insert(ItemVariant r,long n,TransactionContext tx){return new GuardedStorage(slot,valid).insert(r,n,tx);}
        @Override public long extract(ItemVariant r,long n,TransactionContext tx){return new GuardedStorage(slot,valid).extract(r,n,tx);}
        @Override public Iterator<StorageView<ItemVariant>> iterator(){return new GuardedStorage(slot,valid).iterator();}
        @Override public StorageView<ItemVariant> getView(){return slot.getView()==null?null:GuardedStorage.view(slot.getView(),valid);}
        @Override public Set<StorageView<ItemVariant>> getSeen(){return Collections.emptySet();}
        @Override public Set<StorageView<ItemVariant>> nextSlot(){return Collections.emptySet();}
    }
}
