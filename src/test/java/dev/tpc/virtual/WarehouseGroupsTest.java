package dev.tpc.virtual;

import dev.tpc.core.Point;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WarehouseGroupsTest {
    @Test void onlyTheAffectedWarehouseSplitsAndMerges(){
        var groups=new WarehouseGroups((p,g)->{});var a=new Point(0,0,0);var b=new Point(1,0,0);var c=new Point(2,0,0);var other=new Point(100,0,0);
        groups.register(a);groups.register(b);groups.register(c);long independent=groups.register(other);
        for(int i=0;i<10;i++)groups.tick(1);assertEquals(groups.group(a),groups.group(c));
        groups.remove(b);for(int i=0;i<10;i++)groups.tick(1);assertNotEquals(groups.group(a),groups.group(c));assertEquals(independent,groups.group(other));
        groups.register(b);for(int i=0;i<10;i++)groups.tick(1);assertEquals(groups.group(a),groups.group(c));
    }
    @Test void batchChangesAreCoalescedBeforeSplitVerification(){
        var groups=new WarehouseGroups((p,g)->{});for(int x=0;x<10000;x++)groups.register(new Point(x,0,0));
        for(int x=100;x<9900;x++)groups.remove(new Point(x,0,0));for(int i=0;i<1000;i++)groups.tick(1);
        assertNotEquals(groups.group(new Point(0,0,0)),groups.group(new Point(9999,0,0)));assertEquals(0,groups.pending());
    }
}
