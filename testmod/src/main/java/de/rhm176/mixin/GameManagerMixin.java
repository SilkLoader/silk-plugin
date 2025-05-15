package de.rhm176.mixin;

import gameManaging.GameManager;
import org.spongepowered.asm.mixin.Debug;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Debug(export = true)
@Mixin(GameManager.class)
public class GameManagerMixin {
    @Inject(
            method = "init",
            at = @At("HEAD")
    )
    private static void cancelInit(CallbackInfo ci) {
        System.out.println("Hello from an example mixin!");
    }
}
