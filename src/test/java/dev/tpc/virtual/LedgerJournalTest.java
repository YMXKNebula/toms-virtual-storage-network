package dev.tpc.virtual;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LedgerJournalTest {
    @TempDir Path root;
    @Test void truncatedAndCrcDamagedFramesQuarantineAndKeepTheEvidence()throws Exception{
        for(boolean truncate:new boolean[]{true,false}){
            Path file=root.resolve(truncate?"partial":"crc");var j=new LedgerJournal(file,"w/d");j.open(f->{});j.append(new byte[]{1,2,3,4});j.append(new byte[]{5,6,7,8});j.close();
            byte[] original=Files.readAllBytes(file),damaged=truncate?Arrays.copyOf(original,original.length-2):original.clone();if(!truncate)damaged[damaged.length-5]^=0x40;
            Files.write(file,damaged);Files.writeString(file.resolveSibling(file.getFileName()+".clean"),Long.toString(damaged.length));
            var records=new ArrayList<byte[]>();j=new LedgerJournal(file,"w/d");var state=j.open(f->records.add(f.payload()));assertFalse(state.trusted());assertEquals(1,records.size());j.close();assertArrayEquals(damaged,Files.readAllBytes(file));
        }
    }
    @Test void cleanRestartAndPartitionedCheckpointPreserveDeltaOrder()throws Exception{
        Path file=root.resolve("ledger");var j=new LedgerJournal(file,"world/overworld");assertTrue(j.open(f->fail()).trusted());
        j.append(new byte[]{1});j.checkpoint(List.of(new byte[]{2},new byte[]{3}));j.append(new byte[]{4});j.close();
        var values=new ArrayList<Integer>();j=new LedgerJournal(file,"world/overworld");assertTrue(j.open(f->values.add((int)f.payload()[0])).trusted());assertEquals(List.of(2,3,4),values);j.close();
    }
    @Test void uncleanCorruptAndForeignLedgersNeverBecomeLive()throws Exception{
        Path file=root.resolve("ledger");var j=new LedgerJournal(file,"world/overworld");j.open(f->{});j.append(new byte[]{9});j.closeUnclean();
        var recovered=new ArrayList<Integer>();j=new LedgerJournal(file,"world/overworld");assertFalse(j.open(f->recovered.add((int)f.payload()[0])).trusted());assertEquals(List.of(9),recovered);
        var quarantined=j;assertThrows(IllegalStateException.class,()->quarantined.append(new byte[]{1}));j.close();
        assertFalse(Files.exists(file.resolveSibling("ledger.clean")));
        var foreign=new LedgerJournal(file,"other/overworld");assertFalse(foreign.open(f->fail()).trusted());foreign.close();
    }
}
