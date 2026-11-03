package fr.minenorth.casino.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Charte graphique du casino, ambiance « Vice City de nuit » (GTA VI) : panneaux très arrondis bleu-violet nuit, liseré coucher
 * de soleil rose → orange, boutons en pilule, accent doré. Les noms des couleurs sont ceux de l'ancienne charte (tous les écrans
 * en profitent sans changement).
 */
public final class CasinoStyle {
    public static final int FRAME = 0xFF0B0716;
    public static final int PANEL = 0xFF1A1033;
    public static final int LIST = 0xFF120A26;
    public static final int HOVER = 0xFF3A2468;
    public static final int DARK_BTN = 0xFF5B2C93;
    public static final int GOLD = 0xFFFFC23D;
    public static final int GREEN = 0xFF17B26A;
    public static final int RED = 0xFFE0335A;
    public static final int FELT = 0xFF0E6A4C;
    public static final int TEXT = 0xFFF3E9FF;
    public static final int MUTED = 0xFFB4A0D8;
    public static final int WARN = 0xFFFFE066;
    public static final int ALERT = 0xFFFF6A9A;
    public static final int OK = 0xFF6CFFB0;
    public static final int WHITE = 0xFFFFFFFF;
    public static final int DISABLED = 0xFF2B2150;
    public static final int PINK = 0xFFFF3EA5;
    public static final int ORANGE = 0xFFFF8A3D;
    public static final int CYAN = 0xFF2FD4E0;
    public static final int BLUE = 0xFF3D7BFF;

    private CasinoStyle() {}

    public static int lighten(int c) {
        int r = Math.min(255, ((c >> 16) & 255) + 35), g = Math.min(255, ((c >> 8) & 255) + 35), b = Math.min(255, (c & 255) + 35);
        return (c & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    public static int darken(int c, float k) {
        int r = (int) (((c >> 16) & 255) * k), g = (int) (((c >> 8) & 255) * k), b = (int) ((c & 255) * k);
        return (c & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    public static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255, aa = (a >>> 24);
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255, ba = (b >>> 24);
        return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16) | (Math.round(ag + (bg - ag) * t) << 8)
                | Math.round(ab + (bb - ab) * t);
    }

    public static Component bold(String s) { return Component.literal(s).withStyle(net.minecraft.ChatFormatting.BOLD); }

    // ------------------------------------------------------------------ formes arrondies

    /** Retrait horizontal de la ligne {@code dy} (0 = bord) d'un coin de rayon {@code r}. */
    private static int inset(int r, int dy) {
        double y = r - dy - 0.5;
        return (int) Math.round(r - Math.sqrt(Math.max(0, r * r - y * y)));
    }

    /** Rectangle aux coins arrondis (rayon {@code r}, ramené à la moitié du plus petit côté). */
    public static void round(GuiGraphics g, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        for (int dy = 0; dy < r; dy++) {
            int in = inset(r, dy);
            g.fill(x + in, y + dy, x + w - in, y + dy + 1, color);
            g.fill(x + in, y + h - dy - 1, x + w - in, y + h - dy, color);
        }
        g.fill(x, y + r, x + w, y + h - r, color);
    }

    /** Rectangle arrondi en dégradé vertical. */
    public static void roundGradient(GuiGraphics g, int x, int y, int w, int h, int r, int top, int bottom) {
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        for (int dy = 0; dy < h; dy++) {
            int in = dy < r ? inset(r, dy) : dy >= h - r ? inset(r, h - 1 - dy) : 0;
            g.fill(x + in, y + dy, x + w - in, y + dy + 1, mix(top, bottom, h <= 1 ? 0 : dy / (float) (h - 1)));
        }
    }

    /** Bande en dégradé horizontal (liseré coucher de soleil). */
    public static void hGradient(GuiGraphics g, int x, int y, int w, int h, int left, int right) {
        for (int dx = 0; dx < w; dx++) g.fill(x + dx, y, x + dx + 1, y + h, mix(left, right, w <= 1 ? 0 : dx / (float) (w - 1)));
    }

    /** Ellipse pleine (tapis de poker, arène). */
    public static void oval(GuiGraphics g, int cx, int cy, int rx, int ry, int color) {
        for (int dy = -ry; dy < ry; dy++) {
            double t = (dy + 0.5) / ry;
            int half = (int) Math.round(rx * Math.sqrt(Math.max(0, 1 - t * t)));
            g.fill(cx - half, cy + dy, cx + half, cy + dy + 1, color);
        }
    }

    /** Disque (jetons, voyants). */
    public static void disc(GuiGraphics g, int cx, int cy, int r, int color) { oval(g, cx, cy, r, r, color); }

    /** Carte arrondie avec ombre douce et liseré (panneau secondaire). */
    public static void card(GuiGraphics g, int x, int y, int w, int h) {
        round(g, x - 2, y + 1, w + 4, h + 4, 10, 0x55000000);
        round(g, x - 2, y - 2, w + 4, h + 4, 10, FRAME);
        roundGradient(g, x, y, w, h, 8, 0xFF22154A, PANEL);
    }

    /** Fond + cadre arrondi + liseré rose-orange + titre agrandi + nom de l'entreprise. */
    public static void panel(GuiGraphics g, int left, int top, int w, int h, String title, String subtitle) {
        Font font = Minecraft.getInstance().font;
        round(g, left - 4, top + 2, w + 8, h + 8, 14, 0x66000000);   // ombre
        round(g, left - 3, top - 3, w + 6, h + 6, 14, FRAME);
        roundGradient(g, left, top, w, h, 12, 0xFF2A1758, PANEL);
        // reflet du « ciel de Vice City » derrière le titre
        roundGradient(g, left, top, w, 38, 12, 0xFF3B1E6E, 0xFF261650);
        hGradient(g, left + 12, top + 38, w - 24, 2, PINK, ORANGE);
        round(g, left + 10, top + 9, 4, 22, 2, GOLD);
        scaled(g, bold(title), left + 20, top + 8, 1.6f, WHITE);
        if (subtitle != null && !subtitle.isBlank()) g.drawString(font, subtitle, left + 20, top + 25, MUTED, false);
    }

    public static void scaled(GuiGraphics g, Component c, int x, int y, float s, int color) {
        Font font = Minecraft.getInstance().font;
        g.pose().pushPose();
        g.pose().scale(s, s, 1f);
        g.drawString(font, c, Math.round(x / s), Math.round(y / s), color, false);
        g.pose().popPose();
    }

    /** Bouton en pilule, léger dégradé, éclairci au survol. */
    public static void button(GuiGraphics g, int x, int y, int w, int h, String label, int color, boolean hover, boolean active) {
        Font f = Minecraft.getInstance().font;
        int bg = !active ? DISABLED : (hover ? lighten(color) : color);
        int r = Math.min(h / 2, 8);
        round(g, x, y + 1, w, h, r, darken(bg, 0.55f));
        roundGradient(g, x, y, w, h, r, lighten(bg), bg);
        if (hover && active) round(g, x + r, y + 1, w - 2 * r, 1, 0, 0x55FFFFFF);
        g.drawString(f, label, x + w / 2 - f.width(label) / 2, y + (h - 8) / 2 + 1, active ? WHITE : MUTED, false);
    }

    /** Pastille de jeton de casino (bord à encoches blanches). */
    public static void chip(GuiGraphics g, int cx, int cy, int r, int color) {
        disc(g, cx, cy + 1, r, darken(color, 0.5f));
        disc(g, cx, cy, r, color);
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4;
            int px = cx + (int) Math.round(Math.cos(a) * (r - 1.5)), py = cy + (int) Math.round(Math.sin(a) * (r - 1.5));
            g.fill(px - 1, py - 1, px + 1, py + 1, 0xFFF4F4F4);
        }
        disc(g, cx, cy, Math.max(1, r - 3), lighten(color));
    }

    /** Couleur d'un jeton selon sa valeur (comme les objets jeton_1 … jeton_500). */
    public static int chipColor(long cents) {
        long e = cents / 100;
        return e >= 500 ? 0xFF7B2CBF : e >= 100 ? 0xFF1E1E24 : e >= 25 ? 0xFF1E9E52 : e >= 5 ? 0xFFC8283C : 0xFFE8E8EE;
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

    // ------------------------------------------------------------------ cartes à jouer (blackjack, poker)

    private static final String[] RANK = {"A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"};
    private static final String[] SUIT = {"♠", "♥", "♦", "♣"};

    /**
     * Carte arrondie de 30 x 42 (à l'échelle {@code s}) : {@code c} = 0..51, -1 = dos. {@code lift} : décalage vers le haut
     * (carte qui arrive), {@code dim} : grisée (main couchée).
     */
    public static void playingCard(GuiGraphics g, int x, int y, int c, float s, boolean dim) {
        Font font = Minecraft.getInstance().font;
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(s, s, 1);
        round(g, -1, 0, 32, 44, 4, 0x66000000);
        round(g, -1, -1, 32, 44, 4, FRAME);
        if (c < 0) {
            roundGradient(g, 0, 0, 30, 42, 3, 0xFFB0206E, 0xFF5B2C93);
            round(g, 4, 4, 22, 34, 2, 0x33FFFFFF);
            for (int k = 0; k < 4; k++) g.fill(8 + k * 4, 8 + k * 7, 22 - k * 2, 9 + k * 7, 0x55FFC23D);
        } else {
            round(g, 0, 0, 30, 42, 3, 0xFFF7F4FA);
            boolean red = c / 13 == 1 || c / 13 == 2;
            int col = red ? 0xFFD3123A : 0xFF14121C;
            g.drawString(font, RANK[c % 13], 3, 3, col, false);
            g.drawString(font, SUIT[c / 13], 3, 13, col, false);
            scaled(g, Component.literal(SUIT[c / 13]), 11, 20, 1.8f, col);
        }
        if (dim) round(g, 0, 0, 30, 42, 3, 0x99000000);
        g.pose().popPose();
    }
}
