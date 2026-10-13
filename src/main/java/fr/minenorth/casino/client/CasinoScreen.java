package fr.minenorth.casino.client;

import fr.minenorth.casino.network.ModNetwork.OpenPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Base des écrans de jeu : cadre, ligne de statut et rangée de mises rapides. */
public abstract class CasinoScreen extends Screen {
    protected final OpenPacket info;
    protected final int w, h;
    protected int left, top;
    protected String status = "";
    protected boolean statusOk;
    /** Mise sélectionnée (centimes). */
    protected long chip;
    private final String title;

    protected CasinoScreen(OpenPacket info, String title, int w, int h) {
        super(Component.literal(title));
        this.info = info;
        this.title = title;
        this.w = w;
        this.h = h;
        this.chip = info.chips().isEmpty() ? info.min() : info.chips().get(0);
    }

    @Override public boolean isPauseScreen() { return false; }

    /** Valeur des jetons du joueur (centimes) ; -1 tant que le serveur ne l'a pas envoyée. */
    protected long held = -1;

    public void held(long cents) { held = cents; }

    /** Message du serveur (erreur ou information). */
    public void message(boolean ok, String msg) { status = msg; statusOk = ok; }

    @Override
    protected void init() {
        left = (width - w) / 2;
        top = (height - h) / 2;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        CasinoStyle.panel(g, left, top, w, h, title, info.company());
        if (held >= 0) {
            String s = "Jetons : " + CasinoStyle.euros(held);
            g.drawString(font, s, left + w - 14 - font.width(s), top + 14, CasinoStyle.GOLD, false);
        }
        super.render(g, mx, my, pt);
    }

    /** Message de statut centré ; un message trop long passe sur deux lignes au lieu de dépasser du cadre. */
    protected void drawStatus(GuiGraphics g, int y) {
        if (status.isBlank()) return;
        var lines = font.split(Component.literal(status), w - 24);
        for (int i = 0; i < Math.min(2, lines.size()); i++) {
            var l = lines.get(i);
            g.drawString(font, l, left + w / 2 - font.width(l) / 2, y + i * 10, statusOk ? CasinoStyle.OK : CasinoStyle.ALERT, false);
        }
    }

    /** Texte raccourci (avec …) s'il dépasse {@code maxWidth}. */
    protected String fit(String s, int maxWidth) {
        return font.width(s) <= maxWidth ? s : font.plainSubstrByWidth(s, maxWidth - font.width("…")) + "…";
    }

    /** Rangée de mises rapides ; la mise sélectionnée est entourée d'or. */
    protected void chipRow(GuiGraphics g, int x, int y, int mx, int my) {
        for (int i = 0; i < info.chips().size(); i++) {
            long c = info.chips().get(i);
            int cx = x + i * 46;
            boolean sel = c == chip;
            if (sel) g.fill(cx - 1, y - 1, cx + 43, y + 15, CasinoStyle.GOLD);
            CasinoStyle.button(g, cx, y, 42, 14, CasinoStyle.euros(c), sel ? CasinoStyle.HOVER : CasinoStyle.DARK_BTN,
                    mx >= cx && mx < cx + 42 && my >= y && my < y + 14, true);
        }
    }

    protected boolean chipClick(int x, int y, double mx, double my) {
        for (int i = 0; i < info.chips().size(); i++) {
            int cx = x + i * 46;
            if (mx >= cx && mx < cx + 42 && my >= y && my < y + 14) { chip = info.chips().get(i); return true; }
        }
        return false;
    }
}
