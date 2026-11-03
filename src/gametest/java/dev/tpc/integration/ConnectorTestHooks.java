package dev.tpc.integration;
import net.minecraft.core.BlockPos;
import java.util.*;
/** Test-only lifecycle controls; never included in a production JAR. */
public final class ConnectorTestHooks {
    public static final Set<BlockPos> hidden=new HashSet<>(),frozen=new HashSet<>();
}
