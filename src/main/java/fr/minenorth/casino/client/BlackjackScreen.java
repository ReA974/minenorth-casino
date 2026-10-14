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
    /** Cartes du croupier réellement affichées : à la fin d'une main elles sortent une par une (la carte cachée d'abord). */
    private List<Integer> dealerShown = new ArrayList<>();
    private static final int DEAL_DELAY = 14;
    private int dealTimer;
    private BjStatePacket revealing;
    private long pendingHeld = Long.MIN_VALUE;
    private CasinoButton deal, hit, stand, dbl;

    public BlackjackScreen(OpenPacket info) { super(info, "Blackjack", 300, 296); }

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
        player = p.player();
        bet = p.bet();
        waiting = false;
        if (phase != 1) dealerShown = new ArrayList<>();   // nouvelle main : table vide
        if (p.phase() == 2 && !p.dealer().equals(dealerShown)) {
            // Fin de main : le croupier retourne sa carte puis tire les autres, une toutes les 0,7 s ; résultat et solde après.
            revealing = p;
            dealer = p.dealer();
            phase = 3;
            canDouble = false;
            status = "";
            dealTimer = DEAL_DELAY;
            return;
        }
        apply(p);
    }

    private void apply(BjStatePacket p) {
        revealing = null;
        phase = p.phase();
        dealer = p.dealer();
        dealerShown = new ArrayList<>(dealer);
        canDouble = p.canDouble();
        payout = p.payout();
        status = p.message();
        statusOk = phase != 2 || payout >= bet;
        flushHeld();
    }

    @Override
    public void held(long cents) {
        if (revealing != null) pendingHeld = cents; else super.held(cents);
    }

    private void flushHeld() {
        if (pendingHeld != Long.MIN_VALUE) { super.held(pendingHeld); pendingHeld = Long.MIN_VALUE; }
    }

    @Override
    public void tick() {
        if (revealing == null || --dealTimer > 0) return;
        dealTimer = DEAL_DELAY;
        int hole = dealerShown.indexOf(-1);
        if (hole >= 0) dealerShown.set(hole, dealer.get(hole));       // la carte cachée se retourne
        else if (dealerShown.size() < dealer.size()) dealerShown.add(dealer.get(dealerShown.size()));
        playCard();
        if (dealerShown.size() >= dealer.size() && !dealerShown.contains(-1)) apply(revealing);
    }

    private void playCard() {
        net.minecraft.client.Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.get(), 1.4f, 0.5f));
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
        deal.enabled(!waiting && revealing == null);
        hit.visible = stand.visible = dbl.visible = playing;
        hit.enabled(!waiting);
        stand.enabled(!waiting);
        dbl.enabled(!waiting && canDouble);
        super.render(g, mx, my, pt);
        g.fill(left + 10, top + 46, left + w - 10, top + 168, CasinoStyle.FELT);
        g.drawString(font, "Croupier" + (dealerShown.isEmpty() ? "" : "  " + dealerTotal()), left + 16, top + 50, CasinoStyle.WHITE, false);
        drawHand(g, dealerShown, left + 16, top + 62);
        g.drawString(font, "Vous" + (player.isEmpty() ? "" : "  " + BlackjackGame.total(player)), left + 16, top + 112, CasinoStyle.WHITE, false);
        drawHand(g, player, left + 16, top + 124);
        if (phase != 0) g.drawString(font, "Mise : " + CasinoStyle.euros(bet), left + w - 100, top + 50, CasinoStyle.GOLD, false);
        if (phase == 2 && payout > 0) g.drawString(font, "Gain : " + CasinoStyle.euros(payout), left + w - 100, top + 62, CasinoStyle.OK, false);
        drawStatus(g, top + 174);
        if (!playing) {
            int tw = w - 24;
            // Quatre lignes d'infos puis la rangée de jetons : chacune à sa place (elles se chevauchaient en GUI x3).
            g.drawString(font, fit("Mises : " + CasinoStyle.euros(info.min()) + " à " + CasinoStyle.euros(info.max()), tw), left + 12, top + 186, CasinoStyle.MUTED, false);
            g.drawString(font, fit("Blackjack paie " + fmt(info.bjGain() + 1) + " pour 1", tw), left + 12, top + 197, CasinoStyle.MUTED, false);
            g.drawString(font, fit("Croupier " + (info.soft17() ? "tire" : "reste") + " sur 17 soft", tw), left + 12, top + 208, CasinoStyle.MUTED, false);
            g.drawString(font, fit("Doublage sur les 2 premières cartes", tw), left + 12, top + 219, CasinoStyle.MUTED, false);
            g.drawString(font, "Jeton", left + 12, top + h - 66, CasinoStyle.MUTED, false);
            chipRow(g, left + 12, top + h - 52, mx, my);
        }
    }

    private static String fmt(float f) { return f == Math.floor(f) ? String.valueOf((int) f) : String.valueOf(f); }

    private String dealerTotal() {
        List<Integer> shown = new ArrayList<>();
        for (int c : dealerShown) if (c >= 0) shown.add(c);
        return String.valueOf(BlackjackGame.total(shown)) + (dealerShown.contains(-1) ? " + ?" : "");
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
        if (phase != 1 && !waiting && revealing == null && chipClick(left + 12, top + h - 52, mx, my)) return true;
        return super.mouseClicked(mx, my, button);
    }
}
