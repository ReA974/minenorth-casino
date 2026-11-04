package fr.minenorth.casino.client;

import com.mojang.math.Axis;
import fr.minenorth.casino.network.ModNetwork;
import fr.minenorth.casino.network.ModNetwork.ArenaStatePacket;
import fr.minenorth.casino.network.ModNetwork.ArenaStatePacket.FighterView;
import fr.minenorth.casino.network.ModNetwork.OpenPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Arène clandestine. Onglets :
 *  - Combat : l'arène animée, les deux coins (cliquer un coin pour choisir son camp), la mise (jetons rapides ou montant libre) ;
 *    l'organisateur y ouvre les paris (durée au choix), lance ou annule le combat ;
 *  - Combattants : poulailler (arène des coqs) ou animaux inscrits (grande arène) ; l'organisateur place qui il veut dans chaque coin ;
 *  - Mon animal (grande arène) : inscription et entraînement de son animal ;
 *  - Organisation (organisateur) : arène ouverte / bâchée, commission, commissions touchées.
 * Tout est décidé et revérifié par le serveur ; l'écran rejoue chaque échange qu'il reçoit.
 */
public class CockfightScreen extends CasinoScreen {
    private static final int RED_SIDE = 0xFFE0335A, BLUE_SIDE = 0xFF3D7BFF;
    private static final int TAB_COMBAT = 0, TAB_ROSTER = 1, TAB_PET = 2, TAB_ORG = 3;

    private ArenaStatePacket st;
    private int tab;
    /** Camp choisi pour parier (0 rouge, 1 bleu, -1 aucun). */
    private int betSide = -1;
    private int lastSeq = -1;
    private int animTicks = 99, animAttacker = -1;
    private boolean animCrit;
    private int ticks;
    private final List<float[]> feathers = new ArrayList<>();   // x, y, vx, vy, vie
    private final Random rnd = new Random();
    private float shownA = -1, shownB = -1;

    private final List<CasinoButton> tabs = new ArrayList<>();
    private CasinoButton betBtn, raid, chooseBtn, startBtn, clearBtn, rerollBtn, clearPicks, register, toggleOpen, comMinus, comPlus;
    private final List<CasinoButton> durBtns = new ArrayList<>(), trainBtns = new ArrayList<>();
    private EditBox amount;
    private String typed = "";

    public CockfightScreen(OpenPacket info) { super(info, "Arène clandestine", 440, 330); }

    private int cx() { return left + w / 2; }

    private int cy() { return top + 106; }

    private boolean owner() { return st != null && st.owner(); }

    @Override
    protected void init() {
        super.init();
        tabs.clear(); durBtns.clear(); trainBtns.clear();
        String[] names = {"Combat", "Combattants", "Mon animal", "Organisation"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            tabs.add(add(new CasinoButton(left + 12 + i * 94, top + 44, 90, 14, names[i], CasinoStyle.PINK, () -> { tab = idx; status = ""; })));
        }
        int by = top + h - 28, half = (w - 30) / 2;
        // onglet Combat
        betBtn = add(new CasinoButton(left + 12, by, half, 20, "Miser", CasinoStyle.GREEN, this::bet));
        raid = add(new CasinoButton(left + 12, by, w - 24, 20, "🚨 DÉMANTELER L'ARÈNE (saisir les paris)", 0xFF1F4FD1, () -> send(1, 0, 0)));
        int ox = left + 18 + half;
        chooseBtn = add(new CasinoButton(ox, by, half, 20, "Choisir les combattants", CasinoStyle.ORANGE, () -> tab = TAB_ROSTER));
        startBtn = add(new CasinoButton(ox, by, half - 74, 20, "▶ Lancer le combat", CasinoStyle.ORANGE, () -> send(12, 0, 0)));
        clearBtn = add(new CasinoButton(ox + half - 70, by, 70, 20, "Annuler", CasinoStyle.RED, () -> send(13, 0, 0)));
        amount = new EditBox(font, left + w - 82, top + 263, 70, 16, Component.literal("Montant"));
        amount.setMaxLength(6);
        amount.setFilter(s -> s.matches("\\d*"));
        amount.setHint(Component.literal("Montant €"));
        amount.setValue(typed);
        amount.setResponder(v -> typed = v);
        addRenderableWidget(amount);
        // onglet Combattants
        clearPicks = add(new CasinoButton(left + 12, by, half, 20, "Vider les deux coins", CasinoStyle.RED, () -> send(13, 0, 0)));
        rerollBtn = add(new CasinoButton(ox, by, half, 20, "Nouveaux coqs au poulailler", CasinoStyle.DARK_BTN, () -> send(16, 0, 0)));
        // onglet Mon animal
        register = add(new CasinoButton(left + 12, by, w - 24, 20, "Inscrire mon animal", CasinoStyle.GREEN, () -> send(st.pet().registered() ? 3 : 2, 0, 0)));
        for (int i = 0; i < 3; i++) {
            final int stat = i;
            trainBtns.add(add(new CasinoButton(left + w - 150, top + 150 + i * 24, 132, 18, "Entraîner", CasinoStyle.PINK, () -> send(4, stat, 0))));
        }
        // onglet Organisation
        toggleOpen = add(new CasinoButton(left + 24, top + 94, w - 48, 20, "Arène", CasinoStyle.GREEN, () -> send(14, 0, 0)));
        comMinus = add(new CasinoButton(left + 200, top + 130, 24, 18, "−", CasinoStyle.DARK_BTN, () -> commission(-1)));
        comPlus = add(new CasinoButton(left + 286, top + 130, 24, 18, "+", CasinoStyle.DARK_BTN, () -> commission(1)));
    }

    private CasinoButton add(CasinoButton b) { addRenderableWidget(b); return b; }

    private void send(int action, int a, long b) {
        status = "";
        ModNetwork.CHANNEL.sendToServer(new ModNetwork.ArenaActionPacket(action, a, b));
    }

    private void commission(int delta) {
        if (st == null) return;
        float v = Math.max(0, Math.min(st.commissionMax(), Math.round(st.commission()) + delta));
        send(15, Math.round(v * 10), 0);
    }

    /** Mise choisie (centimes) : le montant tapé, sinon le jeton sélectionné. */
    private long stake() {
        try { if (!typed.isBlank()) return Long.parseLong(typed.trim()) * 100; } catch (NumberFormatException ignored) {}
        return chip;
    }

    private void bet() {
        if (betSide < 0) { message(false, "Cliquez d'abord sur le coin rouge ou le coin bleu."); return; }
        send(0, betSide, stake());
    }

    public void state(ArenaStatePacket p) {
        if (p.seq() >= 0 && p.seq() != lastSeq && p.attacker() >= 0) {
            lastSeq = p.seq();
            animTicks = 0;
            animAttacker = p.attacker();
            animCrit = p.crit();
            if (p.damage() > 0) {
                int dx = cx() + (p.attacker() == 0 ? 34 : -34), dy = cy() - 4;
                for (int k = 0; k < (p.crit() ? 14 : 6); k++)
                    feathers.add(new float[]{dx, dy, (rnd.nextFloat() - 0.5f) * 3f, -rnd.nextFloat() * 2.5f, 20 + rnd.nextInt(15)});
            }
        }
        if (st != null && st.phase() != 4 && p.phase() == 4 && st.mineA() + st.mineB() > 0) {
            boolean won = (p.winner() == 0 && p.mineA() > 0) || (p.winner() == 1 && p.mineB() > 0);
            sound(won ? SoundEvents.PLAYER_LEVELUP : SoundEvents.VILLAGER_NO, 1f);
        }
        if (st != null && st.phase() != 2 && p.phase() == 2) sound(SoundEvents.NOTE_BLOCK_BELL.value(), 1.4f);
        if (st == null || p.phase() <= 2) { shownA = p.hpA(); shownB = p.hpB(); }
        if (p.mineA() > 0) betSide = 0; else if (p.mineB() > 0) betSide = 1; else if (p.phase() <= 1) betSide = -1;
        st = p;
        held = -1;   // ici on parie en espèces : pas d'en-tête « Jetons »
        if (!p.owner() && tab == TAB_ORG) tab = TAB_COMBAT;
        if (!p.animals() && tab == TAB_PET) tab = TAB_COMBAT;
    }

    private static void sound(net.minecraft.sounds.SoundEvent e, float pitch) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(e, pitch, 0.5f));
    }

    @Override
    public void held(long cents) { }

    @Override
    public void tick() {
        ticks++;
        if (animTicks < 99) animTicks++;
        for (Iterator<float[]> it = feathers.iterator(); it.hasNext(); ) {
            float[] f = it.next();
            f[0] += f[2]; f[1] += f[3]; f[3] += 0.15f; f[2] *= 0.95f;
            if (--f[4] <= 0) it.remove();
        }
        if (st != null) {
            shownA += (st.hpA() - shownA) * 0.35f;
            shownB += (st.hpB() - shownB) * 0.35f;
        }
    }

    // ------------------------------------------------------------------ rendu

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        layoutWidgets();
        super.render(g, mx, my, pt);
        if (st == null) { CasinoStyle.centered(g, "…", cx(), cy(), CasinoStyle.MUTED); return; }
        String cash = "Espèces : " + CasinoStyle.euros(st.cash());
        g.drawString(font, cash, left + w - 14 - font.width(cash), top + 14, CasinoStyle.GOLD, false);
        String org = st.owner() ? "Vous organisez cette arène" : "Organisée par " + (st.ownerName().isEmpty() ? "la maison" : st.ownerName());
        g.drawString(font, fit(org, 200), left + w - 14 - Math.min(200, font.width(org)), top + 26, st.owner() ? CasinoStyle.PINK : CasinoStyle.MUTED, false);
        switch (tab) {
            case TAB_ROSTER -> drawRoster(g, mx, my);
            case TAB_PET -> drawPet(g);
            case TAB_ORG -> drawOrg(g);
            default -> drawCombat(g, mx, my, pt);
        }
    }

    /** Visibilité et libellés des boutons selon l'onglet, la phase et le rôle. */
    private void layoutWidgets() {
        boolean ok = st != null;
        boolean own = owner(), cop = ok && st.police(), animals = ok && st.animals();
        int phase = ok ? st.phase() : 0;
        tabs.get(TAB_PET).visible = animals;
        tabs.get(TAB_ORG).visible = own;
        // onglets resserrés : seulement ceux qui existent
        int x = left + 12;
        for (int i = 0; i < 4; i++) {
            CasinoButton t = tabs.get(i);
            t.selected(tab == i);
            if (t.visible) { t.setX(x); x += 94; }
        }
        boolean combat = tab == TAB_COMBAT, roster = tab == TAB_ROSTER, pet = tab == TAB_PET, orgTab = tab == TAB_ORG;
        int half = (w - 30) / 2;
        betBtn.visible = combat && ok && !cop;
        betBtn.setWidth(own ? half : w - 24);
        amount.visible = combat && ok && !cop;
        raid.visible = combat && cop;
        chooseBtn.visible = combat && own && phase == 0;
        startBtn.visible = clearBtn.visible = combat && own && phase == 2;
        for (CasinoButton b : durBtns) b.visible = combat && own && phase == 1;
        if (combat && own && phase == 1 && durBtns.isEmpty() && !st.durations().isEmpty()) {
            // boutons « Paris 30 s » « 60 s »… créés à la première réception (la liste vient de la config du serveur)
            int n = st.durations().size(), bw = (half - (n - 1) * 4) / n, bx = left + 18 + half;
            for (int i = 0; i < n; i++) {
                int secs = st.durations().get(i);
                durBtns.add(add(new CasinoButton(bx + i * (bw + 4), top + h - 28, bw, 20, (i == 0 ? "Paris " : "") + secs + " s", CasinoStyle.ORANGE,
                        () -> send(11, secs, 0))));
            }
        }
        if (ok) {
            betBtn.enabled(phase == 2 && betSide >= 0);
            String who = betSide == 0 ? st.a().name() : betSide == 1 ? st.b().name() : "";
            betBtn.setMessage(Component.literal(phase != 2 ? (phase == 3 ? "Combat en cours" : "Paris fermés")
                    : betSide < 0 ? "Choisissez un coin" : fit("Miser " + CasinoStyle.euros(stake()) + " sur " + who, (own ? half : w - 24) - 10)));
        }
        clearPicks.visible = roster && own && (phase == 0 || phase == 1);
        rerollBtn.visible = roster && own && !animals && (phase == 0 || phase == 1);
        register.visible = pet && ok && st.pet().present();
        for (CasinoButton b : trainBtns) b.visible = pet && ok && st.pet().present();
        if (pet && ok && st.pet().present()) {
            var p = st.pet();
            register.setMessage(Component.literal(p.registered() ? "Retirer " + p.name() + " de l'arène" : "Inscrire " + p.name() + " à l'arène"));
            int[] v = {p.force(), p.agility(), p.stamina()};
            for (int i = 0; i < 3; i++) {
                boolean max = v[i] >= p.statMax();
                trainBtns.get(i).setMessage(Component.literal(max ? "Au maximum" : "+1 · " + CasinoStyle.euros(p.trainUnit() * Math.max(1, v[i]))));
                trainBtns.get(i).enabled(!max && p.trainWait() == 0);
            }
        }
        toggleOpen.visible = comMinus.visible = comPlus.visible = orgTab && own;
        if (orgTab && ok) toggleOpen.setMessage(Component.literal(st.open() ? "Arène OUVERTE · cliquer pour la bâcher" : "Arène BÂCHÉE · cliquer pour l'ouvrir"));
    }

    // ------------------------------------------------------------------ onglet Combat

    private void drawCombat(GuiGraphics g, int mx, int my, float pt) {
        drawArena(g, pt);
        boolean ready = !st.a().name().isEmpty();
        if (!ready) {
            CasinoStyle.card(g, left + 12, top + 156, w - 24, 46);
            CasinoStyle.centered(g, st.owner() ? "Choisissez vos deux combattants (onglet Combattants)."
                    : "L'organisateur choisit les combattants du prochain combat…", cx(), top + 166, CasinoStyle.TEXT);
            CasinoStyle.centered(g, fit(st.message(), w - 40), cx(), top + 182, CasinoStyle.MUTED);
        } else {
            corner(g, 0, left + 12, top + 152, mx, my);
            corner(g, 1, left + w / 2 + 3, top + 152, mx, my);
            CasinoStyle.round(g, left + 12, top + 214, w - 24, 14, 7, 0x99000000);
            CasinoStyle.centered(g, fit(st.message(), w - 36), cx(), top + 217, CasinoStyle.TEXT);
            for (int i = 0; i < st.bets().size() && i < 4; i++) {
                String row = st.bets().get(i);
                int bar = row.lastIndexOf('|');
                if (bar < 1) continue;
                int x = left + 14 + (i % 2) * (w / 2 - 4), y = top + 232 + (i / 2) * 10;
                CasinoStyle.disc(g, x + 3, y + 4, 3, row.charAt(0) == 'R' ? RED_SIDE : BLUE_SIDE);
                g.drawString(font, fit(row.substring(1, bar), 130) + "  " + CasinoStyle.euros(parse(row.substring(bar + 1))), x + 9, y, CasinoStyle.MUTED, false);
            }
        }
        if (st.police()) {
            CasinoStyle.centered(g, "Vous êtes en service : vous pouvez démanteler l'arène.", cx(), top + 266, CasinoStyle.CYAN);
        } else {
            g.drawString(font, "Mise :", left + 12, top + 267, CasinoStyle.MUTED, false);
            chipRow(g, left + 46, top + 264, mx, my);
        }
        drawStatus(g, top + h - 48);
    }

    /** Un coin du ring : fiche du combattant, cote, mises ; cliquer dessus le choisit pour parier. */
    private void corner(GuiGraphics g, int side, int x, int y, int mx, int my) {
        int ww = w / 2 - 15, hh = 56;
        FighterView f = side == 0 ? st.a() : st.b();
        int col = side == 0 ? RED_SIDE : BLUE_SIDE;
        boolean win = st.phase() == 4 && st.winner() == side;
        boolean sel = betSide == side;
        boolean hov = st.phase() == 2 && mx >= x && mx < x + ww && my >= y && my < y + hh;
        if (win) CasinoStyle.round(g, x - 3, y - 3, ww + 6, hh + 6, 11, CasinoStyle.GOLD);
        else if (sel) CasinoStyle.round(g, x - 2, y - 2, ww + 4, hh + 4, 10, col);
        CasinoStyle.roundGradient(g, x, y, ww, hh, 9, CasinoStyle.mix(col, 0xFF1A1033, hov ? 0.35f : 0.55f), 0xFF1A1033);
        CasinoStyle.round(g, x + 6, y + 5, 36, 10, 5, col);
        CasinoStyle.centered(g, side == 0 ? "ROUGE" : "BLEU", x + 24, y + 6, CasinoStyle.WHITE);
        g.drawString(font, fit(f.name(), ww - 100), x + 46, y + 6, CasinoStyle.WHITE, false);
        String od = String.format(java.util.Locale.ROOT, "cote x%.2f", odds(side));
        g.drawString(font, od, x + ww - 6 - font.width(od), y + 6, CasinoStyle.GOLD, false);
        stat(g, x + 6, y + 19, "FOR", f.force(), col);
        stat(g, x + 6 + (ww - 12) / 3, y + 19, "AGI", f.agility(), col);
        stat(g, x + 6 + 2 * (ww - 12) / 3, y + 19, "END", f.stamina(), col);
        long pool = side == 0 ? st.poolA() : st.poolB(), mine = side == 0 ? st.mineA() : st.mineB();
        g.drawString(font, fit("Misé " + CasinoStyle.euros(pool) + (mine > 0 ? " · vous " + CasinoStyle.euros(mine) : ""), ww - 10), x + 6, y + 31,
                mine > 0 ? CasinoStyle.OK : CasinoStyle.MUTED, false);
        String who = f.owner().isEmpty() ? f.desc() + (f.wins() + f.losses() > 0 ? " · " + f.wins() + " V / " + f.losses() + " D" : "")
                : f.desc() + " de " + f.owner();
        g.drawString(font, fit(who, ww - 10), x + 6, y + 43, f.owner().isEmpty() ? CasinoStyle.MUTED : CasinoStyle.CYAN, false);
    }

    private static long parse(String s) { try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; } }

    /** Cote du pari mutuel pour un euro misé sur {@code side} (mise comprise) ; avant tout pari, la cote théorique. */
    private double odds(int side) {
        double c = st.commission() / 100.0;
        long mine = side == 0 ? st.poolA() : st.poolB(), other = side == 0 ? st.poolB() : st.poolA();
        if (mine > 0) return 1 + other * (1 - c) / mine;
        double p = Math.max(0.05, Math.min(0.95, side == 0 ? st.pA() : 1 - st.pA()));
        return 1 + (1 - c) * (1 - p) / p;
    }

    private void stat(GuiGraphics g, int x, int y, String label, int v, int col) {
        g.drawString(font, label, x, y, CasinoStyle.MUTED, false);
        int bx = x + font.width(label) + 3;
        for (int i = 0; i < 9; i++) CasinoStyle.round(g, bx + i * 4, y + 1, 3, 6, 1, i < v ? col : 0x44FFFFFF);
    }

    // ------------------------------------------------------------------ onglet Combattants

    private static final int CARD_W = 204, CARD_H = 44;

    private int[] cardPos(int i) { return new int[]{left + 12 + (i % 2) * (CARD_W + 8), top + 64 + (i / 2) * (CARD_H + 6)}; }

    private void drawRoster(GuiGraphics g, int mx, int my) {
        List<FighterView> r = st.roster();
        if (r.isEmpty()) {
            CasinoStyle.card(g, left + 12, top + 70, w - 24, 60);
            CasinoStyle.centered(g, st.animals() ? "Aucun animal inscrit pour l'instant." : "Le poulailler est vide.", cx(), top + 86, CasinoStyle.TEXT);
            CasinoStyle.centered(g, st.animals() ? "Les joueurs inscrivent leur animal dans l'onglet « Mon animal »." : "", cx(), top + 102, CasinoStyle.MUTED);
        }
        boolean canPick = st.owner() && (st.phase() == 0 || st.phase() == 1);
        for (int i = 0; i < r.size() && i < 8; i++) {
            int[] p = cardPos(i);
            int x = p[0], y = p[1];
            FighterView f = r.get(i);
            boolean red = st.pickA() == i, blue = st.pickB() == i;
            if (red || blue) CasinoStyle.round(g, x - 2, y - 2, CARD_W + 4, CARD_H + 4, 10, red ? RED_SIDE : BLUE_SIDE);
            CasinoStyle.roundGradient(g, x, y, CARD_W, CARD_H, 9, 0xFF2E1F55, 0xFF1A1033);
            CasinoStyle.disc(g, x + 12, y + 12, 6, 0xFF000000 | f.color());
            g.drawString(font, fit(f.name(), CARD_W - (canPick ? 110 : 40)), x + 22, y + 5, CasinoStyle.WHITE, false);
            String sub = f.owner().isEmpty() ? f.desc() : f.desc() + " · " + f.owner();
            g.drawString(font, fit(sub, CARD_W - (canPick ? 110 : 40)), x + 22, y + 16, f.owner().isEmpty() ? CasinoStyle.MUTED : CasinoStyle.CYAN, false);
            int col = red ? RED_SIDE : blue ? BLUE_SIDE : CasinoStyle.PINK;
            stat(g, x + 6, y + 30, "F", f.force(), col);
            stat(g, x + 54, y + 30, "A", f.agility(), col);
            stat(g, x + 102, y + 30, "E", f.stamina(), col);
            String rec = f.resting() ? "au repos" : f.wins() + " V · " + f.losses() + " D";
            g.drawString(font, rec, x + CARD_W - 6 - font.width(rec), y + 30, f.resting() ? CasinoStyle.ORANGE : CasinoStyle.GOLD, false);
            if (canPick) {
                pickButton(g, x + CARD_W - 86, y + 4, "Rouge", RED_SIDE, red, mx, my);
                pickButton(g, x + CARD_W - 44, y + 4, "Bleu", BLUE_SIDE, blue, mx, my);
            } else if (red || blue) {
                String t = red ? "ROUGE" : "BLEU";
                CasinoStyle.round(g, x + CARD_W - 46, y + 4, 40, 11, 5, red ? RED_SIDE : BLUE_SIDE);
                CasinoStyle.centered(g, t, x + CARD_W - 26, y + 6, CasinoStyle.WHITE);
            }
        }
        String hint = !st.owner() ? (st.animals() ? "L'organisateur choisit deux inscrits pour chaque combat." : "L'organisateur choisit deux coqs du poulailler.")
                : st.phase() >= 2 ? "Combat lancé : les choix sont figés jusqu'à la fin."
                : st.pickA() >= 0 && st.pickB() >= 0 ? "Les deux coins sont pris : ouvrez les paris dans l'onglet Combat."
                : "Placez un combattant dans le coin rouge et un dans le coin bleu.";
        CasinoStyle.centered(g, fit(hint, w - 30), cx(), top + h - 58, CasinoStyle.MUTED);
        drawStatus(g, top + h - 46);
    }

    private void pickButton(GuiGraphics g, int x, int y, String label, int color, boolean on, int mx, int my) {
        boolean hov = mx >= x && mx < x + 40 && my >= y && my < y + 13;
        CasinoStyle.button(g, x, y, 40, 13, label, on ? color : CasinoStyle.mix(color, 0xFF1A1033, 0.55f), hov, true);
    }

    // ------------------------------------------------------------------ onglet Mon animal

    private static String mmss(int sec) { return sec >= 60 ? (sec / 60) + " min" : sec + " s"; }

    private void drawPet(GuiGraphics g) {
        int x = left + 12, y = top + 64, ww = w - 24;
        CasinoStyle.card(g, x, y, ww, 196);
        var p = st.pet();
        if (!p.present()) {
            CasinoStyle.centered(g, "Amenez votre animal apprivoisé tout près de vous", cx(), y + 70, CasinoStyle.TEXT);
            CasinoStyle.centered(g, "(chien, chat, animal de compagnie…) pour l'inscrire ou l'entraîner.", cx(), y + 84, CasinoStyle.MUTED);
            drawStatus(g, top + h - 46);
            return;
        }
        g.pose().pushPose();
        g.pose().translate(x + 40, y + 52, 0);
        g.pose().scale(1.4f, 1.4f, 1);
        drawAnimal(g, 0xFF000000 | (0x404040 | (p.species().hashCode() & 0xBFBFBF)), false);
        g.pose().popPose();
        CasinoStyle.scaled(g, CasinoStyle.bold(fit(p.name(), 200)), x + 80, y + 12, 1.3f, CasinoStyle.WHITE);
        g.drawString(font, fit(p.species(), 220), x + 80, y + 28, CasinoStyle.CYAN, false);
        g.drawString(font, p.wins() + " victoire(s) · " + p.losses() + " défaite(s)", x + 80, y + 40, CasinoStyle.GOLD, false);
        String[] lab = {"Force", "Agilité", "Endurance"};
        int[] v = {p.force(), p.agility(), p.stamina()};
        for (int i = 0; i < 3; i++) {
            int ry = top + 154 + i * 24;
            g.drawString(font, lab[i], x + 12, ry, CasinoStyle.TEXT, false);
            for (int k = 0; k < p.statMax(); k++) CasinoStyle.round(g, x + 80 + k * 12, ry, 9, 9, 4, k < v[i] ? CasinoStyle.PINK : 0x44FFFFFF);
        }
        String info = p.registered() ? "Inscrit : l'organisateur peut le choisir. Un double se bat, votre animal ne risque rien."
                : p.restWait() > 0 ? "Au repos encore " + mmss(p.restWait()) + " après son dernier combat."
                : p.trainWait() > 0 ? "Prochain entraînement dans " + mmss(p.trainWait()) + "."
                : "Chaque séance d'entraînement ajoute +1 (payée en espèces).";
        g.drawString(font, fit(info, ww - 24), x + 12, y + 70, CasinoStyle.MUTED, false);
        drawStatus(g, top + h - 46);
    }

    // ------------------------------------------------------------------ onglet Organisation

    private void drawOrg(GuiGraphics g) {
        int x = left + 12, y = top + 64, ww = w - 24;
        CasinoStyle.card(g, x, y, ww, 196);
        g.drawString(font, "Votre arène", x + 12, y + 10, CasinoStyle.GOLD, false);
        g.drawString(font, "Commission", x + 12, top + 135, CasinoStyle.TEXT, false);
        String pct = String.format(java.util.Locale.ROOT, "%.0f %%", st.commission());
        CasinoStyle.round(g, left + 228, top + 130, 54, 18, 9, CasinoStyle.FRAME);
        CasinoStyle.centered(g, pct, left + 255, top + 135, CasinoStyle.GOLD);
        g.drawString(font, "max " + String.format(java.util.Locale.ROOT, "%.0f %%", st.commissionMax()), left + 318, top + 135, CasinoStyle.MUTED, false);
        String[] lines = {
                "Prise sur les mises PERDANTES de chaque combat, versée en espèces",
                "à vous seul (à votre prochaine connexion si vous êtes absent).",
                "Commissions touchées : " + CasinoStyle.euros(st.earned()),
                "",
                "Déroulé : 1. choisissez les deux combattants (onglet Combattants),",
                "2. ouvrez les paris et choisissez leur durée, 3. le combat part à la",
                "fin du compte à rebours (ou lancez-le avant). Attention à la police !"};
        for (int i = 0; i < lines.length; i++)
            g.drawString(font, fit(lines[i], ww - 24), x + 12, top + 158 + i * 11, i == 2 ? CasinoStyle.OK : CasinoStyle.MUTED, false);
        drawStatus(g, top + h - 46);
    }

    // ------------------------------------------------------------------ arène et combattants dessinés

    private void drawArena(GuiGraphics g, float pt) {
        int cx = cx(), cy = cy();
        boolean big = st != null && st.animals();
        int rx = big ? 170 : 150, ry = big ? 40 : 36;
        CasinoStyle.oval(g, cx, cy + 6, rx, ry, 0x66000000);
        CasinoStyle.oval(g, cx, cy, rx, ry, big ? 0xFF3A3F47 : 0xFF4A2C14);   // grillage / palissade
        CasinoStyle.oval(g, cx, cy, rx - 6, ry - 5, big ? 0xFF5C626C : 0xFF6B4423);
        CasinoStyle.oval(g, cx, cy + 2, rx - 14, ry - 10, 0xFFB98D52);         // sable
        CasinoStyle.oval(g, cx, cy, rx - 30, ry - 18, 0xFFC9A066);
        for (int k = 0; k < 18; k++) {
            int px = cx - 120 + (k * 37) % 240, py = cy - 14 + (k * 23) % 28;
            g.fill(px, py, px + 2, py + 1, 0xFF9C7440);
        }
        if (st == null) return;
        int phase = st.phase();
        String banner = switch (phase) {
            case 1 -> "Combat prêt : les paris vont ouvrir";
            case 2 -> "PARIS OUVERTS · " + st.seconds() + " s";
            case 3 -> "COMBAT !";
            case 4 -> "Victoire de " + (st.winner() == 0 ? st.a().name() : st.b().name()) + " · fin dans " + st.seconds() + " s";
            default -> st.open() ? "En attente des combattants" : "Arène bâchée";
        };
        int bw = font.width(banner) + 18, bc = phase == 2 ? CasinoStyle.PINK : phase == 3 ? CasinoStyle.ORANGE : phase == 4 ? 0xFFB08A20 : 0xCC1A1033;
        CasinoStyle.round(g, cx - bw / 2, cy - ry - 4, bw, 13, 6, bc);
        CasinoStyle.centered(g, fit(banner, w - 40), cx, cy - ry - 1, CasinoStyle.WHITE);
        if (!st.a().name().isEmpty()) {
            hpBar(g, cx - rx + 14, cy - 30, shownA, st.a().hp(), RED_SIDE);
            hpBar(g, cx + rx - 94, cy - 30, shownB, st.b().hp(), BLUE_SIDE);
            drawFighterAt(g, 0, pt);
            drawFighterAt(g, 1, pt);
        }
        for (float[] f : feathers) {
            int a = (int) Math.min(255, f[4] * 14);
            g.fill((int) f[0], (int) f[1], (int) f[0] + 2, (int) f[1] + 1, (a << 24) | 0xF4F1EA);
        }
    }

    private void hpBar(GuiGraphics g, int x, int y, float hp, int max, int col) {
        CasinoStyle.round(g, x, y, 80, 7, 3, 0xAA000000);
        float frac = max <= 0 ? 0 : Math.max(0, Math.min(1, hp / max));
        if (frac > 0) CasinoStyle.round(g, x + 1, y + 1, Math.max(2, Math.round(78 * frac)), 5, 2, frac < 0.3f ? CasinoStyle.ORANGE : col);
    }

    private void drawFighterAt(GuiGraphics g, int side, float pt) {
        float t = ticks + pt;
        int baseX = cx() + (side == 0 ? -48 : 48);
        float bob = (float) Math.sin(t * 0.25 + side * 2) * 1.5f;
        float ox = 0, oy = bob;
        boolean dead = st.phase() == 4 && st.winner() != side;
        if (animTicks < 10 && st.phase() >= 3) {
            float k = animTicks + pt;
            if (side == animAttacker) {
                float lunge = (float) Math.sin(Math.min(1, k / 8f) * Math.PI);   // bond vers l'adversaire puis retour
                ox = (side == 0 ? 1 : -1) * lunge * 42;
                oy = -lunge * 10;
            } else if (animAttacker >= 0) {
                ox = (float) Math.sin(k * 2.2) * (animCrit ? 3 : 1.5f) * Math.max(0, 1 - k / 10f);
            }
        }
        if (st.phase() == 4 && !dead) oy = -Math.abs((float) Math.sin(t * 0.4)) * 6;   // le vainqueur sautille
        int color = 0xFF000000 | (side == 0 ? st.a().color() : st.b().color());
        boolean hurt = animTicks < 4 && side != animAttacker && animAttacker >= 0 && st.phase() == 3;
        g.pose().pushPose();
        g.pose().translate(baseX + ox, cy() + 8 + oy, 0);
        if (dead) g.pose().mulPose(Axis.ZP.rotationDegrees(side == 0 ? -90 : 90));
        g.pose().scale(side == 0 ? 1 : -1, 1, 1);   // les deux combattants se font face
        if (st.animals()) drawAnimal(g, dead ? 0xFF6E6A70 : color, hurt);
        else drawCock(g, dead ? 0xFF6E6A70 : color, hurt);
        g.pose().popPose();
    }

    /** Animal à quatre pattes (chien, chat…) face à droite, pattes à l'origine. */
    private static void drawAnimal(GuiGraphics g, int body, boolean hurt) {
        int dark = CasinoStyle.darken(body, 0.6f);
        CasinoStyle.round(g, -18, -20, 9, 4, 2, dark);                 // queue
        for (int k : new int[]{-11, -6, 6, 11}) CasinoStyle.round(g, k - 1, -8, 3, 8, 1, dark);   // pattes
        CasinoStyle.oval(g, 0, -13, 15, 7, body);                        // corps
        CasinoStyle.round(g, 9, -24, 9, 9, 4, body);                     // tête
        CasinoStyle.round(g, 10, -28, 3, 5, 1, dark);                    // oreilles
        CasinoStyle.round(g, 15, -28, 3, 5, 1, dark);
        CasinoStyle.round(g, 16, -19, 6, 4, 2, body);                    // museau
        g.fill(20, -19, 22, -17, 0xFF111111);
        g.fill(14, -22, 16, -20, 0xFF111111);                             // œil
        if (hurt) CasinoStyle.oval(g, 0, -16, 17, 12, 0x88FF2020);
    }

    /** Coq dessiné face à droite, pieds à l'origine. */
    private static void drawCock(GuiGraphics g, int body, boolean hurt) {
        int dark = CasinoStyle.darken(body, 0.6f);
        for (int k = 0; k < 4; k++) CasinoStyle.round(g, -17 + k, -24 + k * 3, 6, 12, 3, k % 2 == 0 ? 0xFF1E3A2A : dark);   // queue en panache
        g.fill(-3, -6, -2, 0, 0xFFE0A21A);   // pattes
        g.fill(2, -6, 3, 0, 0xFFE0A21A);
        g.fill(-5, -1, -1, 0, 0xFFE0A21A);
        g.fill(1, -1, 5, 0, 0xFFE0A21A);
        CasinoStyle.oval(g, 0, -13, 12, 8, body);   // corps et aile
        CasinoStyle.oval(g, -2, -12, 7, 4, dark);
        CasinoStyle.round(g, 6, -24, 6, 10, 3, body);   // cou et tête
        CasinoStyle.disc(g, 10, -25, 5, body);
        CasinoStyle.round(g, 7, -32, 3, 4, 1, 0xFFE0192E);   // crête et barbillons
        CasinoStyle.round(g, 9, -33, 3, 5, 1, 0xFFE0192E);
        CasinoStyle.round(g, 11, -32, 3, 4, 1, 0xFFE0192E);
        CasinoStyle.round(g, 12, -22, 2, 4, 1, 0xFFE0192E);
        g.fill(15, -26, 19, -24, 0xFFF2B21B);   // bec et œil
        g.fill(16, -24, 18, -23, 0xFFC98A0A);
        g.fill(11, -27, 13, -25, 0xFF111111);
        if (hurt) CasinoStyle.oval(g, 0, -16, 14, 12, 0x88FF2020);
    }

    // ------------------------------------------------------------------ clics

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (st != null && tab == TAB_COMBAT) {
            if (!st.police() && chipClick(left + 46, top + 264, mx, my)) {
                typed = String.valueOf(chip / 100);
                amount.setValue(typed);
                return true;
            }
            // choisir son coin en cliquant sur une fiche (paris ouverts, pas déjà engagé de l'autre côté)
            if (st.phase() == 2 && !st.a().name().isEmpty() && my >= top + 152 && my < top + 208) {
                int side = mx < left + w / 2 ? 0 : 1;
                if ((side == 0 ? st.mineB() : st.mineA()) == 0) { betSide = side; sound(SoundEvents.UI_BUTTON_CLICK.value(), 1.2f); }
                return true;
            }
        }
        if (st != null && tab == TAB_ROSTER && st.owner() && (st.phase() == 0 || st.phase() == 1)) {
            for (int i = 0; i < st.roster().size() && i < 8; i++) {
                int[] p = cardPos(i);
                int y = p[1] + 4;
                if (my < y || my >= y + 13) continue;
                int rx = p[0] + CARD_W - 86, bx = p[0] + CARD_W - 44;
                if (mx >= rx && mx < rx + 40) { send(10, 0, st.pickA() == i ? -1 : i); return true; }
                if (mx >= bx && mx < bx + 40) { send(10, 1, st.pickB() == i ? -1 : i); return true; }
            }
        }
        return super.mouseClicked(mx, my, button);
    }
}
