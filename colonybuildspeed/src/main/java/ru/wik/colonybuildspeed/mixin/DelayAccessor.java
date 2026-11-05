package ru.wik.colonybuildspeed.mixin;

import com.minecolonies.core.entity.ai.workers.AbstractEntityAIBasic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = AbstractEntityAIBasic.class, remap = false)
public interface DelayAccessor {
    @Accessor("hasDelayed")
    boolean colonybuildspeed$delayed();

    @Accessor("hasDelayed")
    void colonybuildspeed$delayed(boolean value);
}
