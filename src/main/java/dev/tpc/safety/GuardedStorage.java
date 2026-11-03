package dev.tpc.safety;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.*;
import net.fabricmc.fabric.api.transfer.v1.transaction.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import com.tom.storagemod.inventory.*;

/** No items are cached. Every operation delegates to the original Fabric transaction. */
public final class GuardedStorage implements Storage<ItemVariant> {
    private static final Map<Storage<ItemVariant>,Object> IDENTITIES=Collections.synchronizedMap(new WeakHashMap<>());
    private final Storage<ItemVariant> delegate;
    private final BooleanSupplier valid;
    private final Map<StorageView<ItemVariant>,GuardedView> views=new IdentityHashMap<>();
    public GuardedStorage(Storage<ItemVariant> delegate,BooleanSupplier valid){this.delegate=delegate;this.valid=valid;}
    /** Opaque deduplication key, never an unguarded storage handle. Trackers retain wrapper identity. */
    public Object identity(){return delegate instanceof GuardedStorage g?g.identity():IDENTITIES.computeIfAbsent(delegate,k->new Object());}
    /** Native build uses this only to identify proxy cycles, never to transfer items. */
    public Storage<ItemVariant> rootLookupStorage(){return NetworkSafety.building() && valid.getAsBoolean()?delegate:this;}
    /** Preserve NetworkInventory's native proxy route without exposing an unguarded handle.
     * Treating a connector proxy as a plain container creates a second item tracker whose
     * views change as the connector rebuilds, so old slot counts accumulate. */
    public IInventoryAccess proxyAccess(){
        if(!(delegate instanceof PlatformItemHandler proxy))return null;
        if(!valid.getAsBoolean())return PlatformInventoryAccess.EMPTY;
        Set<IProxy> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        return new GuardedAccess(proxy.getRootHandler(seen),valid);
    }
    private long transfer(ItemVariant resource,long amount,TransactionContext context,boolean insert) {
        StoragePreconditions.notNegative(amount);
        if(!valid.getAsBoolean())return 0;
        try(Transaction tx=Transaction.openNested(context)) {
            TransactionGuards.watch(tx,valid);
            long n=insert?delegate.insert(resource,amount,tx):delegate.extract(resource,amount,tx);
            if(!valid.getAsBoolean())return 0;tx.commit();return n;
        }
    }
    @Override public long insert(ItemVariant r,long n,TransactionContext tx){return transfer(r,n,tx,true);}
    @Override public long extract(ItemVariant r,long n,TransactionContext tx){return transfer(r,n,tx,false);}
    @Override public long getVersion(){return valid.getAsBoolean()?delegate.getVersion():Long.MIN_VALUE;}
    @Override public Iterator<StorageView<ItemVariant>> iterator(){
        if(!valid.getAsBoolean())return Collections.emptyIterator();
        Iterator<StorageView<ItemVariant>> original=delegate.iterator();
        return new Iterator<>(){
            public boolean hasNext(){return valid.getAsBoolean() && original.hasNext();}
            public StorageView<ItemVariant> next(){
                if(!valid.getAsBoolean())throw new NoSuchElementException();
                StorageView<ItemVariant> raw=original.next();StorageView<ItemVariant> key=raw.getUnderlyingView();
                GuardedView guarded=views.computeIfAbsent(key,k->new GuardedView(raw,valid));guarded.original=raw;return guarded;
            }
        };
    }
    public static StorageView<ItemVariant> view(StorageView<ItemVariant> original,BooleanSupplier valid){
        return new GuardedView(original,valid);
    }
    private static final class GuardedView implements StorageView<ItemVariant> {
            private StorageView<ItemVariant> original;
            private final BooleanSupplier valid;
            GuardedView(StorageView<ItemVariant> original,BooleanSupplier valid){this.original=original;this.valid=valid;}
            public boolean isResourceBlank(){return !valid.getAsBoolean() || original.isResourceBlank();}
            public ItemVariant getResource(){return valid.getAsBoolean()?original.getResource():ItemVariant.blank();}
            public long getAmount(){return valid.getAsBoolean()?original.getAmount():0;}
            public long getCapacity(){return valid.getAsBoolean()?original.getCapacity():0;}
            public long extract(ItemVariant r,long n,TransactionContext parent){
                StoragePreconditions.notNegative(n);if(!valid.getAsBoolean())return 0;
                try(Transaction tx=Transaction.openNested(parent)) {TransactionGuards.watch(tx,valid);long count=original.extract(r,n,tx);if(!valid.getAsBoolean())return 0;tx.commit();return count;}
            }
            // Do not expose an unguarded extraction handle through getUnderlyingView().
            public StorageView<ItemVariant> getUnderlyingView(){return this;}
        }
}
