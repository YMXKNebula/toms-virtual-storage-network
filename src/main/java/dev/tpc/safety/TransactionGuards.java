package dev.tpc.safety;

import net.fabricmc.fabric.api.transfer.v1.transaction.*;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Permissions are checked before Fabric commits the whole joint transaction, not after one participant. */
public final class TransactionGuards {
    public static final class Revoked extends IllegalStateException {public Revoked(){super("TVSN network changed before transaction commit; the joint transaction must roll back");}}
    private static final ThreadLocal<Map<Transaction,State>> STATES=ThreadLocal.withInitial(IdentityHashMap::new);
    private static final class State extends SnapshotParticipant<Integer>{
        final List<BooleanSupplier> checks=new ArrayList<>();
        final Set<BooleanSupplier> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        boolean revoked;
        protected Integer createSnapshot(){return checks.size();}
        protected void readSnapshot(Integer size){while(checks.size()>size)seen.remove(checks.removeLast());}
    }
    public static void watch(TransactionContext tx,BooleanSupplier permission){
        Transaction root=tx.getOpenTransaction(0);var states=STATES.get();var state=states.get(root);
        if(state==null){state=new State();states.put(root,state);tx.addOuterCloseCallback(result->{var current=STATES.get();current.remove(root);if(current.isEmpty())STATES.remove();});}
        state.updateSnapshots(tx);if(state.seen.add(permission))state.checks.add(permission);
    }
    public static void validate(Transaction tx){
        var states=STATES.get();if(states.isEmpty())return;var root=tx.nestingDepth()==0?tx:tx.getOpenTransaction(0);var state=states.get(root);if(state==null)return;
        if(state.revoked)throw new Revoked();
        for(var check:state.checks)if(!check.getAsBoolean()){state.revoked=true;throw new Revoked();}
    }
}
