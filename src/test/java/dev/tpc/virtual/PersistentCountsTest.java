package dev.tpc.virtual;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PersistentCountsTest {
    @Test void oldSnapshotsRemainImmutableAcrossRandomizedChanges(){
        var random=new Random(5291);var expected=new HashMap<String,Long>();var actual=new PersistentCounts<String>();
        var snapshots=new ArrayList<Map<String,Long>>();var copies=new ArrayList<Map<String,Long>>();
        for(int i=0;i<20000;i++){
            String key="variant"+random.nextInt(2000);long amount=random.nextInt(50);
            if(amount==0)expected.remove(key);else expected.put(key,amount);actual=actual.with(key,amount);
            if(i%1000==0){snapshots.add(actual);copies.add(Map.copyOf(expected));}
        }
        assertEquals(expected,actual);for(int i=0;i<snapshots.size();i++)assertEquals(copies.get(i),snapshots.get(i));
        var snapshot=actual;assertThrows(UnsupportedOperationException.class,()->snapshot.put("invalid",1L));
    }
    private record Collision(int id){@Override public int hashCode(){return 1;}}
    @Test void fullVariantHashCollisionsNeverMergeDifferentItems(){
        var map=new PersistentCounts<Collision>();for(int i=0;i<100;i++)map=map.with(new Collision(i),i+1);var previous=map;
        map=map.with(new Collision(50),0);assertEquals(100,previous.size());assertEquals(99,map.size());assertEquals(51,previous.get(new Collision(50)));assertNull(map.get(new Collision(50)));
    }
}
