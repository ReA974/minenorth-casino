package fr.minenorth.casino.client;

import fr.minenorth.casino.game.BlackjackGame;
import fr.minenorth.casino.network.ModNetwork;
import fr.minenorth.casino.network.ModNetwork.BjActionPacket;
import fr.minenorth.casino.network.ModNetwork.BjStatePacket;
import fr.minenorth.casino.network.ModNetwork.OpenPacket;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/** Blackjack contre le croupier : le serveur garde le sabot, l'écran affiche les cartes reçues (la carte cachée n'est jamais envoyée). */
public class BlackjackScreen extends CasinoScreen {
    private static final String[] RANK = {"A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"};
    private static final String[] SUIT = {"♠", "♥", "♦", "♣"};

    private int phase;   // 0 aucune main, 1 en cours, 2 terminée
    private long bet, payout;
    private List<Integer> player = new ArrayList<>(), dealer = new ArrayList<>();
    private boolean canDouble;
    private boolean waiting;
    private CasinoButton deal, hit, stand, dbl;

    public BlackjackScreen(OpenPacket info) { super(info, "Blackjack", 300, 282); }

    @Override
    protected void init() {
        super.init();
        deal = new CasinoButton(left + 12, top + h - 30, w - 24, 22, "DISTRIBUER", CasinoStyle.GREEN, () -> send(BjActionPacket.DEAL));
        hit = new CasinoButton(left + 12, top + h - 30, 86, 22, "Tirer", CasinoStyle.GREEN, () -> send(BjActionPacket.HIT));
        stand = new CasinoButton(left + 107, top + h - 30, 86, 22, "Rester", CasinoStyle.RED, () -> send(BjActionPacket.STAND));
        dbl = new CasinoButton(left + 202, top + h - 30, 86, 22, "Doubler", CasinoStyle.DARK_BTN, () -> send(BjActionPacket.DOUBLE));
        addRenderableWidget(deal);
        addRenderableWidget(hit);
        addRenderableWidget(stand);
        addRenderableWidget(dbl);
    }

    private void send(int action) {
        if (waiting) return;
        waiting = true;
        status = "";
        ModNetwork.CHANNEL.sendToServer(new BjActionPacket(action, chip));
    }

    public void state(BjStatePacket p) {
        phase = p.phase();
        bet = p.bet();
        player = p.player();
        dealer = p.dealer();
        canDouble = p.canDouble();
        payout = p.payout();
        waiting = false;
        status = p.message();
        statusOk = phase != 2 || payout >= bet;
    }

    @Override
    public void message(boolean ok, String msg) {
        super.message(ok, msg);
        waiting = false;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        boolean playing = phase == 1;
        deal.visible = !playing;
        deal.enabled(!waiting);
        hit.visible = stand.visible = dbl.visible = playing;
        hit.enabled(!waiting);
        stand.enabled(!waiting);
        dbl.enabled(!waiting && canDouble);
        super.render(g, mx, my, pt);
        g.fill(left + 10, top + 46, left + w - 10, top + 168, CasinoStyle.FELT);
        g.drawString(font, "Croupier" + (dealer.isEmpty() ? "" : "  " + dealerTotal()), left + 16, top + 50, CasinoStyle.WHITE, false);
        drawHand(g, dealer, left + 16, top + 62);
        g.drawString(font, "Vous" + (player.isEmpty() ? "" : "  " + BlackjackGame.total(player)), left + 16, top + 112, CasinoStyle.WHITE, false);
        drawHand(g, player, left + 16, top + 124);
        if (phase != 0) g.drawString(font, "Mise : " + CasinoStyle.euros(bet), left + w - 100, top + 50, CasinoStyle.GOLD, false);
        if (phase == 2 && payout > 0) g.drawString(font, "Gain : " + CasinoStyle.euros(payout), left + w - 100, top + 62, CasinoStyle.OK, false);
        drawStatus(g, top + 174);
        if (!playing) {
            g.drawString(font, "Jeton", left + 12, top + h - 66, CasinoStyle.MUTED, false);
            chipRow(g, left + 12, top + h - 52, mx, my);
            g.drawString(font, CasinoStyle.euros(info.min()) + " à " + CasinoStyle.euros(info.max()), left + 12, top + 188, CasinoStyle.MUTED, false);
            g.drawString(font, "Blackjack paie " + fmt(info.bjGain() + 1) + " pour 1 - Croupier " + (info.soft17() ? "tire" : "reste") + " sur 17 soft",
                    left + 12, top + 200, CasinoStyle.MUTED, false);
            g.drawString(font, "Doublage sur les 2 premières cartes", left + 12, top + 212, CasinoStyle.MUTED, false);
        }
    }

    private static String fmt(float f) { return f == Math.floor(f) ? String.valueOf((int) f) : String.valueOf(f); }

    private String dealerTotal() {
        List<Integer> shown = new ArrayList<>();
        for (int c : dealer) if (c >= 0) shown.add(c);
        return String.valueOf(BlackjackGame.total(shown)) + (dealer.contains(-1) ? " + ?" : "");
    }

    private void drawHand(GuiGraphics g, List<Integer> hand, int x, int y) {
        for (int i = 0; i < hand.size(); i++) {
            int cx = x + i * 34;
            int c = hand.get(i);
            g.fill(cx - 1, y - 1, cx + 31, y + 43, CasinoStyle.FRAME);
            if (c < 0) {
                g.fill(cx, y, cx + 30, y + 42, 0xFF22308A);
                g.fill(cx + 4, y + 4, cx + 26, y + 38, 0xFF3A4CC0);
                continue;
            }
            g.fill(cx, y, cx + 30, y + 42, 0xFFF4F4F4);
            boolean red = c / 13 == 1 || c / 13 == 2;
            int col = red ? 0xFFC8102E : 0xFF111111;
            g.drawString(font, RANK[c % 13], cx + 3, y + 3, col, false);
            g.drawString(font, SUIT[c / 13], cx + 3, y + 13, col, false);
            CasinoStyle.scaled(g, net.minecraft.network.chat.Component.literal(SUIT[c / 13]), cx + 11, y + 20, 1.8f, col);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (phase != 1 && !waiting && chipClick(left + 12, top + h - 52, mx, my)) return true;
        return super.mouseClicked(mx, my, button);
    }
}
