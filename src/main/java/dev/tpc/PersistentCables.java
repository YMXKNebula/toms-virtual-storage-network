package dev.tpc;

import dev.tpc.core.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;

public final class PersistentCables implements ModInitializer {
    public static final Logger LOG=LoggerFactory.getLogger("toms_persistent_cables");
    public static TpcConfig CONFIG;
    private static final Map<ServerLevel,CableManager> MANAGERS=new IdentityHashMap<>();
    public static boolean enabled() { return CONFIG!=null && CONFIG.enablePersistentNetwork; }
    public static CableManager manager(ServerLevel level) {
        if(!level.getServer().isSameThread())throw new IllegalStateException("World access off server thread");
        return MANAGERS.computeIfAbsent(level,CableManager::new);
    }
    public static Point point(BlockPos p) { return new Point(p.getX(),p.getY(),p.getZ()); }
    public static BlockPos block(Point p) { return new BlockPos(p.x(),p.y(),p.z()); }
    @Override public void onInitialize() {
        CONFIG=TpcConfig.load();
        dev.tpc.virtual.VirtualContent.register();
        ServerChunkEvents.CHUNK_LOAD.register((level,chunk,generated)->{if(enabled()){manager(level).loaded(chunk);dev.tpc.virtual.VirtualNetwork.get(level).loaded(chunk);}});
        ServerChunkEvents.CHUNK_UNLOAD.register((level,chunk)->{if(enabled()){manager(level).unloaded(chunk);dev.tpc.virtual.VirtualNetwork.get(level).unloaded(chunk);}});
        ServerTickEvents.START_LEVEL_TICK.register(level->{if(enabled())manager(level).startTick();});
        ServerTickEvents.END_LEVEL_TICK.register(level->{if(enabled()){manager(level).tick();dev.tpc.virtual.VirtualNetwork.get(level).tick();}});
        ServerLifecycleEvents.AFTER_SAVE.register((server,flush,force)->dev.tpc.virtual.VirtualNetwork.saved(server,flush));
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{
            dev.tpc.virtual.VirtualNetwork.closeAll();
            for(CableManager m:new ArrayList<>(MANAGERS.values()))m.close(); MANAGERS.clear();
        });
        CommandRegistrationCallback.EVENT.register((dispatcher,registry,environment)->dispatcher.register(Commands.literal("tomsnet")
            .requires(s->s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
            .then(Commands.literal("status").executes(c->{c.getSource().sendSuccess(()->Component.literal(manager(c.getSource().getLevel()).status()),false);return 1;}))
            .then(Commands.literal("inspect").executes(c->{BlockPos p=BlockPos.containing(c.getSource().getPosition());var m=manager(c.getSource().getLevel());c.getSource().sendSuccess(()->Component.literal(m.inspect(p)),false);return 1;}))
            .then(Commands.literal("rebuild").executes(c->{manager(c.getSource().getLevel()).revalidateLoaded();c.getSource().sendSuccess(()->Component.literal("Queued revalidation of loaded chunks in this dimension; no chunks were loaded."),true);return 1;}))));
        CommandRegistrationCallback.EVENT.register((dispatcher,registry,environment)->dispatcher.register(Commands.literal("tvsn")
            .requires(s->s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
            .then(Commands.literal("status").executes(c->{c.getSource().sendSuccess(()->Component.literal(dev.tpc.virtual.VirtualNetwork.get(c.getSource().getLevel()).status()),false);return 1;}))
            .then(Commands.literal("checkpoint").executes(c->{boolean queued=dev.tpc.virtual.VirtualNetwork.get(c.getSource().getLevel()).requestCheckpoint();c.getSource().sendSuccess(()->Component.literal(queued?"TVSN checkpoint queued":"TVSN checkpoint is already pending or the ledger is unavailable"),false);return queued?1:0;}))
            .then(Commands.literal("recovery").then(Commands.literal("export").executes(c->{try{var path=dev.tpc.virtual.VirtualNetwork.get(c.getSource().getLevel()).exportRecovery();c.getSource().sendSuccess(()->Component.literal("TVSN diagnostic export: "+path),false);return 1;}catch(java.io.IOException ex){c.getSource().sendFailure(Component.literal("TVSN export failed: "+ex));return 0;}})))
            .then(Commands.literal("inspect").executes(c->{c.getSource().sendSuccess(()->Component.literal(dev.tpc.virtual.VirtualNetwork.get(c.getSource().getLevel()).describe(BlockPos.containing(c.getSource().getPosition()))),false);return 1;}))));
        LOG.info("TVSN persistent topology enabled={}, target Tom's Storage 2.12.2 / Minecraft 26.3",enabled());
    }
}
