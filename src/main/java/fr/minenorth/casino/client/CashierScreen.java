package fr.minenorth.casino.client;

import fr.minenorth.casino.network.ModNetwork;
import fr.minenorth.casino.network.ModNetwork.OpenPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * Caisse : achat de jetons avec la carte bancaire (montant libre ou raccourcis), rachat de tous les jetons contre de l'argent.
 * Le serveur vérifie tout (montant, limites, solde).
 */
public class CashierScreen extends CasinoScreen {
    private static final int Y_CHIPS = 82;
    private EditBox amount;
    /** Montant saisi (€), conservé si l'écran est reconstruit (redimensionnement). */
    private String typed;

    public CashierScreen(OpenPacket info) {
        super(info, "Caisse", 300, 196);
        typed = String.valueOf(chip / 100);
    }

    @Override
    protected void init() {
        super.init();
        amount = new EditBox(font, left + 90, top + 58, 90, 16, Component.literal("Montant"));
        amount.setMaxLength(6);
        amount.setFilter(s -> s.matches("\\d*"));
        amount.setValue(typed);
        amount.setResponder(v -> typed = v);
        addRenderableWidget(amount);
        addRenderableWidget(new CasinoButton(left + 14, top + 108, 130, 22, "ACHETER", CasinoStyle.GREEN, this::buy));
        addRenderableWidget(new CasinoButton(left + 156, top + 108, 130, 22, "RACHETER TOUT", CasinoStyle.RED,
                () -> ModNetwork.CHANNEL.sendToServer(new ModNetwork.CashierActionPacket(1, 0))));
    }

    private void buy() {
        long euros;
        try {
            euros = Long.parseLong(typed.trim());
        } catch (NumberFormatException e) {
            euros = 0;
        }
        if (euros <= 0) { message(false, "Entrez un montant en euros."); return; }
        ModNetwork.CHANNEL.sendToServer(new ModNetwork.CashierActionPacket(0, euros * 100));
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        g.drawString(font, "Achat de jetons (payé par carte bancaire)", left + 14, top + 46, CasinoStyle.GOLD, false);
        g.drawString(font, "Montant (€)", left + 14, top + 62, CasinoStyle.MUTED, false);
        g.drawString(font, "de " + CasinoStyle.euros(info.min()) + " à " + CasinoStyle.euros(info.max()), left + 188, top + 62, CasinoStyle.MUTED, false);
        chipRow(g, left + 14, top + Y_CHIPS, mx, my);
        g.drawString(font, "Le rachat reprend tous vos jetons", left + 14, top + 138, CasinoStyle.MUTED, false);
        g.drawString(font, "et verse leur valeur sur votre compte.", left + 14, top + 149, CasinoStyle.MUTED, false);
        drawStatus(g, top + 172);
    }

    /** Un raccourci remplit le champ du montant. */
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (chipClick(left + 14, top + Y_CHIPS, mx, my)) {
            typed = String.valueOf(chip / 100);
            amount.setValue(typed);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }
}
