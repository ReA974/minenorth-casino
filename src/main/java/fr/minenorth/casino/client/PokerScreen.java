package fr.minenorth.casino.client;

import fr.minenorth.casino.game.PokerTable;
import fr.minenorth.casino.network.ModNetwork;
import fr.minenorth.casino.network.ModNetwork.OpenPacket;
import fr.minenorth.casino.network.ModNetwork.PokerStatePacket;
import fr.minenorth.casino.network.ModNetwork.PokerStatePacket.SeatView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.List;

/**
 * Table de Texas Hold'em : tapis ovale, six places, cartes communes au centre. L'écran n'affiche que ce que le serveur envoie
 * (ses propres cartes, celles des autres au showdown) et n'envoie que des intentions (s'asseoir, check, relance…).
 */
public class PokerScreen extends CasinoScreen {
    private static final int[][] SEAT_POS = {{-98, 84}, {-172, 4}, {-98, -78}, {98, -78}, {172, 4}, {98, 84}};
    private static final int BOX_W = 78, BOX_H = 30;

    private PokerStatePacket st;
    private long raiseTo;
    private int lastToAct = -2, lastHand = -1, lastBoard;
    private long stateAt;
    private int timerTotal = 1;
    /** Animations : ticks depuis la donne, et depuis la dernière carte commune retournée. */
    private int dealTicks = 99, flipTicks = 99;
    /** Première carte commune de la dernière série retournée (flop : 3 cartes, tournant et rivière : 1). */
    private int flipFrom;
    private CasinoButton fold, check, raise, allIn, minus, plus, halfPot, potBtn, leave;

    public PokerScreen(OpenPacket info) { super(info, "Poker Texas Hold'em", 440, 302); }

    private int cx() { return left + w / 2; }

    private int cy() { return top + 136; }

    @Override
    protected void init() {
        super.init();
        int y1 = top + h - 50, y2 = top + h - 24;
        fold = add(new CasinoButton(left + 12, y1, 80, 20, "Se coucher", CasinoStyle.RED, () -> act(PokerTable.FOLD)));
        check = add(new CasinoButton(left + 98, y1, 110, 20, "Check", CasinoStyle.BLUE, this::checkOrCall));
        raise = add(new CasinoButton(left + 214, y1, 130, 20, "Relancer", CasinoStyle.GREEN, () -> act(PokerTable.RAISE)));
        allIn = add(new CasinoButton(left + 350, y1, 78, 20, "TAPIS", CasinoStyle.PINK, () -> act(PokerTable.ALLIN)));
        minus = add(new CasinoButton(left + 214, y2, 24, 18, "−", CasinoStyle.DARK_BTN, () -> bump(-1)));
        plus = add(new CasinoButton(left + 320, y2, 24, 18, "+", CasinoStyle.DARK_BTN, () -> bump(1)));
        halfPot = add(new CasinoButton(left + 98, y2, 52, 18, "½ pot", CasinoStyle.DARK_BTN, () -> potRaise(0.5)));
        potBtn = add(new CasinoButton(left + 156, y2, 52, 18, "Pot", CasinoStyle.DARK_BTN, () -> potRaise(1)));
        leave = add(new CasinoButton(left + 350, y2, 78, 18, "Se lever", CasinoStyle.DARK_BTN,
                () -> ModNetwork.CHANNEL.sendToServer(new ModNetwork.PokerActionPacket(11, 0, 0))));
    }

    private CasinoButton add(CasinoButton b) { addRenderableWidget(b); return b; }

    private boolean myTurn() { return st != null && st.mySeat() >= 0 && st.toAct() == st.mySeat(); }

    private void act(int action) {
        if (!myTurn()) return;
        status = "";
        ModNetwork.CHANNEL.sendToServer(new ModNetwork.PokerActionPacket(action, 0, raiseTo));
    }

    private void checkOrCall() { act(st != null && st.toCall() > 0 ? PokerTable.CALL : PokerTable.CHECK); }

    private void bump(int dir) {
        if (st == null) return;
        long step = Math.max(100, st.bb());
        raiseTo = clamp(raiseTo + dir * step);
    }

    private void potRaise(double k) {
        if (st == null) return;
        // relance « au pot » : mise à suivre + fraction du pot après avoir suivi
        long pot = st.pot() + st.toCall();
        long mine = st.mySeat() >= 0 ? st.seats().get(st.mySeat()).bet() : 0;
        raiseTo = clamp(mine + st.toCall() + Math.round(pot * k / 100.0) * 100);
    }

    private long clamp(long v) {
        if (st == null) return v;
        v = v / 100 * 100;
        return Math.max(st.minRaiseTo(), Math.min(st.maxRaiseTo(), v));
    }

    public void state(PokerStatePacket p) {
        boolean newHand = p.hand() != lastHand && p.phase() == PokerTable.Phase.PREFLOP.ordinal();
        if (newHand) { dealTicks = 0; lastBoard = 0; }
        if (p.board().size() > lastBoard) { flipTicks = 0; flipFrom = lastBoard; sound(SoundEvents.BOOK_PAGE_TURN, 1.3f); }
        lastBoard = p.board().size();
        lastHand = p.hand();
        if (p.toAct() != lastToAct) {
            lastToAct = p.toAct();
            timerTotal = Math.max(1, p.seconds());
            if (p.toAct() >= 0 && p.toAct() == p.mySeat()) sound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.6f);
        }
        st = p;
        stateAt = System.currentTimeMillis();
        if (myTurn()) raiseTo = clamp(Math.max(raiseTo, p.minRaiseTo()));
        if (p.phase() == PokerTable.Phase.SHOWDOWN.ordinal() && p.mySeat() >= 0 && p.seats().get(p.mySeat()).won() > 0)
            sound(SoundEvents.PLAYER_LEVELUP, 1.2f);
    }

    private static void sound(net.minecraft.sounds.SoundEvent e, float pitch) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(e, pitch, 0.5f));
    }

    @Override
    public void tick() {
        if (dealTicks < 99) dealTicks++;
        if (flipTicks < 99) flipTicks++;
    }

    private int secondsLeft() {
        if (st == null) return 0;
        return (int) Math.max(0, st.seconds() - (System.currentTimeMillis() - stateAt) / 1000);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        boolean seated = st != null && st.mySeat() >= 0;
        boolean turn = myTurn();
        for (CasinoButton b : new CasinoButton[]{fold, check, raise, allIn, minus, plus, halfPot, potBtn}) { b.visible = turn; b.enabled(turn); }
        leave.visible = seated;
        if (turn) {
            check.setMessage(Component.literal(st.toCall() > 0 ? "Suivre " + CasinoStyle.euros(st.toCall()) : "Check"));
            boolean canRaise = st.maxRaiseTo() > st.currentBet() && st.minRaiseTo() > st.currentBet();
            raise.enabled(canRaise && st.minRaiseTo() < st.maxRaiseTo());
            raise.setMessage(Component.literal((st.currentBet() == 0 ? "Miser " : "Relancer à ") + CasinoStyle.euros(raiseTo)));
            minus.enabled(raiseTo > st.minRaiseTo());
            plus.enabled(raiseTo < st.maxRaiseTo());
        }
        super.render(g, mx, my, pt);
        drawTable(g);
        if (st == null) { CasinoStyle.centered(g, "Connexion à la table…", cx(), cy(), CasinoStyle.MUTED); return; }
        drawBoard(g);
        for (int i = 0; i < PokerTable.SEATS && i < st.seats().size(); i++) drawSeat(g, i, mx, my);
        drawStatus(g, top + h - 66);
        if (!seated) {
            g.drawString(font, "Cave :", left + 12, top + h - 44, CasinoStyle.MUTED, false);
            chipRow(g, left + 50, top + h - 47, mx, my);
            g.drawString(font, fit("Cliquez une place libre pour vous asseoir · blinds " + CasinoStyle.euros(st.sb()) + " / "
                    + CasinoStyle.euros(st.bb()) + " · commission " + fmt(info.bjGain()) + " %", w - 24), left + 12, top + h - 22, CasinoStyle.MUTED, false);
        } else if (turn) {
            g.drawString(font, "À vous !  " + secondsLeft() + " s", left + 12, top + h - 21, CasinoStyle.GOLD, false);
        } else {
            g.drawString(font, fit(st.phase() == 0 ? "En attente de la prochaine main…" : "Blinds " + CasinoStyle.euros(st.sb()) + " / "
                    + CasinoStyle.euros(st.bb()), 320), left + 12, top + h - 21, CasinoStyle.MUTED, false);
        }
    }

    private static String fmt(float f) { return f == Math.floor(f) ? String.valueOf((int) f) : String.valueOf(f); }

    private void drawTable(GuiGraphics g) {
        int cx = cx(), cy = cy();
        CasinoStyle.oval(g, cx, cy + 4, 176, 70, 0x66000000);          // ombre
        CasinoStyle.oval(g, cx, cy, 176, 70, 0xFF2A160B);               // bord en bois
        CasinoStyle.oval(g, cx, cy, 172, 66, 0xFF5A3418);
        CasinoStyle.oval(g, cx, cy, 164, 60, 0xFF1A0F12);               // boudin en cuir
        CasinoStyle.oval(g, cx, cy, 158, 55, 0xFF0B5A3F);               // tapis
        CasinoStyle.oval(g, cx, cy - 4, 140, 44, CasinoStyle.FELT);
        CasinoStyle.oval(g, cx, cy - 8, 110, 30, 0xFF117A55);           // lumière de la suspension
        CasinoStyle.centered(g, "MINENORTH  CASINO", cx, cy + 36, 0x6628FFB0);
    }

    private void drawBoard(GuiGraphics g) {
        int cx = cx(), cy = cy();
        List<Integer> board = st.board();
        int x0 = cx - (5 * 30) / 2;
        for (int i = 0; i < 5; i++) {
            int x = x0 + i * 30, y = cy - 20;
            if (i >= board.size()) {
                CasinoStyle.round(g, x, y, 26, 36, 4, 0x2AFFFFFF);
                continue;
            }
            // les nouvelles cartes se retournent l'une après l'autre
            float flip = i >= flipFrom ? Math.max(0f, Math.min(1f, (flipTicks - (i - flipFrom) * 3) / 5f)) : 1f;
            drawFlipped(g, x, y, board.get(i), 0.85f, flip);
        }
        if (st.pot() > 0) {
            String pot = "Pot : " + CasinoStyle.euros(st.pot());
            int pw = font.width(pot) + 16;
            CasinoStyle.round(g, cx - pw / 2, cy - 36, pw, 13, 6, 0xAA000000);
            CasinoStyle.chip(g, cx - pw / 2 + 7, cy - 30, 4, CasinoStyle.GOLD);
            g.drawString(font, pot, cx - pw / 2 + 13, cy - 33, CasinoStyle.GOLD, false);
        }
        String m = fit(st.message(), 300);
        CasinoStyle.centered(g, m, cx, cy + 22, CasinoStyle.TEXT);
    }

    /** Carte retournée progressivement (largeur de 0 à 1). */
    private void drawFlipped(GuiGraphics g, int x, int y, int card, float s, float flip) {
        if (flip <= 0) return;
        float half = 15 * s;
        g.pose().pushPose();
        g.pose().translate(x + half, 0, 0);
        g.pose().scale(flip, 1, 1);
        g.pose().translate(-(x + half), 0, 0);
        CasinoStyle.playingCard(g, x, y, flip < 0.5f ? -1 : card, s, false);
        g.pose().popPose();
    }

    private void drawSeat(GuiGraphics g, int i, int mx, int my) {
        SeatView s = st.seats().get(i);
        int sx = cx() + SEAT_POS[i][0] - BOX_W / 2, sy = cy() + SEAT_POS[i][1] - BOX_H / 2;
        boolean me = i == st.mySeat();
        if (!s.present()) {
            boolean can = st.mySeat() < 0;
            boolean hov = can && mx >= sx && mx < sx + BOX_W && my >= sy && my < sy + BOX_H;
            CasinoStyle.round(g, sx, sy, BOX_W, BOX_H, 10, hov ? 0xCC3A2468 : 0x88120A26);
            CasinoStyle.centered(g, can ? "S'asseoir" : "Libre", sx + BOX_W / 2, sy + 11, hov ? CasinoStyle.GOLD : CasinoStyle.MUTED);
            return;
        }
        boolean folded = (s.flags() & 2) != 0, allin = (s.flags() & 4) != 0, winner = (s.flags() & 16) != 0;
        boolean acting = i == st.toAct();
        // halo : doré pour le gagnant (pulsé), rose pour celui qui parle, liseré or pour soi
        if (winner) {
            float pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 180.0));
            CasinoStyle.round(g, sx - 3, sy - 3, BOX_W + 6, BOX_H + 6, 13, CasinoStyle.mix(0x66FFC23D, 0xFFFFC23D, pulse));
        } else if (acting) {
            CasinoStyle.round(g, sx - 2, sy - 2, BOX_W + 4, BOX_H + 4, 12, CasinoStyle.PINK);
        } else if (me) {
            CasinoStyle.round(g, sx - 2, sy - 2, BOX_W + 4, BOX_H + 4, 12, CasinoStyle.GOLD);
        }
        CasinoStyle.roundGradient(g, sx, sy, BOX_W, BOX_H, 10, folded ? 0xFF2A2340 : 0xFF3A2468, folded ? 0xFF1A1530 : 0xFF1A1033);
        g.drawString(font, fit(s.name(), BOX_W - 8), sx + 5, sy + 4, folded ? CasinoStyle.MUTED : CasinoStyle.WHITE, false);
        g.drawString(font, CasinoStyle.euros(s.stack()), sx + 5, sy + 16, allin ? CasinoStyle.PINK : CasinoStyle.GOLD, false);
        if (i == st.button()) {
            CasinoStyle.disc(g, sx + BOX_W - 7, sy + 7, 5, CasinoStyle.WHITE);
            g.drawString(font, "D", sx + BOX_W - 9, sy + 3, 0xFF111111, false);
        }
        if (acting && st.phase() != 0) {
            int total = Math.max(1, timerTotal);
            float frac = Math.max(0, Math.min(1, secondsLeft() / (float) total));
            CasinoStyle.round(g, sx + 4, sy + BOX_H - 4, BOX_W - 8, 3, 1, 0x66000000);
            CasinoStyle.round(g, sx + 4, sy + BOX_H - 4, Math.round((BOX_W - 8) * frac), 3, 1, frac < 0.3f ? CasinoStyle.RED : CasinoStyle.CYAN);
        }
        // cartes privées (petites), à côté de la case, vers le centre de la table
        if (s.c1() > -2) {
            boolean showFace = s.c1() >= 0;
            int dir = SEAT_POS[i][0] < 0 ? 1 : -1;
            int kx = dir > 0 ? sx + BOX_W + 3 : sx - 39, ky = sy - 2;
            if (SEAT_POS[i][1] < -40 || SEAT_POS[i][1] > 40) { kx = sx + BOX_W / 2 - 18; ky = SEAT_POS[i][1] < 0 ? sy + BOX_H + 2 : sy - 32; }
            // à la donne, les cartes arrivent une à une (siège par siège)
            int order = Math.floorMod(i - st.button() - 1, PokerTable.SEATS);
            boolean c1in = dealTicks >= order * 2, c2in = dealTicks >= 12 + order * 2;
            float sc = me || showFace ? 0.7f : 0.6f;
            if (c1in) CasinoStyle.playingCard(g, kx, ky, s.c1(), sc, folded);
            if (c2in) CasinoStyle.playingCard(g, kx + 17, ky + 1, s.c2(), sc, folded);
        }
        // dernière action et mise du tour, côté centre
        String line = winner && s.won() > 0 ? "+" + CasinoStyle.euros(s.won()) + (s.hand().isEmpty() ? "" : " · " + s.hand())
                : !s.hand().isEmpty() && !folded ? s.hand() : s.last();
        if (!line.isEmpty()) {
            int lw = font.width(line) + 10, ly = SEAT_POS[i][1] > 40 ? sy - 13 : sy + BOX_H + 2;
            if (SEAT_POS[i][1] < -40 && s.c1() > -2) ly = sy + BOX_H + 30;
            if (Math.abs(SEAT_POS[i][1]) < 40) ly = sy + BOX_H + 2;
            int lx = sx + BOX_W / 2 - lw / 2;
            CasinoStyle.round(g, lx, ly, lw, 11, 5, winner ? 0xDD5A3A00 : 0xBB000000);
            CasinoStyle.centered(g, line, sx + BOX_W / 2, ly + 2, winner ? CasinoStyle.GOLD : folded ? CasinoStyle.MUTED : CasinoStyle.TEXT);
        }
        if (s.bet() > 0) {
            int bx = sx + BOX_W / 2 + (cx() - (sx + BOX_W / 2)) * 35 / 100, by = sy + BOX_H / 2 + (cy() - (sy + BOX_H / 2)) * 45 / 100;
            CasinoStyle.chip(g, bx - 8, by, 4, CasinoStyle.chipColor(s.bet()));
            g.drawString(font, CasinoStyle.euros(s.bet()), bx - 2, by - 4, CasinoStyle.WHITE, true);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (st != null && st.mySeat() < 0) {
            if (chipClick(left + 50, top + h - 47, mx, my)) return true;
            for (int i = 0; i < PokerTable.SEATS && i < st.seats().size(); i++) {
                int sx = cx() + SEAT_POS[i][0] - BOX_W / 2, sy = cy() + SEAT_POS[i][1] - BOX_H / 2;
                if (!st.seats().get(i).present() && mx >= sx && mx < sx + BOX_W && my >= sy && my < sy + BOX_H) {
                    status = "";
                    ModNetwork.CHANNEL.sendToServer(new ModNetwork.PokerActionPacket(10, i, chip));
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (myTurn()) { bump(delta > 0 ? 1 : -1); return true; }
        return super.mouseScrolled(mx, my, delta);
    }

}
