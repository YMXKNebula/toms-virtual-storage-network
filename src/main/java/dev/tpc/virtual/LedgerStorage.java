package dev.tpc.virtual;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.*;
import net.fabricmc.fabric.api.transfer.v1.transaction.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One guarded network view, not one inventory copy per barrel or gateway. */
public final class LedgerStorage implements Storage<ItemVariant>,AutoCloseable {
    final VirtualNetwork network;
    final WarehouseLedger<ItemVariant>.Scope scope;
    private final BooleanSupplier permission;
    private final BooleanSupplier authorization=this::authorized;
    private final Map<ItemVariant,WeakReference<View>> views=new WeakHashMap<>();
    private Map<ItemVariant,Long> snapshot;
    private long version;
    LedgerStorage(VirtualNetwork network,WarehouseLedger<ItemVariant>.Scope scope,BooleanSupplier permission){this.network=network;this.scope=scope;this.permission=permission;snapshot=scope.snapshot();version=network.nextViewVersion();}
    public boolean valid(){return network.healthy() && scope.live() && permission.getAsBoolean();}
    private boolean authorized(){return valid() && network.acceptingTransactions();}
    void changed(){snapshot=scope.snapshot();version=network.nextViewVersion();}
    public Map<ItemVariant,Long> snapshot(){
        if(!valid())return Map.of();
        return Transaction.isOpen()?scope.snapshot():snapshot;
    }
    @Override public long getVersion(){return valid()?version:Long.MIN_VALUE;}
    @Override public long insert(ItemVariant variant,long maximum,TransactionContext transaction){return transfer(variant,maximum,transaction,true);}
    @Override public long extract(ItemVariant variant,long maximum,TransactionContext transaction){return transfer(variant,maximum,transaction,false);}
    private long transfer(ItemVariant variant,long maximum,TransactionContext parent,boolean insert){
        StoragePreconditions.notBlankNotNegative(variant,maximum);
        if(maximum==0 || !valid() || !network.acceptingTransactions())return 0;
        try(Transaction nested=Transaction.openNested(parent)){
            dev.tpc.safety.TransactionGuards.watch(nested,authorization);
            long result=insert?scope.insert(variant,maximum,n->network.touch(n,nested))
                    :scope.extract(variant,maximum,n->network.touch(n,nested));
            if(!valid())return 0;nested.commit();return result;
        }
    }
    View view(ItemVariant variant){var ref=views.get(variant);var result=ref==null?null:ref.get();if(result==null){result=new View(variant);views.put(variant,new WeakReference<>(result));}return result;}
    @Override public Iterator<StorageView<ItemVariant>> iterator(){
        if(!valid())return Collections.emptyIterator();var variants=new ArrayList<>(snapshot().keySet()).iterator();
        return new Iterator<>(){public boolean hasNext(){return valid() && variants.hasNext();}public StorageView<ItemVariant> next(){if(!valid())throw new NoSuchElementException();return view(variants.next());}};
    }
    @Override public void close(){scope.close();views.clear();snapshot=null;}
    final class View implements StorageView<ItemVariant>,Storage<ItemVariant>{
        final ItemVariant variant;
        View(ItemVariant variant){this.variant=variant;}
        @Override public boolean isResourceBlank(){return getAmount()==0;}
        @Override public ItemVariant getResource(){return getAmount()==0?ItemVariant.blank():variant;}
        @Override public long getAmount(){return valid()?scope.amount(variant):0;}
        @Override public long getCapacity(){return valid()?Math.max(getAmount(),scope.capacity()*variant.toStack().getMaxStackSize()):0;}
        @Override public long extract(ItemVariant resource,long maximum,TransactionContext tx){StoragePreconditions.notBlankNotNegative(resource,maximum);return resource.equals(variant)?LedgerStorage.this.extract(resource,maximum,tx):0;}
        @Override public long insert(ItemVariant resource,long maximum,TransactionContext tx){StoragePreconditions.notBlankNotNegative(resource,maximum);return resource.equals(variant)?LedgerStorage.this.insert(resource,maximum,tx):0;}
        @Override public Iterator<StorageView<ItemVariant>> iterator(){return Collections.<StorageView<ItemVariant>>singleton(this).iterator();}
        @Override public StorageView<ItemVariant> getUnderlyingView(){return this;}
    }
}
