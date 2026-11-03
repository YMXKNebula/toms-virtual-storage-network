package dev.tpc;

import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.*;

public final class TpcConfig {
    public boolean enablePersistentNetwork = true;
    public int maxTopologyUpdatesPerTick = 256;
    public int maxGraphNodesProcessedPerTick = 4096;
    public double maxTopologyProcessingTimeMs = 2.0;
    public int immediateGraphNodes = 64;
    public int maxCachedQueries = 256;
    public int maxResidentRuns = 250_000;
    public int maxQueuedPositions = 8192;
    public boolean enableDiagnostics = false;
    public int maxOverflowEntitiesPerTick = 16;
    public int unchangedScansBeforeIdle = 3;
    public int checkpointIntervalTicks = 6000;
    public int checkpointNodesPerTick = 64;
    public int maxCheckpointTailBytes = 64*1024*1024;
    public static TpcConfig load() {
        Path path=FabricLoader.getInstance().getConfigDir().resolve("toms_persistent_cables.json");
        var gson=new GsonBuilder().setPrettyPrinting().create();
        try {
            TpcConfig c=Files.exists(path)?gson.fromJson(Files.readString(path),TpcConfig.class):new TpcConfig();
            if(c!=null && !c.enablePersistentNetwork)throw new IllegalArgumentException("TVSN requires enablePersistentNetwork=true so capacity removals and inventory persistence cannot be bypassed");
            if(c==null || c.maxTopologyUpdatesPerTick<1 || c.maxGraphNodesProcessedPerTick<1 || c.maxTopologyProcessingTimeMs<=0 ||
                    !Double.isFinite(c.maxTopologyProcessingTimeMs) || c.immediateGraphNodes<1 || c.maxCachedQueries<1 || c.maxResidentRuns<16 || c.maxQueuedPositions<1 || c.maxOverflowEntitiesPerTick<1 || c.unchangedScansBeforeIdle<1 || c.checkpointIntervalTicks<20 || c.checkpointNodesPerTick<1 || c.maxCheckpointTailBytes<4096)
                throw new IllegalArgumentException("Invalid topology budget");
            if(!Files.exists(path))Files.writeString(path,gson.toJson(c));
            return c;
        } catch(Exception e) { throw new IllegalStateException("Cannot load "+path,e); }
    }
}
