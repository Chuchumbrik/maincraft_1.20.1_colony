package ru.wik.colonyboard.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import ru.wik.colonyboard.Net;
import ru.wik.colonyboard.logic.TextWrap;

import java.util.ArrayList;
import java.util.List;

/**
 * Confirms a native build or upgrade. Opening this screen does not queue anything and does not buy resources.
 * The button sends one server action; the server accepts it only after it rechecks the hut.
 */
public class BuildConfirmScreen extends Screen {
    private final Screen parent;
    private final int colonyId;
    private final Net.BuildHutRow hut;
    private boolean sent;

    public BuildConfirmScreen(Screen parent, int colonyId, Net.BuildHutRow hut) {
        super(Component.translatable(hut.kind() == 0 ? "colonyboard.build.start" : "colonyboard.build.upgrade", hut.target()));
        this.parent = parent;
        this.colonyId = colonyId;
        this.hut = hut;
    }

    private boolean canSend() {
        return !sent && !BoardScreen.buildPending(hut.pos()) && (hut.deny() == null || hut.deny().isEmpty());
    }

    private Component name() {
        if (hut.literal() != null && !hut.literal().isBlank()) return Component.literal(hut.literal());
        if (hut.key() != null && !hut.key().isBlank()) return Component.translatable(hut.key());
        return Component.translatable("colonyboard.build.noname");
    }

    private void send() {
        if (!canSend() || minecraft == null) return;
        sent = true;
        BoardScreen.markBuildPending(hut.pos());
        Net.BUILD.sendToServer(new Net.BuildAct(colonyId, hut.pos(), hut.level(), hut.kind()));
        minecraft.setScreen(parent);
    }

    @Override
    public void renderBackground(GuiGraphics g) { Glass.backdrop(g, width, height); }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        int pl = Math.max(8, width / 2 - 180), pr = Math.min(width - 8, width / 2 + 180);
        Glass.panel(g, pl, 8, pr, height - 8, Glass.CYAN);
        int textW = Math.max(40, pr - pl - 24);
        List<String> lines = new ArrayList<>();
        lines.addAll(TextWrap.wrap(title.getString(), textW, font::width));
        lines.add("");
        lines.addAll(TextWrap.wrap(Component.translatable("colonyboard.build.confirm.who", name(), hut.level(), hut.target()).getString(), textW, font::width));
        String nativeKey = hut.nativeType() == 0 ? "colonyboard.build.confirm.native.build"
            : hut.nativeType() == 1 ? "colonyboard.build.confirm.native.upgrade" : "colonyboard.build.confirm.native.unknown";
        lines.addAll(TextWrap.wrap(Component.translatable(nativeKey).getString(), textW, font::width));
        if (hut.kind() == 0 && hut.nativeType() == 1) {
            lines.addAll(TextWrap.wrap(Component.translatable("colonyboard.build.confirm.parent").getString(), textW, font::width));
        }
        lines.addAll(TextWrap.wrap(Component.translatable("colonyboard.build.confirm.notbuy").getString(), textW, font::width));
        if (!canSend() && hut.deny() != null && !hut.deny().isEmpty()) {
            lines.add("");
            lines.addAll(TextWrap.wrap(Component.translatable(hut.deny()).getString(), textW, font::width));
        }
        int y = 20;
        for (String line : lines) {
            if (y > height - 78) break;
            g.drawString(font, line, pl + 12, y, line.isEmpty() ? Glass.MUTED : Glass.TEXT, false);
            y += 10;
        }
        String action = title.getString();
        List<String> buttonLines = TextWrap.wrap(action, textW, font::width);
        if (buttonLines.isEmpty()) buttonLines = List.of(action);
        int bh = buttonLines.size() * 10 + 8;
        int by = height - 18 - bh - 26;
        boolean hover = canSend() && mx >= pl + 12 && mx < pr - 12 && my >= by && my < by + bh;
        g.fill(pl + 12, by, pr - 12, by + bh, Glass.a(hover ? 180 : 120, canSend() ? 0x12301C : 0x2A1A1A));
        Glass.frame(g, pl + 12, by, pr - 12, by + bh, canSend() ? Glass.GREEN : Glass.RED, 140, 90, 70);
        int ty = by + 4;
        for (String line : buttonLines) {
            g.drawString(font, line, pl + 18, ty, canSend() ? Glass.GREEN : Glass.RED, false);
            ty += 10;
        }
        Component cancel = Component.translatable("gui.cancel");
        int cw = font.width(cancel) + 16;
        int cx = pr - 12 - cw, cy = height - 32;
        boolean ch = mx >= cx && mx < cx + cw && my >= cy && my < cy + 18;
        Glass.button(g, font, cancel, cx, cy, cw, 18, Glass.CYAN, true, ch, false);
        super.render(g, mx, my, pt);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int pl = Math.max(8, width / 2 - 180), pr = Math.min(width - 8, width / 2 + 180);
        int textW = Math.max(40, pr - pl - 24);
        List<String> buttonLines = TextWrap.wrap(title.getString(), textW, font::width);
        if (buttonLines.isEmpty()) buttonLines = List.of(title.getString());
        int bh = buttonLines.size() * 10 + 8;
        int by = height - 18 - bh - 26;
        if (canSend() && mx >= pl + 12 && mx < pr - 12 && my >= by && my < by + bh) { send(); return true; }
        Component cancel = Component.translatable("gui.cancel");
        int cw = font.width(cancel) + 16;
        int cx = pr - 12 - cw, cy = height - 32;
        if (mx >= cx && mx < cx + cw && my >= cy && my < cy + 18) { onClose(); return true; }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public void onClose() { if (minecraft != null) minecraft.setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return false; }
}
