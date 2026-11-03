package dev.tpc.clienttest.mixin;

import com.mojang.blaze3d.platform.Window;
import org.lwjgl.sdl.SDLVideo;
import com.mojang.renderpearl.api.device.GpuBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapoperation.*;

/** Test-only: create an unfocused hidden test window; never included in the delivered mod. */
@Mixin(Window.class)
public abstract class HiddenWindowMixin {
    @WrapOperation(method="createWindow",at=@At(value="INVOKE",target="Lcom/mojang/renderpearl/api/device/GpuBackend;createWindow(Ljava/lang/String;IIJ)J"))
    private long tpc$hidden(GpuBackend backend,String title,int width,int height,long flags,Operation<Long> original){
        return original.call(backend,title,width,height,flags | SDLVideo.SDL_WINDOW_HIDDEN);
    }
}
