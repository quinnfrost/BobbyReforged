package de.johni0702.minecraft.bobby.mixin;

import de.johni0702.minecraft.bobby.Bobby;
import de.johni0702.minecraft.bobby.BobbyConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.Option;
import net.minecraftforge.common.ForgeConfig;
import net.minecraftforge.fml.loading.FMLConfig;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;

@Mixin(GameOptions.class)
public class GameOptionsMixin {
    @Shadow
    private @Final int viewDistance;

    @Inject(
            method = "<init>",
            at = @At(value = "RETURN")
    )
    private void $GameOptions(MinecraftClient client, File optionsFile, CallbackInfo ci) {
        if (client.is64Bit() && Runtime.getRuntime().maxMemory() >= 1000000000L) {
            Option.RENDER_DISTANCE.setMax(BobbyConfig.getMaxRenderDistance());
            Option.SIMULATION_DISTANCE.setMax(BobbyConfig.getMaxRenderDistance());
        }
    }

}
