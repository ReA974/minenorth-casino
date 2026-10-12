package fr.minenorth.casino.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Charte graphique MineNorth (fond bleu nuit, boutons plats, comme les autres mods) avec un accent doré pour le casino. */
public final class CasinoStyle {
    public static final int FRAME = 0xFF0E0E10;
    public static final int PANEL = 0xFF161048;
    public static final int LIST = 0xFF0E0A34;
    public static final int HOVER = 0xFF2E2480;
    public static final int DARK_BTN = 0xFF4A3CB4;
    public static final int GOLD = 0xFFE8B923;
    public static final int GREEN = 0xFF1E9E52;
    public static final int RED = 0xFFC8283C;
    public static final int FELT = 0xFF0F6B3A;
    public static final int TEXT = 0xFFCFE3FF;
    public static final int MUTED = 0xFF8FA8E0;
    public static final int WARN = 0xFFFFE066;
    public static final int ALERT = 0xFFFF6A9A;
    public static final int OK = 0xFF6CFF9A;
    public static final int WHITE = 0xFFFFFFFF;
    public static final int DISABLED = 0xFF2A2468;

    private CasinoStyle() {}

    public static int lighten(int c) {
        int r = Math.min(255, ((c >> 16) & 255) + 35), g = Math.min(255, ((c >> 8) & 255) + 35), b = Math.min(255, (c & 255) + 35);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    public static Component bold(String s) { return Component.literal(s).withStyle(net.minecraft.ChatFormatting.BOLD); }

    /** Fond + cadre + titre agrandi + nom de l'entreprise. */
    public static void panel(GuiGraphics g, int left, int top, int w, int h, String title, String subtitle) {
        Font font = Minecraft.getInstance().font;
        g.fill(left - 3, top - 3, left + w + 3, top + h + 3, FRAME);
        g.fill(left, top, left + w, top + h, PANEL);
        g.fill(left, top, left + 4, top + 38, GOLD);
        scaled(g, bold(title), left + 14, top + 8, 1.6f, WHITE);
        if (subtitle != null && !subtitle.isBlank()) g.drawString(font, subtitle, left + 14, top + 25, MUTED, false);
        g.fill(left + 10, top + 40, left + w - 10, top + 41, HOVER);
    }

    public static void scaled(GuiGraphics g, Component c, int x, int y, float s, int color) {
        Font font = Minecraft.getInstance().font;
        g.pose().pushPose();
        g.pose().scale(s, s, 1f);
        g.drawString(font, c, Math.round(x / s), Math.round(y / s), color, false);
        g.pose().popPose();
    }

    public static void button(GuiGraphics g, int x, int y, int w, int h, String label, int color, boolean hover, boolean active) {
        Font f = Minecraft.getInstance().font;
        int bg = !active ? DISABLED : (hover ? lighten(color) : color);
        g.fill(x, y, x + w, y + h, bg);
        g.drawString(f, label, x + w / 2 - f.width(label) / 2, y + (h - 8) / 2, active ? WHITE : MUTED, false);
    }

    public static void item(GuiGraphics g, ItemStack st, int x, int y, float scale) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1);
        g.renderItem(st, 0, 0);
        g.pose().popPose();
    }

    public static void centered(GuiGraphics g, String s, int cx, int y, int color) {
        Font f = Minecraft.getInstance().font;
        g.drawString(f, s, cx - f.width(s) / 2, y, color, false);
    }

    public static String euros(long cents) {
        long a = Math.abs(cents);
        return (cents < 0 ? "-" : "") + (a / 100) + (a % 100 == 0 ? "" : "," + String.format("%02d", a % 100)) + " €";
    }
}
