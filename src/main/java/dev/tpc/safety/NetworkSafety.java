package dev.tpc.safety;

import dev.tpc.*;
import dev.tpc.core.Topology;
import com.tom.storagemod.block.entity.*;
import com.tom.storagemod.inventory.RemoteConnections;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.util.*;
import java.util.function.BooleanSupplier;

public final class NetworkSafety {
    public static final class Ticket implements BooleanSupplier {
        private final List<Topology.Lease> leases=new ArrayList<>();
        private final List<BlockEntity> endpoints=new ArrayList<>();
        private final Thread thread=Thread.currentThread();
        private boolean revoked;
        public void invalidate(){revoked=true;}
        public void watch(BlockEntity endpoint){if(endpoint.getLevel() instanceof ServerLevel level)dev.tpc.virtual.VirtualNetwork.get(level).policies.watch(endpoint.getBlockPos(),this);}
        void add(BlockEntity endpoint) {
            if(endpoints.contains(endpoint))return;
            endpoints.add(endpoint);
            watch(endpoint);
            if(endpoint.getLevel() instanceof ServerLevel level)leases.add(PersistentCables.manager(level).query(endpoint.getBlockPos()));
        }
        public boolean getAsBoolean() {
            if(Thread.currentThread()!=thread || revoked)return false;
            for(Topology.Lease lease:leases)if(!lease.valid())return false;
            for(BlockEntity be:endpoints)if(!live(be))return false;
            return true;
        }
    }
    private static final ThreadLocal<ArrayDeque<Ticket>> BUILD=ThreadLocal.withInitial(ArrayDeque::new);
    public static boolean building(){return !BUILD.get().isEmpty();}
    public static Ticket begin(IInventoryConnector self){Ticket ticket=new Ticket();if(self instanceof BlockEntity be)ticket.add(be);BUILD.get().push(ticket);return ticket;}
    public static void finish(){BUILD.get().pop();if(BUILD.get().isEmpty())BUILD.remove();}
    public static boolean live(BlockEntity be){
        if(!(be.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread() || be.isRemoved())return false;
        BlockPos p=be.getBlockPos();var chunk=level.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4);
        return chunk!=null && chunk.getBlockEntity(p)==be;
    }
    public static BooleanSupplier origin(ServerLevel level,BlockPos from,BlockEntity endpoint) {
        Topology.Lease lease=PersistentCables.manager(level).query(from);
        boolean linked=lease.functions().contains(PersistentCables.point(endpoint.getBlockPos())) && endpoint.getLevel()==level;
        return ()->level.getServer().isSameThread() && linked && lease.valid() && live(endpoint);
    }
    public static Collection<IInventoryConnector> connectors(IInventoryConnector self) {
        if(!(self instanceof BlockEntity be) || !(be.getLevel() instanceof ServerLevel level) || !live(be))return List.of();
        var lease=PersistentCables.manager(level).query(be.getBlockPos());
        if(!BUILD.get().isEmpty())BUILD.get().peek().add(be);
        Set<IInventoryConnector> result=new LinkedHashSet<>();
        for(var connector:dev.tpc.virtual.VirtualNetwork.get(level).peers.get(lease))if(connector!=self)result.add(connector);
        // Preserve the original channel, beacon level and distance rules. findOthers only examines loaded endpoints.
        if(lease.valid() && self instanceof InventoryCableConnectorBlockEntity cable && cable.getChannel()!=null && cable.getBeaconLevel()>=0) {
            var facing=be.getBlockState().getValue(com.tom.storagemod.block.InventoryCableConnectorBlock.FACING);
            BlockPos beacon=be.getBlockPos().relative(facing);
            if(level.getChunkSource().getChunkNow(beacon.getX()>>4,beacon.getZ()>>4)!=null && cable.hasBeacon()) {
                var channel=RemoteConnections.get(level).getChannel(cable.getChannel());
                if(channel!=null)for(var link:channel.findOthers(level,be.getBlockPos(),cable.getBeaconLevel()))result.add(link.getConnector());
            }
        }
        return result;
    }
}
