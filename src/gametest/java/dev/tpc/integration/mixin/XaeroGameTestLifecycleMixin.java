package dev.tpc.integration.mixin;

import dev.tpc.PersistentCables;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test adapter: Xaero only calls its late server initializer for DedicatedServer,
 * whereas Fabric's GameTestServer is a separate MinecraftServer subclass. */
@Pseudo
@Mixin(targets="xaero.lib.common.event.CommonEventsFabric",remap=false)
public abstract class XaeroGameTestLifecycleMixin {
    @Inject(method="serverStarting",at=@At("HEAD"))
    private void tpc$initializeTestServer(MinecraftServer server,CallbackInfo ci) {
        if(server instanceof DedicatedServer)return;
        try {
            Object lib=Class.forName("xaero.lib.XaeroLib").getField("INSTANCE").get(null);
            lib.getClass().getMethod("tryLoadLaterServer").invoke(lib);
            PersistentCables.LOG.info("TPC_TEST_ADAPTER: invoked Xaero's native late server initializer for GameTestServer");
        } catch(ReflectiveOperationException e){throw new IllegalStateException("Xaero GameTest lifecycle adapter failed",e);}
    }
}
