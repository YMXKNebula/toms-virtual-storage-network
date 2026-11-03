package dev.tpc.integration;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import java.nio.file.*;
import java.util.*;

/** Test-server event timings only; this class is excluded from the delivered mod JAR. */
public final class TestMetrics implements ModInitializer {
    private long start;
    private final List<Long> ticks=new ArrayList<>();
    private long maxHeap;
    public void onInitialize(){
        ServerTickEvents.START_SERVER_TICK.register(s->start=System.nanoTime());
        ServerTickEvents.END_SERVER_TICK.register(s->{ticks.add(System.nanoTime()-start);maxHeap=Math.max(maxHeap,Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());});
        ServerLifecycleEvents.SERVER_STOPPED.register(s->{
            try{
                Path root=Path.of("../../build/benchmarks").resolve(System.getProperty("tpc.testProfile","baseline"));Files.createDirectories(root);
                StringBuilder csv=new StringBuilder("tick,event_span_ms\n");for(int i=0;i<ticks.size();i++)csv.append(i).append(',').append(ticks.get(i)/1_000_000.0).append('\n');
                Files.writeString(root.resolve("gametest-server-ticks.csv"),csv);
                long[] sorted=ticks.stream().mapToLong(Long::longValue).sorted().toArray();
                if(sorted.length>0)Files.writeString(root.resolve("gametest-server-summary.txt"),String.format(Locale.ROOT,"context=Fabric GameTest server; no clients; startup included; START_SERVER_TICK to END_SERVER_TICK events only\nticks=%d\nmean_ms=%.6f\np95_ms=%.6f\np99_ms=%.6f\nmax_observed_heap_bytes=%d\n",sorted.length,Arrays.stream(sorted).average().orElse(0)/1_000_000,sorted[(int)Math.ceil(sorted.length*.95)-1]/1_000_000.0,sorted[(int)Math.ceil(sorted.length*.99)-1]/1_000_000.0,maxHeap));
            }catch(Exception e){throw new RuntimeException(e);}
        });
    }
}
