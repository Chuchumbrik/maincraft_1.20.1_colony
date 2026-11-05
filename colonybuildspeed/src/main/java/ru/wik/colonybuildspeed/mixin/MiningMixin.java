package ru.wik.colonybuildspeed.mixin;

import com.ldtteam.structurize.placement.StructurePlacer;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.entity.ai.statemachine.states.AIWorkerState;
import com.minecolonies.api.entity.ai.statemachine.states.IAIState;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.items.ModTags;
import com.minecolonies.api.util.Tuple;
import com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure;
import com.minecolonies.core.entity.ai.workers.builder.EntityAIStructureBuilder;
import com.minecolonies.api.entity.ai.workers.util.IBuilderUndestroyable;
import com.minecolonies.core.colony.buildings.AbstractBuilding;
import com.minecolonies.core.colony.buildings.AbstractBuildingStructureBuilder;
import com.minecolonies.core.entity.ai.workers.util.BuildingProgressStage;
import com.minecolonies.core.entity.ai.workers.util.BuildingStructureHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.wik.colonybuildspeed.ColonyBuildSpeed;
import ru.wik.colonybuildspeed.Config;
import ru.wik.colonybuildspeed.Demolish;

@Mixin(value = AbstractEntityAIStructure.class, remap = false)
public abstract class MiningMixin {
    @Shadow
    protected BlockPos blockToMine;
    @Shadow
    protected AbstractEntityCitizen worker;
    @Shadow
    protected Level world;
    @Shadow
    public AbstractBuilding building;

    @Shadow
    protected abstract IAIState structureStep();

    @Shadow
    protected abstract boolean mineBlock(BlockPos pos, BlockPos work);

    @Unique
    private int colonybuildspeed$depth;
    @Unique
    private boolean colonybuildspeed$eligible;
    @Unique
    private static boolean colonybuildspeed$warned;

    @Inject(method = "doMining", at = @At("HEAD"))
    private void colonybuildspeed$snap(CallbackInfoReturnable<IAIState> cir) {
        if (colonybuildspeed$depth > 0) {
            return;
        }
        colonybuildspeed$eligible = false;
        try {
            colonybuildspeed$eligible = colonybuildspeed$firstEligible();
        } catch (Throwable t) {
            colonybuildspeed$warn(t);
        }
    }

    @Inject(
        method = "doMining",
        at = @At(
            value = "INVOKE",
            target = "Lcom/minecolonies/api/entity/citizen/AbstractEntityCitizen;decreaseSaturationForContinuousAction()V",
            shift = At.Shift.AFTER
        ),
        cancellable = true
    )
    private void colonybuildspeed$afterBreak(CallbackInfoReturnable<IAIState> cir) {
        if (colonybuildspeed$depth > 0 || !colonybuildspeed$eligible) {
            return;
        }
        int budget;
        try {
            budget = Config.BLOCKS_PER_CYCLE.get();
        } catch (Throwable t) {
            colonybuildspeed$warn(t);
            return;
        }
        try {
            Demolish.Decision decision = Demolish.continueAfterFirst(new Live(), budget);
            if (decision.step != null && decision.step.state instanceof IAIState state) {
                cir.setReturnValue(state);
            } else {
                cir.setReturnValue(AIWorkerState.BUILDING_STEP);
            }
        } catch (Throwable t) {
            colonybuildspeed$warn(t);
            cir.setReturnValue(AIWorkerState.BUILDING_STEP);
        }
    }

    @Unique
    private boolean colonybuildspeed$firstEligible() {
        if (!Config.ENABLED.get() || !Config.DEMOLISH.get()) {
            return false;
        }
        if (Config.BLOCKS_PER_CYCLE.get() <= 1) {
            return false;
        }
        if (Config.BUILDERS_ONLY.get() && !((Object) this instanceof EntityAIStructureBuilder)) {
            return false;
        }
        if (blockToMine == null || worker == null || world == null) {
            return false;
        }
        return colonybuildspeed$view(blockToMine).map(Demolish::blockReason).orElse(Demolish.Reason.UNSAFE) == Demolish.Reason.OK;
    }

    @Unique
    private java.util.Optional<Demolish.BlockView> colonybuildspeed$view(BlockPos pos) {
        try {
            Demolish.BlockView view = new Demolish.BlockView();
            view.enabled = Config.ENABLED.get();
            view.demolish = Config.DEMOLISH.get();
            view.buildersOnly = Config.BUILDERS_ONLY.get();
            view.builder = (Object) this instanceof EntityAIStructureBuilder;
            Tuple<StructurePlacer, BuildingStructureHandler<?, ?>> tuple =
                ((StructureAIAccessor) (Object) this).colonybuildspeed$placer();
            BuildingProgressStage stage = tuple == null || tuple.getB() == null ? null : tuple.getB().getStage();
            view.clearOrRemove = stage == BuildingProgressStage.CLEAR || stage == BuildingProgressStage.REMOVE;
            view.hasWorkOrder = colonybuildspeed$hasOrder();
            view.loaded = world.hasChunkAt(pos);
            if (!view.loaded) {
                return java.util.Optional.of(view);
            }
            BlockState state = world.getBlockState(pos);
            view.air = state.isAir();
            view.fluid = !state.getFluidState().isEmpty() || state.getBlock() instanceof LiquidBlock;
            view.blockEntity = state.hasBlockEntity();
            view.door = state.getBlock() instanceof DoorBlock;
            view.bed = state.getBlock() instanceof BedBlock || state.hasProperty(BlockStateProperties.BED_PART);
            view.chest = state.hasProperty(BlockStateProperties.CHEST_TYPE);
            view.doubleBlock = state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF);
            view.decoration = state.is(ModTags.decorationItems);
            ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
            view.structurize = key != null && "structurize".equals(key.getNamespace());
            view.undestroyable = state.getBlock() instanceof IBuilderUndestroyable
                || state.is(Blocks.BEDROCK)
                || state.is(ModTags.indestructible);
            BlockPos feet = worker.blockPosition();
            view.feet = pos.equals(feet) || pos.equals(feet.above());
            double reach = Config.REACH.get();
            view.inReach = worker.position().distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= reach * reach;
            view.foreignClaim = colonybuildspeed$foreign(pos);
            return java.util.Optional.of(view);
        } catch (Throwable t) {
            colonybuildspeed$warn(t);
            return java.util.Optional.empty();
        }
    }

    @Unique
    private boolean colonybuildspeed$hasOrder() {
        if (!(building instanceof AbstractBuildingStructureBuilder builder)) {
            return false;
        }
        try {
            return builder.getWorkOrder() != null;
        } catch (Throwable t) {
            colonybuildspeed$warn(t);
            return false;
        }
    }

    @Unique
    private boolean colonybuildspeed$foreign(BlockPos pos) {
        IColony owner = worker.getCitizenColonyHandler().getColonyOrRegister();
        if (owner == null) {
            return true;
        }
        IColony at = IColonyManager.getInstance().getColonyByPosFromWorld(world, pos);
        return at != null && at.getID() != owner.getID();
    }

    @Unique
    private static Demolish.Pos colonybuildspeed$pos(BlockPos pos) {
        return pos == null ? null : new Demolish.Pos(pos.getX(), pos.getY(), pos.getZ());
    }

    @Unique
    private Demolish.Pos colonybuildspeed$progress() {
        Tuple<StructurePlacer, BuildingStructureHandler<?, ?>> tuple =
            ((StructureAIAccessor) (Object) this).colonybuildspeed$placer();
        if (tuple == null || tuple.getA() == null || tuple.getA().getIterator() == null) {
            return null;
        }
        return colonybuildspeed$pos(tuple.getA().getIterator().getProgressPos());
    }

    @Unique
    private static Demolish.Signal colonybuildspeed$signal(IAIState state) {
        if (state == AIWorkerState.INVENTORY_FULL) {
            return Demolish.Signal.INVENTORY;
        }
        if (state == AIWorkerState.IDLE) {
            return Demolish.Signal.IDLE;
        }
        if (state == AIWorkerState.NEEDS_ITEM) {
            return Demolish.Signal.NEEDS;
        }
        if (state == AIWorkerState.COMPLETE_BUILD || state == AIWorkerState.LOAD_STRUCTURE) {
            return Demolish.Signal.COMPLETE;
        }
        if (state == AIWorkerState.MINE_BLOCK) {
            return Demolish.Signal.MINE_OR_SAME;
        }
        return Demolish.Signal.OTHER;
    }

    @Unique
    private static void colonybuildspeed$warn(Throwable t) {
        if (!colonybuildspeed$warned) {
            colonybuildspeed$warned = true;
            LogManager.getLogger(ColonyBuildSpeed.ID).warn("demolition batch stopped", t);
        }
    }

    private final class Live implements Demolish.Host {
        @Override
        public int enter() {
            return ++colonybuildspeed$depth;
        }

        @Override
        public void exit() {
            colonybuildspeed$depth = Math.max(0, colonybuildspeed$depth - 1);
            if (colonybuildspeed$depth == 0) {
                ((DelayAccessor) (Object) MiningMixin.this).colonybuildspeed$delayed(false);
            }
        }

        @Override
        public Demolish.Pos progress() {
            return colonybuildspeed$progress();
        }

        @Override
        public Demolish.Pos blockToMine() {
            return colonybuildspeed$pos(blockToMine);
        }

        @Override
        public Demolish.Step structureStep() {
            Demolish.Pos beforeMine = colonybuildspeed$pos(blockToMine);
            Demolish.Pos beforeProgress = colonybuildspeed$progress();
            IAIState state = MiningMixin.this.structureStep();
            Demolish.Pos afterMine = colonybuildspeed$pos(blockToMine);
            Demolish.Pos afterProgress = colonybuildspeed$progress();
            Demolish.StepKind kind = Demolish.classify(
                colonybuildspeed$signal(state), beforeMine, afterMine, beforeProgress, afterProgress);
            return new Demolish.Step(kind, afterMine, afterProgress, state);
        }

        @Override
        public boolean mineNative(Demolish.Pos pos) {
            return mineBlock(new BlockPos(pos.x(), pos.y(), pos.z()), null);
        }

        @Override
        public void satisfyDelay() {
            ((DelayAccessor) (Object) MiningMixin.this).colonybuildspeed$delayed(true);
        }

        @Override
        public void disarmDelay() {
            ((DelayAccessor) (Object) MiningMixin.this).colonybuildspeed$delayed(false);
        }

        @Override
        public Demolish.Reason inspect(Demolish.Pos pos) {
            if (pos == null) {
                return Demolish.Reason.UNSAFE;
            }
            return colonybuildspeed$view(new BlockPos(pos.x(), pos.y(), pos.z()))
                .map(Demolish::blockReason)
                .orElse(Demolish.Reason.UNSAFE);
        }

        @Override
        public boolean gone(Demolish.Pos pos) {
            BlockPos at = new BlockPos(pos.x(), pos.y(), pos.z());
            if (!world.hasChunkAt(at)) {
                return false;
            }
            BlockState state = world.getBlockState(at);
            return state.isAir() || state.getBlock() == Blocks.WATER;
        }

        @Override
        public void afterEachBreak() {
            worker.decreaseSaturationForContinuousAction();
        }
    }
}
