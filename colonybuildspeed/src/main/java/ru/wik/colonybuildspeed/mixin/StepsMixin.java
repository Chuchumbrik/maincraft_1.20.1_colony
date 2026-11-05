package ru.wik.colonybuildspeed.mixin;

import com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure;
import com.minecolonies.core.entity.ai.workers.util.BuildingStructureHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.wik.colonybuildspeed.Chain;

@Mixin(value = BuildingStructureHandler.class, remap = false)
public abstract class StepsMixin {
    @Shadow
    @Final
    private AbstractEntityAIStructure<?, ?> structureAI;

    @Inject(method = "getStepsPerCall", at = @At("HEAD"), cancellable = true)
    private void colonybuildspeed$steps(CallbackInfoReturnable<Integer> cir) {
        int n = Chain.steps((BuildingStructureHandler<?, ?>) (Object) this, structureAI);
        if (n > 1) {
            cir.setReturnValue(n);
        }
    }
}
