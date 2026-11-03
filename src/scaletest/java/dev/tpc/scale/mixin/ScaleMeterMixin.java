package dev.tpc.scale.mixin;
import dev.tpc.scale.ScaleRun;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.function.BooleanSupplier;
@Mixin(value=MinecraftServer.class,priority=3000)
public abstract class ScaleMeterMixin {
    @WrapMethod(method="tickServer")
    private void tvsn$measure(BooleanSupplier time,Operation<Void> original){long start=System.nanoTime();String p=ScaleRun.phase,large=dev.tpc.scale.LargeRun.phase;try{original.call(time);}finally{long end=System.nanoTime();ScaleRun.sample(p,start,end);dev.tpc.scale.LargeRun.sample(large,start,end);}}
}
