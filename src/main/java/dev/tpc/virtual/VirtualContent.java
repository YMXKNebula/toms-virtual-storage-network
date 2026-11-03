package dev.tpc.virtual;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;

public final class VirtualContent {
    public static final String MOD_ID="toms_virtual_storage";
    private static final Identifier BARREL_ID=Identifier.fromNamespaceAndPath(MOD_ID,"tom_barrel");
    public static final TomBarrelBlock BARREL=new TomBarrelBlock(BlockBehaviour.Properties.of().strength(2.5F).sound(SoundType.WOOD)
            .pushReaction(PushReaction.IMMOVEABLE).setId(ResourceKey.create(Registries.BLOCK,BARREL_ID)));
    public static final Item BARREL_ITEM=new BlockItem(BARREL,new Item.Properties().setId(ResourceKey.create(Registries.ITEM,BARREL_ID)).useBlockDescriptionPrefix());
    public static void register(){
        Registry.register(BuiltInRegistries.BLOCK,BARREL_ID,BARREL);
        Registry.register(BuiltInRegistries.ITEM,BARREL_ID,BARREL_ITEM);
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(entries->entries.accept(BARREL_ITEM));
    }
}
