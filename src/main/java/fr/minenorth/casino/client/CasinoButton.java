package fr.minenorth.casino.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/** Bouton plat MineNorth. */
public class CasinoButton extends AbstractButton {
    private final Runnable action;
    private final int color;
    /** Onglet choisi : couleur pleine ; les autres onglets sont grisés. */
    private Boolean selected;

    public CasinoButton(int x, int y, int w, int h, String label, int color, Runnable action) {
        super(x, y, w, h, Component.literal(label));
        this.color = color;
        this.action = action;
    }

    public CasinoButton enabled(boolean on) { this.active = on; return this; }

    /** Bouton utilisé comme onglet : true = onglet ouvert. */
    public CasinoButton selected(boolean on) { this.selected = on; return this; }

    @Override public void onPress() { if (active) action.run(); }

    @Override protected void updateWidgetNarration(NarrationElementOutput out) { defaultButtonNarrationText(out); }

    @Override
    public void renderWidget(GuiGraphics g, int mx, int my, float pt) {
        int c = selected == null || selected ? color : 0xFF3A2E5E;
        CasinoStyle.button(g, getX(), getY(), width, height, getMessage().getString(), c, isHoveredOrFocused(), active);
    }
}
