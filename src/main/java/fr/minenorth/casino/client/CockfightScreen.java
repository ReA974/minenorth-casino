package fr.minenorth.casino.client;

import com.mojang.math.Axis;
import fr.minenorth.casino.network.ModNetwork;
import fr.minenorth.casino.network.ModNetwork.CockStatePacket;
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
 * Arène clandestine : deux combattants (coqs de la maison ou animaux de joueurs), leurs qualités, les cotes du pari mutuel et le combat animé.
 * Onglets : Paris, Mon animal (inscription, entraînement), Organisation (organisateur : ouverture, lancement, commission, bénéficiaire).
 * Tout est décidé et revérifié par le serveur ; l'écran rejoue chaque échange qu'il reçoit.
 */
public class CockfightScreen extends CasinoScreen {
    private static final int RED_SIDE = 0xFFE0335A, BLUE_SIDE = 0xFF3D7BFF;

    private CockStatePacket st;
    private int lastSeq = -1;
    /** Animation de l'échange en cours : ticks écoulés, attaquant, coup critique. */
    private int animTicks = 99, animAttacker = -1;
    private boolean animCrit;
    private int ticks;
    private final List<float[]> feathers = new ArrayList<>();   // x, y, vx, vy, vie
    private final Random rnd = new Random();
    /** Points de vie affichés (ils descendent en douceur). */
    private float shownA = -1, shownB = -1;
    private CasinoButton betRed, betBlue, raid, collect;
    private ModNetwork.CockExtraPacket ex;
    /** 0 paris, 1 mon animal, 2 organisation. */
    private int tab;
    private final List<CasinoButton> tabButtons = new ArrayList<>(), petButtons = new ArrayList<>(), orgButtons = new ArrayList<>();
    private CasinoButton register, trainF, trainA, trainS, openBtn, manualBtn, startBtn, comMinus, comPlus, benefBtn, designate;
    private EditBox playerBox;

    public CockfightScreen(OpenPacket info) { super(info, "Arène clandestine", 380, 330); }

    private int arenaCx() { return left + w / 2; }

    private int arenaCy() { return top + 98; }

    @Override
    protected void init() {
        super.init();
        int y = top + h - 28;
        betRed = add(new CasinoButton(left + 12, y, 172, 20, "Parier sur ROUGE", RED_SIDE, () -> bet(0)));
        betBlue = add(new CasinoButton(left + w - 184, y, 172, 20, "Parier sur BLEU", BLUE_SIDE, () -> bet(1)));
        raid = add(new CasinoButton(left + 12, y, w - 24, 20, "🚨 DÉMANTELER L'ARÈNE (saisir les paris)", 0xFF1F4FD1,
                () -> ModNetwork.CHANNEL.sendToServer(new ModNetwork.CockActionPacket(2, 0, 0))));
        collect = add(new CasinoButton(left + w - 150, top + 24, 138, 13, "Récupérer la caisse", 0xFFB08A20,
                () -> ModNetwork.CHANNEL.sendToServer(new ModNetwork.CockActionPacket(1, 0, 0))));
        tabButtons.clear(); petButtons.clear(); orgButtons.clear();
        String[] names = {"Paris", "Mon animal", "Organisation"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            tabButtons.add(add(new CasinoButton(left + 12 + i * 72, top + 24, 68, 13, names[i], CasinoStyle.PINK, () -> { tab = idx; status = ""; })));
        }
        int py = top + 214;
        register = pet(new CasinoButton(left + 12, top + h - 28, w - 24, 20, "Inscrire mon animal au prochain combat", 0xFF2E9E5A,
                () -> action(ex != null && ex.mySide() >= 0 ? 4 : 3, 0)));
        trainF = pet(new CasinoButton(left + w - 132, py, 120, 16, "Entraîner", CasinoStyle.PINK, () -> action(5, 0)));
        trainA = pet(new CasinoButton(left + w - 132, py + 20, 120, 16, "Entraîner", CasinoStyle.PINK, () -> action(5, 1)));
        trainS = pet(new CasinoButton(left + w - 132, py + 40, 120, 16, "Entraîner", CasinoStyle.PINK, () -> action(5, 2)));
        int ox = left + 12, ow = (w - 30) / 2;
        openBtn = org(new CasinoButton(ox, top + 156, ow, 18, "Arène", 0xFF2E9E5A, () -> setting(0, 0, "")));
        manualBtn = org(new CasinoButton(ox + ow + 6, top + 156, ow, 18, "Lancement", 0xFF3D7BFF, () -> setting(1, 0, "")));
        startBtn = org(new CasinoButton(ox, top + 180, w - 24, 18, "▶ Lancer le combat maintenant", CasinoStyle.ORANGE, () -> setting(5, 0, "")));
        comMinus = org(new CasinoButton(ox + 150, top + 206, 22, 16, "−", 0xFF4A3C8C, () -> commission(-1)));
        comPlus = org(new CasinoButton(ox + 220, top + 206, 22, 16, "+", 0xFF4A3C8C, () -> commission(1)));
        benefBtn = org(new CasinoButton(ox + 150, top + 228, w - 174, 16, "Bénéficiaire", 0xFFB08A20,
                () -> setting(2, ex == null ? 0 : (ex.beneficiary() + 1) % 4, "")));
        playerBox = new EditBox(font, ox + 150, top + 252, 110, 14, Component.literal("Pseudo"));
        playerBox.setMaxLength(16);
        playerBox.setHint(Component.literal("Pseudo du joueur"));
        addRenderableWidget(playerBox);
        designate = org(new CasinoButton(ox + 266, top + 251, w - 290, 16, "Désigner", 0xFFB08A20, () -> setting(4, 0, playerBox.getValue())));
    }

    private CasinoButton pet(CasinoButton b) { petButtons.add(b); return add(b); }

    private CasinoButton org(CasinoButton b) { orgButtons.add(b); return add(b); }

    private void action(int action, int side) {
        status = "";
        ModNetwork.CHANNEL.sendToServer(new ModNetwork.CockActionPacket(action, side, 0));
    }

    private void setting(int kind, int value, String text) {
        status = "";
        ModNetwork.CHANNEL.sendToServer(new ModNetwork.CockSettingPacket(kind, value, text == null ? "" : text));
    }

    /** Commission : pas de 1 %, envoyée en dixièmes de %. */
    private void commission(int delta) {
        if (ex == null) return;
        float v = Math.max(0, Math.min(ex.commissionMax(), Math.round(ex.commission()) + delta));
        setting(3, Math.round(v * 10), "");
    }

    public void extra(ModNetwork.CockExtraPacket p) {
        ex = p;
        if (!p.organizer() && tab == 2) tab = 0;
    }

    private CasinoButton add(CasinoButton b) { addRenderableWidget(b); return b; }

    private void bet(int side) {
        status = "";
        ModNetwork.CHANNEL.sendToServer(new ModNetwork.CockActionPacket(0, side, chip));
    }

    public void state(CockStatePacket p) {
        if (p.seq() >= 0 && p.seq() != lastSeq && p.attacker() >= 0) {
            lastSeq = p.seq();
            animTicks = 0;
            animAttacker = p.attacker();
            animCrit = p.crit();
            if (p.damage() > 0) {
                int dx = arenaCx() + (p.attacker() == 0 ? 34 : -34), dy = arenaCy() - 4;
                for (int k = 0; k < (p.crit() ? 14 : 6); k++)
                    feathers.add(new float[]{dx, dy, (rnd.nextFloat() - 0.5f) * 3f, -rnd.nextFloat() * 2.5f, 20 + rnd.nextInt(15)});
            }
        }
        if (st != null && st.phase() != 3 && p.phase() == 3) {
            boolean won = (p.winner() == 0 && p.mineA() > 0) || (p.winner() == 1 && p.mineB() > 0);
            if (st.mineA() + st.mineB() > 0) sound(won ? SoundEvents.PLAYER_LEVELUP : SoundEvents.VILLAGER_NO, 1f);
        }
        if (st == null || p.phase() == 0 || p.phase() == 1) { shownA = p.hpA(); shownB = p.hpB(); }
        st = p;
        held = -1;   // ici on parie en espèces : l'en-tête « Jetons » n'a pas lieu d'être
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

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        boolean open = st != null && (st.phase() == 0 || st.phase() == 1);
        boolean cop = st != null && st.police();
        boolean org = ex != null && ex.organizer();
        tabButtons.get(2).visible = org;
        for (int i = 0; i < 3; i++) tabButtons.get(i).selected(tab == i);
        boolean betTab = tab == 0;
        betRed.visible = betBlue.visible = !cop && betTab;
        raid.visible = cop && betTab;
        for (CasinoButton b : petButtons) b.visible = tab == 1 && ex != null && !ex.petName().isEmpty();
        for (CasinoButton b : orgButtons) b.visible = tab == 2 && org;
        playerBox.visible = tab == 2 && org;
        if (ex != null) updateButtons(st != null && st.phase() == 0);
        betRed.enabled(open && (st.mineB() == 0));
        betBlue.enabled(open && (st.mineA() == 0));
        raid.enabled(st != null && st.nameA() != null);
        collect.visible = st != null && st.organizer();
        collect.enabled(st != null && st.caisse() > 0);
        if (st != null && st.organizer()) collect.setMessage(Component.literal("Prendre la caisse : " + CasinoStyle.euros(st.caisse())));
        super.render(g, mx, my, pt);
        if (st != null) {
            String cash = "Espèces : " + CasinoStyle.euros(st.cash());
            g.drawString(font, cash, left + w - 14 - font.width(cash), top + 14, CasinoStyle.GOLD, false);
        }
        drawArena(g, pt);
        if (tab == 1) { drawPetPanel(g); return; }
        if (tab == 2 && ex != null && ex.organizer()) { drawOrgPanel(g); return; }
        if (st == null || st.nameA().isEmpty()) {
            CasinoStyle.centered(g, st == null ? "…" : fit(st.message(), w - 30), arenaCx(), arenaCy() + 70, CasinoStyle.MUTED);
            return;
        }
        drawSide(g, 0, left + 12, top + 152);
        drawSide(g, 1, left + w / 2 + 4, top + 152);
        // déroulé / message
        int my2 = top + 208;
        CasinoStyle.round(g, left + 12, my2, w - 24, 14, 7, 0x99000000);
        CasinoStyle.centered(g, fit(st.message(), w - 36), arenaCx(), my2 + 3, CasinoStyle.TEXT);
        // parieurs
        int by = top + 226;
        for (int i = 0; i < st.bets().size() && i < 4; i++) {
            String row = st.bets().get(i);
            int bar = row.lastIndexOf('|');
            if (bar < 1) continue;
            boolean red = row.charAt(0) == 'R';
            long c = parse(row.substring(bar + 1));
            String line = fit(row.substring(1, bar), 110) + "  " + CasinoStyle.euros(c);
            int x = left + 14 + (i % 2) * (w / 2 - 4), y = by + (i / 2) * 10;
            CasinoStyle.disc(g, x + 3, y + 4, 3, red ? RED_SIDE : BLUE_SIDE);
            g.drawString(font, line, x + 9, y, CasinoStyle.MUTED, false);
        }
        drawStatus(g, top + h - 66);
        if (!cop) {
            g.drawString(font, "Mise :", left + 12, top + h - 46, CasinoStyle.MUTED, false);
            chipRow(g, left + 48, top + h - 49, mx, my);
        } else {
            CasinoStyle.centered(g, "Vous êtes en service : vous pouvez démanteler l'arène.", arenaCx(), top + h - 44, CasinoStyle.CYAN);
        }
    }

    private void updateButtons(boolean idle) {
        boolean mine = ex.mySide() >= 0;
        register.setMessage(Component.literal(mine ? "Retirer mon animal du combat" : "Inscrire " + ex.petName() + " au prochain combat"));
        register.enabled(idle && (mine || ex.restWait() == 0));
        int[] v = {ex.petForce(), ex.petAgi(), ex.petSta()};
        CasinoButton[] tb = {trainF, trainA, trainS};
        for (int i = 0; i < 3; i++) {
            boolean max = v[i] >= ex.statMax();
            tb[i].setMessage(Component.literal(max ? "Au maximum" : "+1 · " + CasinoStyle.euros(ex.trainUnitCents() * Math.max(1, v[i]))));
            tb[i].enabled(!max && ex.trainWait() == 0 && !mine);
        }
        openBtn.setMessage(Component.literal(ex.open() ? "Arène OUVERTE (fermer)" : "Arène FERMÉE (ouvrir)"));
        manualBtn.setMessage(Component.literal(ex.manual() ? "Lancement : À LA MAIN" : "Lancement : AUTO"));
        startBtn.enabled(ex.open() && st != null && (st.phase() == 0 || st.phase() == 1));
        benefBtn.setMessage(Component.literal(fit(ex.beneficiaryLabel(), w - 190)));
    }

    private static String mmss(int sec) { return sec >= 60 ? (sec / 60) + " min" : sec + " s"; }

    /** Onglet « Mon animal » : animal le plus proche, palmarès, qualités et entraînement. */
    private void drawPetPanel(GuiGraphics g) {
        int x = left + 12, y = top + 152, ww = w - 24;
        CasinoStyle.roundGradient(g, x, y, ww, 150, 10, 0xFF2A1C4A, 0xFF1A1033);
        if (ex == null || ex.petName().isEmpty()) {
            CasinoStyle.centered(g, "Amenez votre animal apprivoisé tout près de vous", arenaCx(), y + 50, CasinoStyle.TEXT);
            CasinoStyle.centered(g, "(chien, chat, animal Polly Pets…) pour l'inscrire ou l'entraîner.", arenaCx(), y + 64, CasinoStyle.MUTED);
            drawStatus(g, top + h - 26);
            return;
        }
        g.drawString(font, fit(ex.petName() + " · " + ex.petSpecies(), ww - 20), x + 10, y + 8, CasinoStyle.WHITE, false);
        String rec = ex.petWins() + " victoire(s) · " + ex.petLosses() + " défaite(s)";
        g.drawString(font, rec, x + 10, y + 20, CasinoStyle.GOLD, false);
        String[] lab = {"Force", "Agilité", "Endurance"};
        int[] v = {ex.petForce(), ex.petAgi(), ex.petSta()};
        for (int i = 0; i < 3; i++) {
            int ry = top + 214 + i * 20 + 4;
            g.drawString(font, lab[i], x + 10, ry, CasinoStyle.TEXT, false);
            for (int k = 0; k < ex.statMax(); k++) CasinoStyle.round(g, x + 72 + k * 9, ry, 7, 8, 3, k < v[i] ? CasinoStyle.PINK : 0x44FFFFFF);
        }
        String info = ex.mySide() >= 0 ? "Inscrit côté " + (ex.mySide() == 0 ? "ROUGE" : "BLEU") + " : un double se bat, votre animal ne risque rien."
                : ex.restWait() > 0 ? "Au repos encore " + mmss(ex.restWait()) + " après son dernier combat."
                : ex.trainWait() > 0 ? "Prochain entraînement dans " + mmss(ex.trainWait()) + "."
                : "Entraînez-le avant de l'inscrire : chaque séance ajoute +1.";
        g.drawString(font, fit(info, ww - 20), x + 10, y + 36, CasinoStyle.MUTED, false);
        drawStatus(g, top + h - 52);
    }

    /** Onglet « Organisation » : ouverture, lancement, commission et qui la touche. */
    private void drawOrgPanel(GuiGraphics g) {
        int x = left + 12;
        CasinoStyle.roundGradient(g, x, top + 150, w - 24, 124, 10, 0xFF2A1C4A, 0xFF1A1033);
        g.drawString(font, "Commission", x + 10, top + 210, CasinoStyle.TEXT, false);
        String pct = String.format(java.util.Locale.ROOT, "%.0f %%", ex.commission());
        CasinoStyle.centered(g, pct, x + 186 + 11, top + 210, CasinoStyle.GOLD);
        g.drawString(font, "des mises perdantes", x + 250, top + 210, CasinoStyle.MUTED, false);
        g.drawString(font, "Elle va à", x + 10, top + 232, CasinoStyle.TEXT, false);
        g.drawString(font, "Joueur désigné", x + 10, top + 255, CasinoStyle.TEXT, false);
        CasinoStyle.centered(g, fit(ex.manual() ? "Les paris restent ouverts jusqu'à ce que vous lanciez le combat."
                : "Le combat part à la fin du compte à rebours (ou tout de suite avec le bouton).", w - 30), arenaCx(), top + 280, CasinoStyle.MUTED);
        drawStatus(g, top + h - 30);
    }

    private static long parse(String s) { try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; } }

    /** Cote du pari mutuel pour un euro misé sur {@code side} (mise comprise) ; avant tout pari, la cote théorique. */
    private double odds(int side) {
        double c = (ex != null ? ex.commission() : info.bjGain()) / 100.0;
        long mine = side == 0 ? st.poolA() : st.poolB(), other = side == 0 ? st.poolB() : st.poolA();
        if (mine > 0) return 1 + other * (1 - c) / mine;
        double p = side == 0 ? st.pA() : 1 - st.pA();
        return 1 + (1 - c) * (1 - p) / p;
    }

    private void drawSide(GuiGraphics g, int side, int x, int y) {
        int ww = w / 2 - 16;
        int col = side == 0 ? RED_SIDE : BLUE_SIDE;
        boolean win = st.phase() == 3 && st.winner() == side;
        if (win) CasinoStyle.round(g, x - 2, y - 2, ww + 4, 56, 9, CasinoStyle.GOLD);
        CasinoStyle.roundGradient(g, x, y, ww, 52, 8, CasinoStyle.mix(col, 0xFF1A1033, 0.55f), 0xFF1A1033);
        String owner = ex == null ? "" : side == 0 ? ex.ownerA() : ex.ownerB();
        g.drawString(font, fit(owner.isEmpty() ? "Coq de la maison" : "Animal de " + owner, ww - 10), x + 6, y + 40, owner.isEmpty() ? CasinoStyle.MUTED : CasinoStyle.CYAN, false);
        String name = side == 0 ? st.nameA() : st.nameB();
        g.drawString(font, fit(name, ww - 50), x + 6, y + 4, CasinoStyle.WHITE, false);
        String od = String.format(java.util.Locale.ROOT, "x%.2f", odds(side));
        g.drawString(font, od, x + ww - 6 - font.width(od), y + 4, CasinoStyle.GOLD, false);
        int f = side == 0 ? st.forceA() : st.forceB(), a = side == 0 ? st.agiA() : st.agiB(), s = side == 0 ? st.staA() : st.staB();
        stat(g, x + 6, y + 15, "FOR", f, col);
        stat(g, x + 6 + (ww - 12) / 3, y + 15, "AGI", a, col);
        stat(g, x + 6 + 2 * (ww - 12) / 3, y + 15, "END", s, col);
        long pool = side == 0 ? st.poolA() : st.poolB(), mine = side == 0 ? st.mineA() : st.mineB();
        g.drawString(font, fit("Misé " + CasinoStyle.euros(pool) + (mine > 0 ? " · vous " + CasinoStyle.euros(mine) : ""), ww - 10), x + 6, y + 29,
                mine > 0 ? CasinoStyle.OK : CasinoStyle.MUTED, false);
    }

    private void stat(GuiGraphics g, int x, int y, String label, int v, int col) {
        g.drawString(font, label, x, y, CasinoStyle.MUTED, false);
        int bx = x + font.width(label) + 3;
        for (int i = 0; i < 9; i++) CasinoStyle.round(g, bx + i * 3, y + 1, 2, 6, 1, i < v ? col : 0x44FFFFFF);
    }

    // ------------------------------------------------------------------ arène et coqs

    private void drawArena(GuiGraphics g, float pt) {
        int cx = arenaCx(), cy = arenaCy();
        CasinoStyle.oval(g, cx, cy + 6, 150, 46, 0x66000000);
        CasinoStyle.oval(g, cx, cy, 150, 46, 0xFF4A2C14);    // palissade
        CasinoStyle.oval(g, cx, cy, 144, 41, 0xFF6B4423);
        CasinoStyle.oval(g, cx, cy + 2, 136, 36, 0xFFB98D52); // sable
        CasinoStyle.oval(g, cx, cy, 120, 28, 0xFFC9A066);
        for (int k = 0; k < 18; k++) {
            int px = cx - 120 + (k * 37) % 240, py = cy - 18 + (k * 23) % 36;
            g.fill(px, py, px + 2, py + 1, 0xFF9C7440);
        }
        if (st != null) {
            int phase = st.phase();
            if (phase == 1) {
                String t = "PARIS OUVERTS · " + st.seconds() + " s";
                int tw = font.width(t) + 16;
                CasinoStyle.round(g, cx - tw / 2, cy - 52, tw, 13, 6, CasinoStyle.PINK);
                CasinoStyle.centered(g, t, cx, cy - 49, CasinoStyle.WHITE);
            } else if (phase == 2) {
                CasinoStyle.scaled(g, CasinoStyle.bold("COMBAT !"), cx - 26, cy - 54, 1.4f, CasinoStyle.ORANGE);
            } else if (phase == 0 && !st.nameA().isEmpty()) {
                CasinoStyle.centered(g, "Faites vos jeux : le premier pari lance le compte à rebours", cx, cy - 50, CasinoStyle.MUTED);
            } else if (phase == 3) {
                String t = "Victoire de " + (st.winner() == 0 ? st.nameA() : st.nameB()) + " · suivant dans " + st.seconds() + " s";
                CasinoStyle.centered(g, fit(t, w - 30), cx, cy - 50, CasinoStyle.GOLD);
            }
            if (!st.nameA().isEmpty()) {
                hpBar(g, cx - 140, cy - 38, shownA, st.hpMaxA(), RED_SIDE);
                hpBar(g, cx + 60, cy - 38, shownB, st.hpMaxB(), BLUE_SIDE);
                drawCockAt(g, 0, pt);
                drawCockAt(g, 1, pt);
            }
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

    private void drawCockAt(GuiGraphics g, int side, float pt) {
        int cx = arenaCx(), cy = arenaCy();
        float t = ticks + pt;
        int baseX = cx + (side == 0 ? -44 : 44);
        float bob = (float) Math.sin(t * 0.25 + side * 2) * 1.5f;
        float ox = 0, oy = bob;
        boolean dead = st.phase() == 3 && st.winner() != side;
        if (animTicks < 10 && st.phase() >= 2) {
            float k = animTicks + pt;
            if (side == animAttacker) {
                // bond vers l'adversaire puis retour
                float lunge = (float) Math.sin(Math.min(1, k / 8f) * Math.PI);
                ox = (side == 0 ? 1 : -1) * lunge * 40;
                oy = -lunge * 10;
            } else if (animAttacker >= 0) {
                ox = (float) Math.sin(k * 2.2) * (animCrit ? 3 : 1.5f) * Math.max(0, 1 - k / 10f);
            }
        }
        if (st.phase() == 3 && !dead) oy = -Math.abs((float) Math.sin(t * 0.4)) * 6;   // le vainqueur sautille
        int color = 0xFF000000 | (side == 0 ? st.colorA() : st.colorB());
        boolean hurt = animTicks < 4 && side != animAttacker && animAttacker >= 0 && st.phase() == 2;
        g.pose().pushPose();
        g.pose().translate(baseX + ox, cy + 6 + oy, 0);
        if (dead) g.pose().mulPose(Axis.ZP.rotationDegrees(side == 0 ? -90 : 90));
        g.pose().scale(side == 0 ? 1 : -1, 1, 1);   // les deux coqs se font face
        boolean animal = ex != null && !(side == 0 ? ex.ownerA() : ex.ownerB()).isEmpty();
        if (animal) drawAnimal(g, dead ? 0xFF6E6A70 : color, hurt);
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
        // queue en panache
        for (int k = 0; k < 4; k++) CasinoStyle.round(g, -17 + k, -24 + k * 3, 6, 12, 3, k % 2 == 0 ? 0xFF1E3A2A : dark);
        // pattes
        g.fill(-3, -6, -2, 0, 0xFFE0A21A);
        g.fill(2, -6, 3, 0, 0xFFE0A21A);
        g.fill(-5, -1, -1, 0, 0xFFE0A21A);
        g.fill(1, -1, 5, 0, 0xFFE0A21A);
        // corps et aile
        CasinoStyle.oval(g, 0, -13, 12, 8, body);
        CasinoStyle.oval(g, -2, -12, 7, 4, dark);
        // cou et tête
        CasinoStyle.round(g, 6, -24, 6, 10, 3, body);
        CasinoStyle.disc(g, 10, -25, 5, body);
        // crête et barbillons
        CasinoStyle.round(g, 7, -32, 3, 4, 1, 0xFFE0192E);
        CasinoStyle.round(g, 9, -33, 3, 5, 1, 0xFFE0192E);
        CasinoStyle.round(g, 11, -32, 3, 4, 1, 0xFFE0192E);
        CasinoStyle.round(g, 12, -22, 2, 4, 1, 0xFFE0192E);
        // bec et œil
        g.fill(15, -26, 19, -24, 0xFFF2B21B);
        g.fill(16, -24, 18, -23, 0xFFC98A0A);
        g.fill(11, -27, 13, -25, 0xFF111111);
        if (hurt) CasinoStyle.oval(g, 0, -16, 14, 12, 0x88FF2020);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (tab == 0 && st != null && !st.police() && chipClick(left + 48, top + h - 49, mx, my)) return true;
        return super.mouseClicked(mx, my, button);
    }
}
