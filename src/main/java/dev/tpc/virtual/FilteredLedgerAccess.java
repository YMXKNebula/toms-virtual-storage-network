package dev.tpc.virtual;

import com.tom.storagemod.inventory.*;
import com.tom.storagemod.inventory.filter.ItemPredicate;
import com.tom.storagemod.util.Priority;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.*;
import net.fabricmc.fabric.api.transfer.v1.transaction.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import java.util.*;
import java.util.stream.*;

/** Keep Tom's filter predicates/priority, while discovering destinations through insertion probes.
 * A sparse ledger has no fake empty slots for the native physical-container tracker to enumerate. */
public final class FilteredLedgerAccess implements PlatformInventoryAccess,Priority.IPriority {
    private final LedgerAccess ledger;
    private final PlatformFilteredInventoryAccess filtered;
    private final Tracker tracker=new Tracker();
    public FilteredLedgerAccess(LedgerAccess ledger,BlockFilter filter,Level level){this.ledger=ledger;filtered=(PlatformFilteredInventoryAccess)filter.wrap(level,ledger);}
    @Override public Storage<ItemVariant> get(){return filtered;}
    @Override public IInventoryAccess getRootHandler(Set<IProxy> seen){return ledger.getRootHandler(seen);}
    @Override public Priority getPriority(){return filtered.getPriority();}
    @Override public int getSlotCount(){return ledger.getSlotCount();}
    @Override public int getFreeSlotCount(){return ledger.getFreeSlotCount();}
    @Override public IInventoryChangeTracker tracker(){return tracker;}
    @Override public ItemStack pushStack(ItemStack stack){if(stack.isEmpty())return stack;try(var tx=Transaction.openOuter()){long n=filtered.insert(ItemVariant.of(stack),stack.getCount(),tx);if(n==0)return stack;tx.commit();return n==stack.getCount()?ItemStack.EMPTY:stack.copyWithCount((int)(stack.getCount()-n));}}
    @Override public ItemStack pullMatchingStack(ItemStack stack,long maximum){if(stack.isEmpty() || maximum<=0)return ItemStack.EMPTY;try(var tx=Transaction.openOuter()){long n=filtered.extract(ItemVariant.of(stack),Math.min(maximum,stack.getMaxStackSize()),tx);if(n==0)return ItemStack.EMPTY;tx.commit();return stack.copyWithCount((int)n);}}
    private final class Tracker implements IInventoryChangeTracker,IChangeNotifier {
        @Override public long getChangeTracker(Level level){filtered.getFilter().getItemPred().updateState();return ledger.tracker().getChangeTracker(level);}
        @Override public Stream<StoredItemStack> streamWrappedStacks(boolean parallel){return StreamSupport.stream(filtered.spliterator(),false).filter(v->!v.isResourceBlank() && v.getAmount()>0).map(v->new StoredItemStack(v.getResource().toStack(),v.getAmount(),v.getResource().hashCode()));}
        @Override public long countItems(StoredItemStack stack){return streamWrappedStacks(false).filter(stack::equalItem).mapToLong(StoredItemStack::getQuantity).sum();}
        @Override public void onSlotChanged(InventorySlot slot){}
        private InventorySlot find(ItemPredicate predicate,boolean empty,Set<StorageView<ItemVariant>> seen){
            for(var view:filtered)if(!view.isResourceBlank() && !seen.contains(view.getUnderlyingView()) && predicate.test(new StoredItemStack(view.getResource().toStack(),view.getAmount(),view.getResource().hashCode())))return new Slot(view,seen);
            return empty && getFreeSlotCount()>0?new Slot(null,seen):null;
        }
        @Override public InventorySlot findSlot(ItemPredicate p,boolean empty){return find(p,empty,Collections.emptySet());}
        @Override public InventorySlot findSlotAfter(InventorySlot old,ItemPredicate p,boolean empty,boolean loop){var found=find(p,empty,old==null?Collections.emptySet():old.nextSlot());return found==null && loop?findSlot(p,empty):found;}
        @Override public InventorySlot findSlotDest(StoredItemStack stack){try(var tx=Transaction.openOuter()){return filtered.insert(ItemVariant.of(stack.getStack()),Math.max(1,stack.getQuantity()),tx)>0?new Slot(null,Collections.emptySet()):null;}}
        @Override public InventorySlot findSlotDestAfter(InventorySlot old,StoredItemStack stack,boolean loop){return old==null || loop?findSlotDest(stack):null;}
        final class Slot extends InventorySlot {
            final StorageView<ItemVariant> view;
            Slot(StorageView<ItemVariant> view,Set<StorageView<ItemVariant>> seen){super(filtered,view,Tracker.this,seen);this.view=view;}
            @Override public ItemStack getStack(){return view==null || view.isResourceBlank()?ItemStack.EMPTY:view.getResource().toStack((int)Math.min(view.getAmount(),view.getResource().toStack().getMaxStackSize()));}
            @Override public long insert(ItemVariant v,long n,TransactionContext tx){return view==null || view.getResource().equals(v)?filtered.insert(v,n,tx):0;}
            @Override public long extract(ItemVariant v,long n,TransactionContext tx){return view==null?0:view.extract(v,n,tx);}
        }
    }
}
