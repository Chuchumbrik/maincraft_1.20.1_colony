package ru.wik.colonyboard.client;

import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.IColonyView;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.lwjgl.glfw.GLFW;
import ru.wik.colonyboard.Net;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class ClientSetup {
    public static final KeyMapping OPEN = new KeyMapping("key.colonyboard.open", InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_J, "key.categories.colonyboard");
    /** 1.6: act on the newest glass toast. Ctrl+Y / Ctrl+U by default (rebind in Controls). */
    public static final KeyMapping CONFIRM = new KeyMapping("key.colonyboard.toast.confirm", net.minecraftforge.client.settings.KeyConflictContext.IN_GAME,
        net.minecraftforge.client.settings.KeyModifier.CONTROL, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Y, "key.categories.colonyboard");
    public static final KeyMapping OPEN_REQ = new KeyMapping("key.colonyboard.toast.open", net.minecraftforge.client.settings.KeyConflictContext.IN_GAME,
        net.minecraftforge.client.settings.KeyModifier.CONTROL, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_U, "key.categories.colonyboard");
    private static boolean openNextTick;
    /** entity id -> remaining ticks of client-side glow. */
    static final Map<Integer, Integer> GLOW = new HashMap<>();

    private ClientSetup() {}

    public static void init() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener((RegisterKeyMappingsEvent e) -> { e.register(OPEN); e.register(CONFIRM); e.register(OPEN_REQ); });
        FMLJavaModLoadingContext.get().getModEventBus().addListener(ToastOverlay::register);
        MinecraftForge.EVENT_BUS.addListener((RegisterClientCommandsEvent e) ->
            e.getDispatcher().register(Commands.literal("colonyboard").executes(ctx -> { openNextTick = true; return 1; })));
        MinecraftForge.EVENT_BUS.addListener(ClientSetup::tick);
        MinecraftForge.EVENT_BUS.addListener(ToastOverlay::onMouse);
    }

    private static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        while (OPEN.consumeClick()) if (mc.screen == null) openNextTick = true;
        while (CONFIRM.consumeClick()) if (mc.screen == null) ToastOverlay.hotkey(Net.Glass.ACT_CONFIRM);
        while (OPEN_REQ.consumeClick()) if (mc.screen == null) ToastOverlay.hotkey(Net.Glass.ACT_OPEN);
        if (openNextTick) { openNextTick = false; mc.setScreen(new BoardScreen()); }
        Iterator<Map.Entry<Integer, Integer>> it = GLOW.entrySet().iterator();
        while (it.hasNext()) {
            var en = it.next();
            Entity ent = mc.level.getEntity(en.getKey());
            int left = en.getValue() - 1;
            if (ent != null) {
                ent.setGlowingTag(left > 0);
                if (left % 5 == 0) mc.level.addParticle(net.minecraft.core.particles.ParticleTypes.END_ROD,
                    ent.getX(), ent.getY() + ent.getBbHeight() + 0.5, ent.getZ(), 0, 0.05, 0);
            }
            if (left <= 0) it.remove(); else en.setValue(left);
        }
    }

    public static IColonyView findColony() {
        Minecraft mc = Minecraft.getInstance();
        Player p = mc.player;
        if (p == null || mc.level == null) return null;
        IColonyView member = null;
        try {
            IColonyView here = IColonyManager.getInstance().getColonyView(mc.level, p.blockPosition());
            for (IColonyView v : IColonyManager.getInstance().getColonyViews(mc.level)) {
                if (p.getUUID().equals(v.getPermissions().getOwner())) return v;
                if (member == null && v.getPermissions().isColonyMember(p)) member = v;
            }
            if (member == null && here != null && here.getPermissions().isColonyMember(p)) member = here;
        } catch (Throwable ignored) {}
        return member;
    }

    public static boolean serverHasMod() {
        var c = Minecraft.getInstance().getConnection();
        return c != null && Net.CHANNEL.isRemotePresent(c.getConnection());
    }

    /** 1.7: server has the optional dashboard channel (colonyboard 1.7+). */
    public static boolean serverHasDash() {
        var c = Minecraft.getInstance().getConnection();
        return c != null && Net.DASH.isRemotePresent(c.getConnection());
    }

    /** 1.7.1: server has the optional tool-tier channel. */
    public static boolean serverHasTier() {
        var c = Minecraft.getInstance().getConnection();
        return c != null && Net.TIER.isRemotePresent(c.getConnection());
    }

    public static void requestDash(int colonyId) {
        if (serverHasDash()) Net.DASH.sendToServer(new Net.DashRequest(colonyId));
    }

    public static void requestTier(int colonyId) {
        if (serverHasTier()) Net.TIER.sendToServer(new Net.TierRequest(colonyId));
    }

    public static void requestSync(int colonyId) {
        if (serverHasMod()) Net.CHANNEL.sendToServer(new Net.SyncRequest(colonyId));
    }

    /** 1.7.3: placed huts with no work order, and huts that can take a native upgrade. */
    public static boolean serverHasBuild() {
        var c = Minecraft.getInstance().getConnection();
        return c != null && Net.BUILD.isRemotePresent(c.getConnection());
    }

    public static void requestBuild(int colonyId) {
        if (serverHasBuild()) Net.BUILD.sendToServer(new Net.BuildRequest(colonyId));
    }
}
