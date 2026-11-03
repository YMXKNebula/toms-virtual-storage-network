package dev.tpc.integration.mixin;
import dev.tpc.integration.ConnectorTestHooks;
import dev.tpc.virtual.PhysicalPeers;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
@Mixin(value=PhysicalPeers.class,remap=false)
public abstract class ConnectorPublicationTestMixin {
    @Redirect(method="get",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/chunk/LevelChunk;getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"))
    private BlockEntity test$publication(LevelChunk chunk,BlockPos pos){return ConnectorTestHooks.hidden.contains(pos)?null:chunk.getBlockEntity(pos);}
}
