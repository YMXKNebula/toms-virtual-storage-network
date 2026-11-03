package dev.tpc.core;

import java.nio.file.*;
import java.util.*;

/** Reproducible algorithm benchmark. Generated observations are not Minecraft world evidence. */
public final class TopologyBenchmark {
    private static Point p(int x){return new Point(x,64,0);}
    private static double percentile(long[] values,double q){long[] copy=values.clone();Arrays.sort(copy);return copy[Math.min(copy.length-1,(int)Math.ceil(copy.length*q)-1)]/1_000_000.0;}
    public static void main(String[] args)throws Exception {
        Path directory=Path.of("build/benchmarks");Files.createDirectories(directory);
        StringBuilder csv=new StringBuilder("simulated_length,index_ms,records,steady_query_p50_ms,steady_query_p95_ms,steady_query_p99_ms,break_query_p50_ms,break_query_p95_ms,break_query_p99_ms,repair_query_p50_ms,repair_query_p95_ms,repair_query_p99_ms,compacted_bytes,heap_used_bytes\n");
        NodeSpec wire=NodeSpec.ordinary(48),node=new NodeSpec(63,63,true,List.of());
        for(int length:new int[]{2048,10_000,100_000,1_000_000,10_000_000}) {
            Topology t=new Topology(256);long begin=System.nanoTime();t.observe(p(0),node);
            // Every simulated cable position is supplied, so the compressor cannot infer unknown gaps.
            for(int x=1;x<length;x++)t.observe(p(x),wire);
            t.observe(p(length),node);double indexing=(System.nanoTime()-begin)/1_000_000.0;
            if(t.runCount()!=3 || !t.query(p(0),64).functions().contains(p(length)))throw new AssertionError("Index");
            for(int i=0;i<5000;i++)t.query(p(0),0);
            long steps=t.searchSteps();long[] queries=new long[10_000];
            for(int i=0;i<queries.length;i++){begin=System.nanoTime();if(!t.query(p(0),0).valid())throw new AssertionError("Query");queries[i]=System.nanoTime()-begin;}
            if(t.searchSteps()!=steps)throw new AssertionError("Steady query searched graph");
            long[] breaks=new long[250],repairs=new long[250];
            int[] positions={1,length/4,length/2,length*3/4,length-1};
            for(int i=0;i<breaks.length;i++) {
                int x=positions[i%5];var old=t.query(p(0),64);begin=System.nanoTime();t.observe(p(x),null);
                if(old.valid() || t.query(p(0),64).functions().contains(p(length)))throw new AssertionError("Break");breaks[i]=System.nanoTime()-begin;
                begin=System.nanoTime();t.observe(p(x),wire);if(!t.query(p(0),64).functions().contains(p(length)))throw new AssertionError("Repair");repairs[i]=System.nanoTime()-begin;
                t.tick(4096,Long.MAX_VALUE);
            }
            Path journal=directory.resolve("line-"+length+"-"+UUID.randomUUID()+".wal");
            try(var j=new TopologyJournal(journal,"simulation:overworld")){j.open(100);j.append(new Topology.Edit(List.of(),List.copyOf(t.snapshot()),t.version()));}
            long heap=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
            csv.append(String.format(Locale.ROOT,"%d,%.3f,%d,%.9f,%.9f,%.9f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%d,%d%n",length,indexing,t.runCount(),percentile(queries,.5),percentile(queries,.95),percentile(queries,.99),percentile(breaks,.5),percentile(breaks,.95),percentile(breaks,.99),percentile(repairs,.5),percentile(repairs,.95),percentile(repairs,.99),Files.size(journal),heap));
            System.out.println("Simulated "+length+" positions; "+t.runCount()+" compressed records; index "+indexing+" ms");
        }
        Files.writeString(directory.resolve("topology.csv"),csv);
        Files.writeString(directory.resolve("environment.txt"),"Java="+System.getProperty("java.runtime.version")+"\nos="+System.getProperty("os.name")+"\narchitecture="+System.getProperty("os.arch")+"\nprocessors="+Runtime.getRuntime().availableProcessors()+"\nmaxHeap="+Runtime.getRuntime().maxMemory()+"\nGenerated algorithm data only. Heap includes temporary garbage. These are not server MSPT measurements.\n");
        System.out.println(csv);
    }
}
