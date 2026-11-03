package dev.tpc.virtual;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class WarehouseLedgerTest {
    @Test void sameVariantCanOccupyAll64SlotsInEachBarrel(){
        var ledger=new WarehouseLedger<String>();ledger.register(1,UUID.randomUUID(),1,Map.of());ledger.register(2,UUID.randomUUID(),1,Map.of());
        var scope=ledger.scope(List.of(1L,2L));assertEquals(128,scope.capacity());assertEquals(0,scope.used());
        assertEquals(8192,scope.insert("stone",10000,n->{}));assertEquals(128,scope.used());assertEquals(4096,ledger.get(1).amount("stone"));
        assertEquals(0,scope.insert("stone",1,n->{}));assertEquals(8192,scope.extract("stone",10000,n->{}));assertEquals(0,scope.used());
        for(int i=0;i<128;i++)assertEquals(1,scope.insert("variant-"+i,1,n->{}));
        assertEquals(128,scope.used());assertEquals(0,scope.insert("overflow",1,n->{}));
    }
    @Test void mixedNativeLimitsShareSlotsAndPartialStacksMerge(){
        var l=new WarehouseLedger<String>(v->v.equals("pearl")?16:v.equals("pickaxe")?1:64);l.register(1,UUID.randomUUID(),1,Map.of());
        var s=l.scope(List.of(1L));assertEquals(65,s.insert("stone",65,n->{}));assertEquals(17,s.insert("pearl",17,n->{}));assertEquals(2,s.insert("pickaxe",2,n->{}));
        assertEquals(6,s.used());assertEquals(58,s.free());assertEquals(63,s.insert("stone",63,n->{}));assertEquals(6,s.used());
        assertEquals(58,s.insert("pickaxe",60,n->{}));assertEquals(0,s.free());assertEquals(0,s.insert("other",1,n->{}));
    }
    @Test void onlineCapacityWinsBeforeOfflinePartialAndOwnershipSurvivesSplit(){
        var l=new WarehouseLedger<String>();l.register(1,UUID.randomUUID(),1,Map.of("iron",32L));l.register(2,UUID.randomUUID(),2,Map.of());l.online(2,true);
        var joined=l.scope(List.of(1L,2L));assertEquals(16,joined.insert("iron",16,n->{}));assertEquals(32,l.get(1).amount("iron"));assertEquals(16,l.get(2).amount("iron"));
        var a=l.scope(List.of(1L));var b=l.scope(List.of(2L));assertEquals(32,a.amount("iron"));assertEquals(16,b.amount("iron"));
        assertEquals(10,joined.extract("iron",10,n->{}));assertEquals(6,b.amount("iron"));assertEquals(32,a.amount("iron"));
    }
    @Test void restoringDisjointFullInventoriesKeepsIndexesCorrect(){
        var l=new WarehouseLedger<String>();l.register(1,UUID.randomUUID(),1,Map.of());var scope=l.scope(List.of(1L));
        var before=new HashMap<String,Long>();var after=new HashMap<String,Long>();for(int i=0;i<64;i++){before.put("a"+i,1L);after.put("b"+i,2L);}
        l.restore(1,before);l.restore(1,after);assertEquals(64,scope.used());assertEquals(0,scope.amount("a0"));assertEquals(2,scope.amount("b0"));
        var snapshot=l.get(1).snapshot();l.set(1,"b0",3);assertEquals(2,snapshot.get("b0"));assertEquals(3,l.get(1).snapshot().get("b0"));
        l.remove(1);assertFalse(scope.live());
    }
    @Test void stackLimitChangesReindexExistingAllocationsWithoutLosingItems(){
        var limit=new AtomicLong(64);var l=new WarehouseLedger<String>(v->limit.get());l.register(1,UUID.randomUUID(),1,Map.of("stone",4096L));var s=l.scope(List.of(1L));
        assertEquals(64,s.used());limit.set(128);assertEquals(32,s.used());assertEquals(4096,s.insert("stone",4096,n->{}));assertEquals(64,s.used());
        limit.set(32);assertEquals(256,s.used());assertEquals(0,s.free());assertEquals(0,s.insert("other",1,n->{}));assertEquals(8192,s.extract("stone",8192,n->{}));assertEquals(64,s.free());
    }
    @Test void aRollbackCanReleaseNewStacksBeforeRestoringPreviousSlots(){
        var l=new WarehouseLedger<String>();l.register(1,UUID.randomUUID(),1,Map.of("stone",4096L));var s=l.scope(List.of(1L));var before=l.get(1).snapshot();
        assertEquals(4032,s.extract("stone",4032,n->{}));assertEquals(4032,s.insert("iron",4032,n->{}));l.restore(1,before);
        assertEquals(4096,s.amount("stone"));assertEquals(0,s.amount("iron"));assertEquals(64,s.used());
    }
    @Test void aLowerLimitNeverUsesUpAnotherBarrelsEmptySlots(){
        var limit=new AtomicLong(64);var l=new WarehouseLedger<String>(v->limit.get());l.register(1,UUID.randomUUID(),1,Map.of("stone",4096L));l.register(2,UUID.randomUUID(),1,Map.of());
        var s=l.scope(List.of(1L,2L));assertEquals(64,s.free());limit.set(32);
        assertEquals(64,s.free(),"An over-limit old allocation must not hide another barrel's 64 empty slots");
        var before=l.get(1).snapshot();assertEquals(32,s.extract("stone",32,n->{}));l.restore(1,before);assertEquals(4096,l.get(1).amount("stone"));assertEquals(64,s.free());
        assertEquals(2048,s.insert("stone",2048,n->{}));assertEquals(4096,l.get(1).amount("stone"));assertEquals(2048,l.get(2).amount("stone"));assertEquals(0,s.free());
    }
    @Test void eventReceiptsAreIdempotentAndReentrantDeliveryIsOrdered()throws Exception{
        var events=new VirtualEvents();var seen=new ArrayList<Long>();UUID tx=UUID.randomUUID();
        events.subscribe(e->{seen.add(e.id().sequence());if(e.id().sequence()==1)events.publish(new VirtualEvents.BarrelRemoved(new VirtualEvents.Id(tx,2),1,1));});
        var event=new VirtualEvents.BarrelRegistered(new VirtualEvents.Id(tx,1),1,1);events.publish(event);events.publish(event);assertEquals(List.of(1L,2L),seen);
    }
    @Test void fillingTenThousandBarrelsDoesNotRescanTheFilledPrefix(){
        var l=new WarehouseLedger<String>();for(long i=0;i<10000;i++)l.register(i,UUID.randomUUID(),1,Map.of());var scope=l.scope(l.nodes().stream().map(n->n.position).toList());
        for(int i=0;i<10000;i++)assertEquals(4096,scope.insert("stone",4096,n->{}));
        assertEquals(40960000,scope.amount("stone"));long visited=l.candidatesVisited();assertTrue(visited<=30000,"Candidate work must remain linear while filling barrels: "+visited);
        for(int i=0;i<1000;i++)assertEquals(0,scope.insert("stone",1,n->{}));assertEquals(visited,l.candidatesVisited(),"Full insertion must use the empty capacity index");
        assertEquals(16,scope.extract("stone",16,n->{}));assertEquals(16,scope.insert("stone",16,n->{}));
    }
}
