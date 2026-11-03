package dev.tpc.integration;

import com.tom.storagemod.block.entity.*;
import com.tom.storagemod.Content;
import com.tom.storagemod.block.*;
import com.tom.storagemod.inventory.StoredItemStack;
import dev.tpc.PersistentCables;
import dev.tpc.virtual.VirtualNetwork;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Actual reported block layout. Polls the native open-terminal path without prewarming itemCache. */
public final class ConnectorEntryGameTests {
    private static void await(GameTestHelper t,BooleanSupplier ready,Runnable done){if(ready.getAsBoolean())done.run();else t.runAfterDelay(1,()->await(t,ready,done));}
    @SuppressWarnings({"unchecked","rawtypes"}) private static BlockState property(BlockState state,String name,String value){Property p=state.getBlock().getStateDefinition().getProperty(name);if(p==null)throw new IllegalArgumentException(name);return state.setValue(p,(Comparable)p.getValue(value).orElseThrow());}
    private record Cell(BlockPos pos,BlockState state){}
    private static void small(GameTestHelper t,boolean frozen){
        var l=t.getLevel();var p=t.absolutePos(new BlockPos(2,6,2));var cp=p.east(3);var bp=cp.east();
        if(frozen)ConnectorTestHooks.frozen.add(cp.immutable());
        l.setBlock(bp,dev.tpc.virtual.VirtualContent.BARREL.defaultBlockState(),3);
        l.setBlock(cp,Content.connector.get().defaultBlockState(),3);
        var wire=Content.invCable.get().defaultBlockState().setValue(InventoryCableBlock.EAST,true).setValue(InventoryCableBlock.WEST,true);
        l.setBlock(p.east(),wire,3);l.setBlock(p.east(2),wire,3);
        l.setBlock(p,Content.craftingTerminal.get().defaultBlockState().setValue(AbstractStorageTerminalBlock.FACING,Direction.EAST),3);
        var terminal=(StorageTerminalBlockEntity)l.getBlockEntity(p);var connector=(InventoryConnectorBlockEntity)l.getBlockEntity(cp);var world=VirtualNetwork.get(l);
        if(frozen){
            var temporary=bp.east();l.setBlock(temporary,Content.connector.get().defaultBlockState(),3);var writer=(InventoryConnectorBlockEntity)l.getBlockEntity(temporary);
            await(t,()->writer.getMergedHandler().getSlotCount()==64,()->{
                t.assertTrue(writer.getMergedHandler().pushStack(new ItemStack(Items.DIAMOND,31)).isEmpty(),"Prepare stock through a real native connector");l.setBlock(temporary,Blocks.AIR.defaultBlockState(),3);terminal.getStacks();
                observe(t,terminal,connector,31,120,()->{ConnectorTestHooks.frozen.remove(cp);t.succeed();});
            });
        }else await(t,()->connector.getMergedHandler().getSlotCount()==64 && PersistentCables.manager(l).query(p).valid(),()->{
            var lease=PersistentCables.manager(l).query(p);world.peers.chunk(cp.getX()>>4,cp.getZ()>>4);ConnectorTestHooks.hidden.add(cp.immutable());
            try{t.assertTrue(world.peers.get(lease).isEmpty(),"Model a query before the loaded connector entity is published");}finally{ConnectorTestHooks.hidden.remove(cp);}
            t.assertTrue(connector.getMergedHandler().pushStack(new ItemStack(Items.DIAMOND,31)).isEmpty(),"The now-published working connector must accept real stock");terminal.getStacks();
            observe(t,terminal,connector,31,120,t::succeed);
        });
    }
    @GameTest(maxTicks=1800) public void lateConnectorPublicationRefreshesAnEmptyPeerCache(GameTestHelper t){small(t,false);}
    @GameTest(maxTicks=1800) public void loadedConnectorDoesNotRequirePeriodicTicksToProvideAnEntry(GameTestHelper t){small(t,true);}
    private static List<Cell> layout(int offset)throws IOException{
        var cells=new ArrayList<Cell>();try(var input=ConnectorEntryGameTests.class.getResourceAsStream("/tvsn-reported-network.tsv");var reader=new BufferedReader(new InputStreamReader(Objects.requireNonNull(input),StandardCharsets.UTF_8))){
            String line;while((line=reader.readLine())!=null){var p=line.split("\t",-1);var pos=new BlockPos(Integer.parseInt(p[0])+offset,Integer.parseInt(p[1]),Integer.parseInt(p[2])+8_000_000);var state=BuiltInRegistries.BLOCK.getValue(Identifier.parse(p[3])).defaultBlockState();
                if(!p[4].isEmpty())for(String setting:p[4].split(",")){var pair=setting.split("=",2);state=property(state,pair[0],pair[1]);}cells.add(new Cell(pos,state));
            }
        }return cells;
    }
    @GameTest(maxTicks=5000) public void reportedWorkingConnectorWithoutEmptyEntryAllWarehousesLoaded(GameTestHelper t){reported(t,false);}
    @GameTest(maxTicks=5000) public void reportedWorkingConnectorWithOtherWarehousesUnloaded(GameTestHelper t){reported(t,true);}
    private static void reported(GameTestHelper t,boolean unload){
        var l=t.getLevel();int offset=unload?8_002_048:8_000_000;List<Cell> cells;try{cells=layout(offset);}catch(IOException ex){throw new RuntimeException(ex);}
        var chunks=new HashSet<ChunkPos>();for(var cell:cells)chunks.add(new ChunkPos(cell.pos.getX()>>4,cell.pos.getZ()>>4));for(var cp:chunks){l.setChunkForced(cp.x(),cp.z(),true);l.getChunk(cp.x(),cp.z());}
        for(var cell:cells){if(cell.pos.getY()==-63)l.setBlock(cell.pos.below(),Blocks.BEDROCK.defaultBlockState(),2);l.setBlock(cell.pos,cell.state,3);}
        var empty=new BlockPos(offset+460,-60,8_000_000-91);l.setBlock(empty,Blocks.AIR.defaultBlockState(),3);
        var entry=new BlockPos(offset+334,-60,8_000_000-88);var terminalPos=new BlockPos(offset+342,-60,8_000_000-90);
        var connector=(InventoryConnectorBlockEntity)l.getBlockEntity(entry);var terminal=(StorageTerminalBlockEntity)l.getBlockEntity(terminalPos);
        terminal.getStacks();
        await(t,()->connector.getMergedHandler().getSlotCount()>0,()->{
            t.assertTrue(connector.getMergedHandler().pushStack(new ItemStack(Items.DIAMOND,31)).isEmpty(),"The loaded barrel-facing storage connector must accept real stock");
            if(unload)for(var cp:chunks)if(Math.abs(cp.x()-(entry.getX()>>4))>2 || Math.abs(cp.z()-(entry.getZ()>>4))>2)l.setChunkForced(cp.x(),cp.z(),false);
            observe(t,terminal,connector,31,200,()->{
                var pulled=terminal.pullStack(new StoredItemStack(new ItemStack(Items.DIAMOND)),31);t.assertTrue(pulled!=null && pulled.getQuantity()==31,"The naturally updated terminal must extract from a loaded working connector");
                for(var cp:chunks)l.setChunkForced(cp.x(),cp.z(),false);t.succeed();
            });
        });
    }
    private static void observe(GameTestHelper t,StorageTerminalBlockEntity terminal,InventoryConnectorBlockEntity connector,long expected,int ticks,Runnable done){
        var stacks=terminal.getStacks();long amount=stacks.values().stream().filter(s->s.getStack().is(Items.DIAMOND)).mapToLong(StoredItemStack::getQuantity).sum();
        if(ticks==0){t.assertTrue(amount==expected,"Reported layout must work without an empty connector; terminal="+amount+", connector slots="+connector.getMergedHandler().getSlotCount()+", connector stock="+connector.getMergedHandler().tracker().countItems(new StoredItemStack(new ItemStack(Items.DIAMOND))));done.run();}
        else t.runAfterDelay(1,()->observe(t,terminal,connector,expected,ticks-1,done));
    }
}
