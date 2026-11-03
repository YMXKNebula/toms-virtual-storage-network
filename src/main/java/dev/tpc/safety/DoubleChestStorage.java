package dev.tpc.safety;

import dev.tpc.PersistentCables;
import net.fabricmc.fabric.api.transfer.v1.item.*;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedSlottedStorage;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import java.lang.ref.WeakReference;
import java.util.*;

/** Only the vanilla two-chest Fabric provider may replace its outer wrapper without
 * replacing the inventory. Unknown providers retain the strict identity check. */
public final class DoubleChestStorage {
    // Weak keys AND values: the combined storage itself retains both component storages.
    private static final Map<Object,WeakReference<Storage<ItemVariant>>> CANONICAL=new WeakHashMap<>();
    private final ServerLevel level;
    private final BlockPos first,second;
    private final LevelChunk firstChunk,secondChunk;
    private final BlockState firstState,secondState;
    private final ChestBlockEntity firstEntity,secondEntity;
    private final Object firstToken,secondToken;
    private final Object part0,part1;
    private final Storage<ItemVariant> storage;
    private boolean retired;

    private DoubleChestStorage(ServerLevel level,BlockPos pos,BlockPos partner,Storage<ItemVariant> raw) {
        this.level=level;first=pos.immutable();second=partner.immutable();
        firstChunk=chunk(level,first);secondChunk=chunk(level,second);
        firstState=firstChunk.getBlockState(first);secondState=secondChunk.getBlockState(second);
        firstEntity=(ChestBlockEntity)firstChunk.getBlockEntity(first);secondEntity=(ChestBlockEntity)secondChunk.getBlockEntity(second);
        firstToken=PersistentCables.manager(level).loadToken(first);secondToken=PersistentCables.manager(level).loadToken(second);
        var parts=((CombinedSlottedStorage<?,?>)raw).parts;
        part0=parts.get(0);part1=parts.get(1);
        var previous=CANONICAL.get(part0);var shared=previous==null?null:previous.get();
        storage=sameParts(shared)?shared:raw;
        CANONICAL.put(part0,new WeakReference<>(storage));
    }

    /** Call before the provider lookup, which otherwise may read an unloaded neighbor. */
    public static boolean otherHalfLoaded(ServerLevel level,BlockPos pos,BlockState state) {
        if(!vanilla(state) || state.getValue(ChestBlock.TYPE)==ChestType.SINGLE)return true;
        return chunk(level,pos.relative(ChestBlock.getConnectedDirection(state)))!=null;
    }

    public static DoubleChestStorage capture(ServerLevel level,BlockPos pos,Direction side,Storage<ItemVariant> raw) {
        if(raw.getClass()!=CombinedSlottedStorage.class)return null;
        var c=chunk(level,pos);if(c==null)return null;
        var state=c.getBlockState(pos);
        if(!vanilla(state) || state.getValue(ChestBlock.TYPE)==ChestType.SINGLE)return null;
        BlockPos partner=pos.relative(ChestBlock.getConnectedDirection(state));
        var other=chunk(level,partner);if(other==null)return null;
        var os=other.getBlockState(partner);
        if(os.getBlock()!=state.getBlock() || os.getValue(ChestBlock.TYPE)!=state.getValue(ChestBlock.TYPE).getOpposite()
                || os.getValue(ChestBlock.FACING)!=state.getValue(ChestBlock.FACING))return null;
        var a=c.getBlockEntity(pos);var b=other.getBlockEntity(partner);
        if(!vanilla(a,state) || !vanilla(b,os) || a.isRemoved() || b.isRemoved())return null;
        Object sa=ContainerStorage.of((ChestBlockEntity)a,side),sb=ContainerStorage.of((ChestBlockEntity)b,side);
        var parts=((CombinedSlottedStorage<?,?>)raw).parts;
        if(parts.size()!=2 || !((parts.get(0)==sa && parts.get(1)==sb) || (parts.get(0)==sb && parts.get(1)==sa)))return null;
        return new DoubleChestStorage(level,pos,partner,raw);
    }

    public Storage<ItemVariant> storage(){return storage;}

    public boolean valid() {
        if(!level.getServer().isSameThread())return false;
        if(retired)return false;
        if(chunk(level,first)!=firstChunk || chunk(level,second)!=secondChunk
                || firstChunk.getBlockState(first)!=firstState || secondChunk.getBlockState(second)!=secondState
                || firstChunk.getBlockEntity(first)!=firstEntity || secondChunk.getBlockEntity(second)!=secondEntity
                || firstEntity.isRemoved() || secondEntity.isRemoved()
                || PersistentCables.manager(level).loadToken(first)!=firstToken || PersistentCables.manager(level).loadToken(second)!=secondToken) {
            retired=true;return false;
        }
        return true;
    }

    public boolean matches(Storage<ItemVariant> candidate) {
        if(!valid())return false;
        if(!sameParts(candidate)){retired=true;return false;}
        return true;
    }

    private boolean sameParts(Storage<ItemVariant> candidate) {
        if(candidate==null || candidate.getClass()!=CombinedSlottedStorage.class)return false;
        var parts=((CombinedSlottedStorage<?,?>)candidate).parts;
        return parts.size()==2 && parts.get(0)==part0 && parts.get(1)==part1;
    }

    private static LevelChunk chunk(ServerLevel level,BlockPos pos) {return level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);}
    private static boolean vanilla(BlockState state) {return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST);}
    private static boolean vanilla(BlockEntity entity,BlockState state) {
        return entity!=null && entity.getClass()==(state.is(Blocks.CHEST)?ChestBlockEntity.class:TrappedChestBlockEntity.class);
    }
}
