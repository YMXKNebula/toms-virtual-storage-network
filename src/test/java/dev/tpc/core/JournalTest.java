package dev.tpc.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JournalTest {
    @TempDir Path directory;
    @Test void cleanRestartKeepsCompressedLineAndEdits() throws Exception {
        Path path=directory.resolve("graph.wal");
        Topology t=TopologyTest.line(10_000_000);
        try(var j=new TopologyJournal(path,"world:overworld")) {
            j.open(100); j.append(new Topology.Edit(List.of(),List.copyOf(t.snapshot()),t.version()));
            t.onEdit(j::append); t.observe(TopologyTest.p(5_000_000),null);
        }
        try(var j=new TopologyJournal(path,"world:overworld")) {
            var restored=j.open(100); assertTrue(restored.trusted());
            Topology r=new Topology(32); r.restore(restored.runs(),restored.version());
            assertFalse(r.query(TopologyTest.p(0),100).functions().contains(TopologyTest.p(10_000_000)));
            r.observe(TopologyTest.p(5_000_000),TopologyTest.WIRE);
            assertTrue(r.query(TopologyTest.p(0),100).functions().contains(TopologyTest.p(10_000_000)));
        }
        assertTrue(Files.size(path)<4096,"Persistence must describe runs, not ten million blocks");
    }
    @Test void uncleanSessionPartialWriteAndForeignWorldNeverRestoreTrustedEdges() throws Exception {
        for(int scenario=0;scenario<3;scenario++) {
            Path p=directory.resolve("graph"+scenario);
            try(var j=new TopologyJournal(p,"world:overworld")) {
                j.open(100); j.append(new Topology.Edit(List.of(),List.copyOf(TopologyTest.line(100).snapshot()),1));
                if(scenario==0) j.disableCleanRecovery();
            }
            if(scenario==1) { byte[] data=Files.readAllBytes(p); data[data.length-6]^=1; Files.write(p,data); }
            try(var j=new TopologyJournal(p,scenario==2?"foreign:overworld":"world:overworld")) {
                var r=j.open(100); assertFalse(r.trusted()); assertTrue(r.runs().isEmpty());
            }
        }
    }
    @Test void backgroundCompactionAndFollowingEditsReplayInOrder() throws Exception {
        Path p=directory.resolve("compact");Topology t=TopologyTest.line(10_000_000);
        try(var j=new TopologyJournal(p,"w:d")) {
            j.open(100);j.append(new Topology.Edit(List.of(),List.copyOf(t.snapshot()),t.version()));t.onEdit(j::append);
            for(int i=0;i<300;i++){t.observe(TopologyTest.p(100),null);t.observe(TopologyTest.p(100),TopologyTest.WIRE);}
            j.compact(new Topology.Edit(List.of(),List.copyOf(t.snapshot()),t.version()));t.observe(TopologyTest.p(500),null);
        }
        try(var j=new TopologyJournal(p,"w:d")) {
            var r=j.open(100);assertTrue(r.trusted());Topology restored=new Topology(16);restored.restore(r.runs(),r.version());
            assertEquals(t.cableCount(),restored.cableCount());assertFalse(restored.query(TopologyTest.p(0),100).functions().contains(TopologyTest.p(10_000_000)));
        }
        assertTrue(Files.size(p)<4096);
    }
}
