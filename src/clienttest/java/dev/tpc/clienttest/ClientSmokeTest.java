package dev.tpc.clienttest;

import dev.tpc.PersistentCables;
import dev.tpc.virtual.*;
import com.tom.storagemod.Content;
import com.tom.storagemod.block.*;
import com.tom.storagemod.block.entity.StorageTerminalBlockEntity;
import com.tom.storagemod.inventory.NetworkInventory;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

public final class ClientSmokeTest implements FabricClientGameTest {
    public void runTest(ClientGameTestContext context){
        context.runOnClient(client->{if(!PersistentCables.enabled())throw new AssertionError("Mod failed to initialize on client");});
        try(var world=context.worldBuilder().create()){
            world.getConnection().waitForChunksDownload();
            BlockPos start=world.getServer().computeOnServer(server->{
                var player=server.getPlayerList().getPlayers().getFirst();var level=player.level();BlockPos p=player.blockPosition().above(2);
                level.setBlock(p,Content.terminal.get().defaultBlockState().setValue(AbstractStorageTerminalBlock.FACING,Direction.EAST),3);
                level.setBlock(p.east(),Content.connector.get().defaultBlockState(),3);level.setBlock(p.east(2),Blocks.CHEST.defaultBlockState(),3);
                ((ChestBlockEntity)level.getBlockEntity(p.east(2))).setItem(0,new ItemStack(Items.DIAMOND,12));return p;
            });
            world.getServer().waitFor(server->{
                var level=server.getPlayerList().getPlayers().getFirst().level();
                NetworkInventory inventory=new NetworkInventory();inventory.onLoad(level,start,Direction.DOWN,null);
                return inventory.getAccess(level,start).getSlotCount()>0;
            });
            world.getServer().runOnServer(server->{
                var player=server.getPlayerList().getPlayers().getFirst();var level=player.level();
                player.openMenu((StorageTerminalBlockEntity)level.getBlockEntity(start));
            });
            context.waitForScreen(com.tom.storagemod.screen.StorageTerminalScreen.class);
            context.waitFor(client->displayCount(client)==12);
            for(int tick=0;tick<240;tick++){
                context.waitTick();
                context.runOnClient(client->{if(displayCount(client)!=12)throw new AssertionError("Direct connector terminal client count changed while its chest stayed unchanged: "+displayCount(client));});
            }
            world.getServer().runOnServer(server->{
                var level=server.getPlayerList().getPlayers().getFirst().level();
                var terminal=(StorageTerminalBlockEntity)level.getBlockEntity(start);
                var taken=terminal.pullStack(new com.tom.storagemod.inventory.StoredItemStack(new ItemStack(Items.DIAMOND)),64);
                if(taken==null || taken.getQuantity()!=12 || !((ChestBlockEntity)level.getBlockEntity(start.east(2))).isEmpty())throw new AssertionError("Integrated-server terminal extraction did not conserve the twelve real items");
            });
            context.waitFor(client->displayCount(client)==0);
            world.getServer().runOnServer(server->{
                var level=server.getPlayerList().getPlayers().getFirst().level();
                if(!((StorageTerminalBlockEntity)level.getBlockEntity(start)).pushStack(new ItemStack(Items.DIAMOND,7)).isEmpty())throw new AssertionError("Integrated-server reinsertion failed");
            });
            context.waitFor(client->displayCount(client)==7);
            for(int tick=0;tick<60;tick++){context.waitTick();context.runOnClient(client->{if(displayCount(client)!=7)throw new AssertionError("Client count inflated after reinsertion");});}
            PersistentCables.LOG.info("TPC_CLIENT_SMOKE VERIFIED: native direct-connector GUI stayed at 12 for 240 client ticks, cleared after real extraction, then stayed at 7 after reinsertion");
            context.setScreen(()->null);
            world.getServer().runOnServer(server->{
                var level=server.getPlayerList().getPlayers().getFirst().level();((ChestBlockEntity)level.getBlockEntity(start.east(2))).clearContent();
                level.setBlock(start.east(2),VirtualContent.BARREL.defaultBlockState(),3);
            });
            world.getServer().waitFor(server->{var level=server.getPlayerList().getPlayers().getFirst().level();return ((com.tom.storagemod.block.entity.InventoryConnectorBlockEntity)level.getBlockEntity(start.east())).getMergedHandler().getSlotCount()==64 && level.getBlockState(start.east(2)).getValue(TomBarrelBlock.ACTIVE);});
            world.getServer().runOnServer(server->{
                var player=server.getPlayerList().getPlayers().getFirst();var terminal=(StorageTerminalBlockEntity)player.level().getBlockEntity(start);
                if(!terminal.pushStack(new ItemStack(Items.DIAMOND,23)).isEmpty())throw new AssertionError("Virtual client fixture insertion failed");player.openMenu(terminal);
            });
            context.waitForScreen(com.tom.storagemod.screen.StorageTerminalScreen.class);context.waitFor(client->displayCount(client)==23);
            for(int i=0;i<160;i++){context.waitTick();context.runOnClient(client->{if(displayCount(client)!=23)throw new AssertionError("Virtual GUI count changed without a transaction");});}
            context.runOnClient(client->{
                if(client.level.getBlockEntity(start.east(2))!=null || !client.level.getBlockState(start.east(2)).getValue(TomBarrelBlock.ACTIVE))throw new AssertionError("Virtual block state/BE contract was not synchronized to the client");
                for(String name:new String[]{"tom_barrel","tom_barrel_active"})try{
                    var resource=client.getResourceManager().getResource(net.minecraft.resources.Identifier.fromNamespaceAndPath("toms_virtual_storage","textures/block/"+name+".png")).orElseThrow();
                    try(var input=resource.open()){var image=javax.imageio.ImageIO.read(input);if(image.getWidth()!=16 || image.getHeight()!=16)throw new AssertionError("Texture is not native 16x16");}
                }catch(java.io.IOException ex){throw new AssertionError(ex);}
            });
            world.getServer().runOnServer(server->{var level=server.getPlayerList().getPlayers().getFirst().level();var terminal=(StorageTerminalBlockEntity)level.getBlockEntity(start);var taken=terminal.pullStack(new com.tom.storagemod.inventory.StoredItemStack(new ItemStack(Items.DIAMOND)),64);if(taken==null || taken.getQuantity()!=23)throw new AssertionError("Virtual GUI could not drain its actual 23 items");});
            context.waitFor(client->displayCount(client)==0);context.setScreen(()->null);
            PersistentCables.LOG.info("TVSN_CLIENT_SMOKE VERIFIED: original and virtual GUI counts conserved, active block state synchronized, both native 16x16 textures loaded");
            if(net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("toms_storage_additions"))captureReleaseScene(context,world,start);
        }
    }
    private static void captureReleaseScene(ClientGameTestContext context,net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext world,BlockPos start){
        var origin=start.offset(-3,5,10);
        var hopper=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse("toms_storage_additions:curvature_storage_hopper"));
        world.getServer().runOnServer(server->{
            var player=server.getPlayerList().getPlayers().getFirst();var level=player.level();
            for(int x=-8;x<=14;x++)for(int z=-6;z<=14;z++){
                level.setBlock(origin.offset(x,-1,z),Blocks.POLISHED_ANDESITE.defaultBlockState(),3);
                for(int y=0;y<7;y++)level.setBlock(origin.offset(x,y,z),Blocks.AIR.defaultBlockState(),3);
            }
            level.setBlock(origin,Blocks.CHEST.defaultBlockState(),3);
            level.setBlock(origin.east(),Content.connector.get().defaultBlockState(),3);
            level.setBlock(origin.east(2),hopper.defaultBlockState().setValue(AbstractInventoryHopperBlock.FACING,Direction.EAST),3);
            level.setBlock(origin.east(3),Content.invCable.get().defaultBlockState(),3);
            level.setBlock(origin.east(4),Content.connector.get().defaultBlockState(),3);
            for(int x=5;x<=7;x++)for(int z=0;z<=1;z++)level.setBlock(origin.offset(x,0,z),VirtualContent.BARREL.defaultBlockState(),3);
            level.setBlock(origin.offset(3,0,1),Content.craftingTerminal.get().defaultBlockState().setValue(AbstractStorageTerminalBlock.FACING,Direction.SOUTH),3);
            level.setBlock(origin.offset(1,0,4),Content.basicInvHopper.get().defaultBlockState(),3);
            level.setBlock(origin.offset(3,0,4),hopper.defaultBlockState(),3);
            player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
            player.teleportTo(origin.getX()+3.5,origin.getY()+4,origin.getZ()+10.5);
        });
        world.getServer().waitFor(server->{
            var level=server.getPlayerList().getPlayers().getFirst().level();
            return ((com.tom.storagemod.block.entity.InventoryConnectorBlockEntity)level.getBlockEntity(origin.east(4))).getMergedHandler().getSlotCount()==384
                    && level.getBlockState(origin.east(5)).getValue(TomBarrelBlock.ACTIVE);
        });
        context.waitTicks(40);
        context.runOnClient(client->{client.player.setYRot(180);client.player.setXRot(25);client.options.fov().set(55);});
        context.waitTicks(20);
        var shot=context.takeScreenshot(net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions.of("curvature-hopper").withSize(1920,1080));
        PersistentCables.LOG.info("RELEASE_GAME_SCREENSHOT {}",shot.toAbsolutePath());
    }
    private static long displayCount(net.minecraft.client.Minecraft client){
        if(client.player==null || !(client.player.containerMenu instanceof com.tom.storagemod.menu.StorageTerminalMenu menu))return -1;
        return menu.itemListClient.stream().filter(s->s.getStack().is(Items.DIAMOND)).mapToLong(com.tom.storagemod.inventory.StoredItemStack::getQuantity).sum();
    }
}
