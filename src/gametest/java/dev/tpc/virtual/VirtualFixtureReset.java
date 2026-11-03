package dev.tpc.virtual;

import net.minecraft.core.BlockPos;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import java.util.*;

/** Test-only disposal of a previous run's fixture stock, using the real ledger transaction path. */
public final class VirtualFixtureReset {
    public static void clear(VirtualNetwork network,BlockPos position){
        var node=network.ledger.get(position.asLong());if(node==null || node.contents().isEmpty())return;
        if(!network.healthy())throw new IllegalStateException("A quarantined ledger must never be reset to make a restart test pass");
        try(var storage=new LedgerStorage(network,network.ledger.scope(List.of(position.asLong())),()->true);var tx=Transaction.openOuter()){
            for(var variant:List.copyOf(node.contents().keySet()))storage.extract(variant,Long.MAX_VALUE,tx);tx.commit();
        }
    }
}
