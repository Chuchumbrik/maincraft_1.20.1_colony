package ru.wik.colonybuildspeed;

import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.placement.AbstractBlueprintIterator;
import com.ldtteam.structurize.placement.StructurePlacer;
import com.ldtteam.structurize.placement.structure.IStructureHandler;
import com.ldtteam.structurize.util.BlueprintPositionInfo;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.items.ModTags;
import com.minecolonies.api.util.Tuple;
import com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure;
import com.minecolonies.core.entity.ai.workers.builder.EntityAIStructureBuilder;
import com.minecolonies.core.entity.ai.workers.util.BuildingProgressStage;
import com.minecolonies.core.entity.ai.workers.util.BuildingStructureHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import ru.wik.colonybuildspeed.mixin.StructureAIAccessor;

/**
 * Placement batch from 1.0.0. CLEAR/REMOVE stay at one step here: a BREAK_BLOCK result
 * aborts {@code executeStructureStep}, so raising this number does not mine several solids.
 */
public final class Chain {
    private static boolean warned = false;

    public static int steps(BuildingStructureHandler<?, ?> handler, AbstractEntityAIStructure<?, ?> ai) {
        try {
            if (!Config.ENABLED.get()) {
                return 1;
            }
            int n = Config.BLOCKS_PER_CYCLE.get();
            if (n <= 1 || ai == null) {
                return 1;
            }
            if (Config.BUILDERS_ONLY.get() && !(ai instanceof EntityAIStructureBuilder)) {
                return 1;
            }
            BuildingProgressStage stage = handler.getStage();
            if (!(stage == BuildingProgressStage.BUILD_SOLID
                || stage == BuildingProgressStage.WEAK_SOLID
                || stage == BuildingProgressStage.DECORATE && Config.DECORATE.get())) {
                return 1;
            }
            Tuple<StructurePlacer, BuildingStructureHandler<?, ?>> tuple = ((StructureAIAccessor) ai).colonybuildspeed$placer();
            if (tuple == null || tuple.getB() != handler) {
                return 1;
            }
            StructurePlacer placer = tuple.getA();
            if (placer == null || placer.getIterator() == null) {
                return 1;
            }
            IStructureHandler ph = placer.getHandler();
            BlockPos local = placer.getIterator().getProgressPos();
            if (local == null || local.equals(AbstractBlueprintIterator.NULL_POS)) {
                return 1;
            }
            Blueprint bp = ph.getBluePrint();
            if (local.getX() < 0 || local.getY() < 0 || local.getZ() < 0
                || local.getX() >= bp.getSizeX() || local.getY() >= bp.getSizeY() || local.getZ() >= bp.getSizeZ()) {
                return 1;
            }
            BlockState state = bp.getBlockState(local);
            if (state == null || state.isAir() || state.hasBlockEntity()) {
                return 1;
            }
            if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                || state.hasProperty(BlockStateProperties.BED_PART)
                || state.hasProperty(BlockStateProperties.CHEST_TYPE)) {
                return 1;
            }
            if (!state.getFluidState().isEmpty()) {
                return 1;
            }
            if (state.is(ModTags.decorationItems) && stage != BuildingProgressStage.DECORATE) {
                return 1;
            }
            ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
            if (key == null || "structurize".equals(key.getNamespace())) {
                return 1;
            }
            BlueprintPositionInfo info = placer.getIterator().getBluePrintPositionInfo(local);
            if (info == null || info.getEntities() != null && info.getEntities().length > 0) {
                return 1;
            }
            if (info.getBlockInfo() != null && info.getBlockInfo().getTileEntityData() != null) {
                return 1;
            }
            BlockPos world = ph.getProgressPosInWorld(local);
            AbstractEntityCitizen w = ai.getWorker();
            if (w == null) {
                return 1;
            }
            double r = Config.REACH.get();
            if (w.position().distanceToSqr(world.getX() + 0.5, world.getY() + 0.5, world.getZ() + 0.5) > r * r) {
                return 1;
            }
            BlockPos feet = w.blockPosition();
            if (world.equals(feet) || world.equals(feet.above())) {
                return 1;
            }
            return n;
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                LogManager.getLogger(ColonyBuildSpeed.ID).warn("colonybuildspeed disabled for this call", t);
            }
            return 1;
        }
    }

    private Chain() {
    }
}
