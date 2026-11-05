package ru.wik.colonyboard.mixin;

import com.minecolonies.api.colony.requestsystem.requestable.Tool;
import com.minecolonies.api.equipment.registry.EquipmentTypeEntry;
import com.minecolonies.core.colony.buildings.AbstractBuilding;
import com.minecolonies.core.entity.ai.workers.AbstractEntityAIBasic;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.wik.colonyboard.logic.ToolFloor;
import ru.wik.colonyboard.server.ToolTier;

/**
 * Raise minLevel only when a new dig-tool {@link Tool} request is constructed.
 * Inventory checks still use the original minimalLevel, so held tools are not stripped.
 */
@Mixin(value = AbstractEntityAIBasic.class, remap = false)
public abstract class ToolRequestMixin {
    @Shadow @Final public AbstractBuilding building;

    @Redirect(
        method = "checkForToolOrWeapon",
        at = @At(
            value = "NEW",
            target = "(Lcom/minecolonies/api/equipment/registry/EquipmentTypeEntry;Ljava/lang/Integer;Ljava/lang/Integer;)Lcom/minecolonies/api/colony/requestsystem/requestable/Tool;"
        ),
        require = 0
    )
    private Tool colonyboard$raiseSync(EquipmentTypeEntry type, Integer min, Integer max) {
        return colonyboard$newTool(type, min, max);
    }

    @Redirect(
        method = "checkForToolOrWeaponAsync",
        at = @At(
            value = "NEW",
            target = "(Lcom/minecolonies/api/equipment/registry/EquipmentTypeEntry;Ljava/lang/Integer;Ljava/lang/Integer;)Lcom/minecolonies/api/colony/requestsystem/requestable/Tool;"
        ),
        require = 0
    )
    private Tool colonyboard$raiseAsync(EquipmentTypeEntry type, Integer min, Integer max) {
        return colonyboard$newTool(type, min, max);
    }

    private Tool colonyboard$newTool(EquipmentTypeEntry type, Integer min, Integer max) {
        int raised = ToolTier.raise(building, type, min == null ? 0 : min);
        raised = ToolFloor.capToRequestMax(raised, max);
        return new Tool(type, raised, max);
    }
}
