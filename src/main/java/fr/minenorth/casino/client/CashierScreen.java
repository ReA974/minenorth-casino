package fr.minenorth.casino.client;

import fr.minenorth.casino.network.ModNetwork;
import fr.minenorth.casino.network.ModNetwork.OpenPacket;
import net.minecraft.client.gui.GuiGraphics;

/** Caisse : achat de jetons avec la carte bancaire, rachat de tous les jetons contre de l'argent. Le serveur fait tous les calculs. */
public class CashierScreen extends CasinoScreen {
    private static final int Y_CHIPS = 74;

    public CashierScreen(OpenPacket info) { super(info, "Caisse", 300, 196); }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(new CasinoButton(left + 14, top + 108, 130, 22, "ACHETER", CasinoStyle.GREEN,
                () -> ModNetwork.CHANNEL.sendToServer(new ModNetwork.CashierActionPacket(0, chip))));
        addRenderableWidget(new CasinoButton(left + 156, top + 108, 130, 22, "RACHETER TOUT", CasinoStyle.RED,
                () -> ModNetwork.CHANNEL.sendToServer(new ModNetwork.CashierActionPacket(1, 0))));
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        g.drawString(font, "Achat de jetons (payé par carte bancaire)", left + 14, top + 50, CasinoStyle.GOLD, false);
        g.drawString(font, "Montant", left + 14, top + 62, CasinoStyle.MUTED, false);
        g.drawString(font, CasinoStyle.euros(chip), left + 60, top + 62, CasinoStyle.WHITE, false);
        chipRow(g, left + 14, top + Y_CHIPS, mx, my);
        g.drawString(font, "Le rachat reprend tous vos jetons", left + 14, top + 138, CasinoStyle.MUTED, false);
        g.drawString(font, "et verse leur valeur sur votre compte.", left + 14, top + 149, CasinoStyle.MUTED, false);
        drawStatus(g, top + 172);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (chipClick(left + 14, top + Y_CHIPS, mx, my)) return true;
        return super.mouseClicked(mx, my, button);
    }
}
