package dev.tpc.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TopologyTest {
    static Point p(int x) { return new Point(x, 64, 0); }
    static final NodeSpec WIRE = NodeSpec.ordinary(48);
    static final NodeSpec NODE = new NodeSpec(63, 63, true, List.of());
    static Topology line(int n) {
        Topology t = new Topology(256);
        // Fixture represents an already verified run. It is not a world indexer.
        t.restore(List.of(new CableRun(1, p(1), 0, n - 1, WIRE)), 1);
        t.observe(p(0), NODE); t.observe(p(n), NODE);
        return t;
    }
    @Test void requestedScalesAndLocalBreakRepair() {
        for (int n : new int[]{2048, 10_000, 100_000, 1_000_000, 10_000_000}) {
            Topology t = line(n);
            assertEquals(3, t.runCount()); assertEquals(n + 1L, t.cableCount());
            for (int x : new int[]{1, n / 4, n / 2, n * 3 / 4, n - 1}) {
                var old = t.query(p(0), 32); assertTrue(old.functions().contains(p(n)));
                long steps = t.searchSteps();
                t.observe(p(x), null);
                assertFalse(old.valid()); assertFalse(t.query(p(0), 32).functions().contains(p(n)));
                t.observe(p(x), WIRE);
                assertTrue(t.query(p(0), 32).functions().contains(p(n)));
                assertEquals(3, t.runCount()); assertEquals(n + 1L, t.cableCount());
                assertTrue(t.searchSteps() - steps < 20, "Must visit compressed records, not cable length");
            }
            long steps = t.searchSteps(); for (int i = 0; i < 10_000; i++) assertTrue(t.query(p(0), 0).valid());
            assertEquals(steps, t.searchSteps(), "Stable cached access must do no graph search");
        }
    }
    @Test void actualPerBlockObservationsCompressAndUnknownGapStaysDisconnected() {
        Topology t = new Topology(256);
        t.observe(p(0), NODE); t.observe(p(10_001), NODE);
        for (int x = 1; x <= 10_000; x++) if (x != 5000) t.observe(p(x), WIRE);
        assertFalse(t.query(p(0), 100).functions().contains(p(10_001)));
        t.observe(p(5000), WIRE);
        assertEquals(3, t.runCount()); assertTrue(t.query(p(0), 100).functions().contains(p(10_001)));
    }
    @Test void directedIncomingRulesAndLongFunctionalJumps() {
        Topology t = new Topology(32);
        t.observe(p(0), new NodeSpec(63, 32, true, List.of()));
        t.observe(p(1), new NodeSpec(1, 48, true, List.of()));
        assertEquals(Set.of(p(0)), t.query(p(0), 100).functions());
        t.observe(p(1), new NodeSpec(16, 0, true, List.of(new NodeSpec.Target(p(500), 2))));
        t.observe(p(500), new NodeSpec(4, 0, true, List.of()));
        assertEquals(Set.of(p(0), p(1), p(500)), t.query(p(0), 100).functions());
        assertEquals(Set.of(p(500)), t.query(p(500), 100).functions());
    }
    @Test void ringAlternativePathBranchAndMerge() {
        Topology t = new Topology(256);
        // Exact per-position masks, with an extra branch at (4,64,0).
        Set<Point> ps = new HashSet<>();
        for (int x = 0; x <= 8; x++) { ps.add(new Point(x,64,0)); ps.add(new Point(x,64,4)); }
        for (int z = 1; z < 4; z++) { ps.add(new Point(0,64,z)); ps.add(new Point(8,64,z)); }
        for (int z = -1; z >= -4; z--) ps.add(new Point(4,64,z));
        for (Point q : ps) {
            int mask = 0; for (int d = 0; d < 6; d++) if (ps.contains(q.step(d))) mask |= 1 << d;
            t.observe(q, q.equals(p(0)) || q.equals(p(8)) ? new NodeSpec(63, mask, true, List.of()) : NodeSpec.ordinary(mask));
        }
        assertTrue(t.query(p(0), 100).functions().contains(p(8)));
        t.observe(p(3), null); assertTrue(t.query(p(0), 100).functions().contains(p(8)));
        t.observe(new Point(3,64,4), null); assertFalse(t.query(p(0), 100).functions().contains(p(8)));
        t.observe(p(3), WIRE); assertTrue(t.query(p(0), 100).functions().contains(p(8)));
    }
    @Test void suspensionIsImmediateAndIndependentNetworkKeepsLease() {
        Topology t = line(10_000_000);
        t.observe(new Point(0, 80, 0), NODE);
        var independent = t.query(new Point(0,80,0),100);
        var lease = t.query(p(0),100);
        t.suspend(p(5_000_000));
        assertFalse(lease.valid()); assertFalse(t.query(p(0),100).valid()); assertTrue(independent.valid());
        t.observe(p(5_000_000), WIRE);
        assertFalse(lease.valid()); assertTrue(t.query(p(0),100).functions().contains(p(10_000_000)));
    }
    @Test void budgetedWorkAndStaleSearchesFailClosed() {
        Topology t = new Topology(32);
        for (int x = 0; x < 200; x++) t.observe(p(x), NODE);
        var lease = t.query(p(0),2); assertFalse(lease.valid());
        t.tick(2,Long.MAX_VALUE); assertFalse(lease.valid());
        t.observe(p(100),null); assertFalse(lease.valid());
        var next = t.query(p(0),0);
        for (int i = 0; i < 120; i++) t.tick(2,Long.MAX_VALUE);
        assertTrue(next.valid()); assertEquals(100,next.functions().size());
    }
    @Test void randomizedEditsMatchDirectedReferenceBfs() {
        Random random = new Random(5454);
        Topology t = new Topology(128); Map<Point,NodeSpec> reference = new HashMap<>();
        for (int step = 0; step < 2500; step++) {
            Point q = new Point(random.nextInt(20),64,random.nextInt(5));
            NodeSpec spec = random.nextInt(4) == 0 ? null : new NodeSpec(random.nextInt(64),random.nextInt(64),true,List.of());
            t.observe(q,spec); if (spec == null) reference.remove(q); else reference.put(q,spec);
            Point root = new Point(random.nextInt(20),64,random.nextInt(5));
            Set<Point> seen = new HashSet<>(); ArrayDeque<Point> queue = new ArrayDeque<>(); if (reference.containsKey(root)) queue.add(root);
            while (!queue.isEmpty()) {
                Point a = queue.remove(); if (!seen.add(a)) continue; NodeSpec s = reference.get(a);
                for (int d=0;d<6;d++) if ((s.outgoing() & 1<<d)!=0) {
                    Point b=a.step(d); NodeSpec n=reference.get(b);
                    if (n!=null && (n.incoming() & 1<<(d^1))!=0) queue.add(b);
                }
            }
            assertEquals(seen,t.query(root,1000).functions(),"Step "+step);
        }
    }
    @Test void differentDimensionsAreIsolatedBySeparateOwnersAndOffThreadMutationFails() throws Exception {
        Topology a=line(100), b=line(100); a.observe(p(50),null);
        assertFalse(a.query(p(0),100).functions().contains(p(100))); assertTrue(b.query(p(0),100).functions().contains(p(100)));
        Thread worker = new Thread(() -> assertThrows(IllegalStateException.class, () -> b.observe(p(1),null)));
        worker.start(); worker.join();
    }
    @Test void chunkRevalidationBlocksNewAndBorrowedLeasesUntilEveryObservedCellIsChecked() {
        Topology t=line(10_000_000);var borrowed=t.query(p(0),100);
        assertEquals(1,t.runsInChunk(300000,0).size());
        t.beginChunkValidation(300000,0);assertFalse(borrowed.valid());assertFalse(t.query(p(0),100).valid());
        t.observe(p(4_800_001),WIRE);
        assertFalse(t.query(p(0),100).valid(),"Checking one cell must not validate the remainder of a chunk");
        t.observe(p(4_800_002),null);t.endChunkValidation(300000,0);
        assertFalse(t.query(p(0),100).functions().contains(p(10_000_000)));
        t.observe(p(4_800_002),WIRE);assertTrue(t.query(p(0),100).functions().contains(p(10_000_000)));
    }
}
