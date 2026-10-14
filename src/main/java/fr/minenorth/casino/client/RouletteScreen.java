package fr.minenorth.casino.client;

import fr.minenorth.casino.game.RouletteGame;
import fr.minenorth.casino.game.RouletteGame.Bet;
import fr.minenorth.casino.network.ModNetwork;
import fr.minenorth.casino.network.ModNetwork.OpenPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Roulette européenne. Clic gauche sur une case : pose la mise sélectionnée ; clic droit : retire le pari. « Lancer »
 * envoie tous les paris au serveur, qui tire le numéro et règle le gain.
 */
public class RouletteScreen extends CasinoScreen {
    private static final int CELL_W = 20, CELL_H = 18, ZERO_W = 22;
    private static final String[] DOZEN_LABEL = {"1 - 12", "13 - 24", "25 - 36"};

    private record Cell(int x, int y, int w, int h, int type, int value, String label, int color) {}

    private final Map<Integer, Long> bets = new LinkedHashMap<>();   // clé = type * 100 + valeur
    private final Map<Integer, Long> sentBets = new LinkedHashMap<>();   // paris envoyés, remis sur le tapis si le serveur refuse
    private final List<Cell> cells = new ArrayList<>();
    private final Random anim = new Random();
    private boolean waiting;
    private int ticks;
    private ModNetwork.RouletteResultPacket result;
    private CasinoButton spinBtn, clearBtn;
    private int lastNumber = -1, shownNumber = -1;
    /** Jetons reçus pendant le tirage : affichés seulement une fois le résultat révélé (sinon le solde annonce le gain avant la fin). */
    private long pendingHeld = Long.MIN_VALUE;

    // Animation de la roue (dessinée dans l'écran, quelques dizaines de rectangles par image : aucun coût mesurable en FPS).
    /** Ordre des numéros sur la roue européenne. */
    private static final int[] WHEEL = {0, 32, 15, 19, 4, 21, 2, 25, 17, 34, 6, 27, 13, 36, 11, 30, 8, 23, 10, 5, 24, 16, 33, 1, 20, 14, 31, 9, 22, 18, 29, 7, 28, 12, 35, 3, 26};
    private static final double STEP = 2 * Math.PI / 37;
    /** Durée de l'atterrissage de la bille et du maintien du résultat, en ticks (20 par seconde). */
    private static final double LAND = 64, HOLD = 40;
    private static final double WHEEL_SPEED = 0.045, BALL_SPEED = -0.40;
    private final long base = System.nanoTime();
    private double landStart = -1, ballEnd, ballDelta, lastTickSound, lastRattle;
    private int lastPocket = Integer.MIN_VALUE;
    private boolean revealed;

    // Derniers numéros sortis (tous joueurs confondus), le plus récent en premier. Pendant un tirage, la mise à jour attend la fin de l'animation.
    private static final int PANEL_W = 98, HIST_COLS = 4, HIST_ROWS = 10;
    private List<Integer> history = new ArrayList<>(), pendingHistory;

    public void history(List<Integer> draws) {
        if (busy()) pendingHistory = draws; else history = draws;
    }

    @Override
    public void held(long cents) {
        if (busy() && !revealed) pendingHeld = cents; else super.held(cents);
    }

    private void flushHeld() {
        if (pendingHeld != Long.MIN_VALUE) { super.held(pendingHeld); pendingHeld = Long.MIN_VALUE; }
    }

    private double clock() { return (System.nanoTime() - base) / 50_000_000.0; }
    private static double wheelAngle(double c) { return c * WHEEL_SPEED; }
    private static int pocketOf(int number) { for (int i = 0; i < WHEEL.length; i++) if (WHEEL[i] == number) return i; return 0; }

    private static void sound(net.minecraft.sounds.SoundEvent e, float pitch, float volume) {
        net.minecraft.client.Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(e, pitch, volume));
    }

    public RouletteScreen(OpenPacket info) { super(info, "Roulette", 300, 284); }

    private static int key(int type, int value) { return type * 100 + value; }

    private int numberColor(int n) { return n == 0 ? CasinoStyle.FELT : RouletteGame.isRed(n) ? 0xFFB0202C : 0xFF111111; }

    @Override
    protected void init() {
        super.init();
        left = Math.max(4, (width - w - PANEL_W - 6) / 2);   // la table et le panneau des derniers numéros, côte à côte
        cells.clear();
        int gx = left + 8, gy = top + 50, nx = gx + ZERO_W;
        cells.add(new Cell(gx, gy, ZERO_W - 1, 3 * CELL_H - 1, RouletteGame.STRAIGHT, 0, "0", CasinoStyle.FELT));
        for (int col = 0; col < 12; col++)
            for (int row = 0; row < 3; row++) {
                int n = col * 3 + 3 - row;
                cells.add(new Cell(nx + col * CELL_W, gy + row * CELL_H, CELL_W - 1, CELL_H - 1, RouletteGame.STRAIGHT, n, String.valueOf(n), numberColor(n)));
            }
        for (int row = 0; row < 3; row++)
            cells.add(new Cell(nx + 12 * CELL_W, gy + row * CELL_H, 26, CELL_H - 1, RouletteGame.COLUMN, 3 - row, "2:1", CasinoStyle.DARK_BTN));
        for (int k = 0; k < 3; k++)
            cells.add(new Cell(nx + k * 4 * CELL_W, gy + 3 * CELL_H + 1, 4 * CELL_W - 1, CELL_H - 1, RouletteGame.DOZEN, k + 1, DOZEN_LABEL[k], CasinoStyle.DARK_BTN));
        int[] outType = {RouletteGame.LOW, RouletteGame.EVEN, RouletteGame.RED, RouletteGame.BLACK, RouletteGame.ODD, RouletteGame.HIGH};
        String[] outLabel = {"1 - 18", "Pair", "Rouge", "Noir", "Impair", "19 - 36"};
        int[] outColor = {CasinoStyle.DARK_BTN, CasinoStyle.DARK_BTN, 0xFFB0202C, 0xFF111111, CasinoStyle.DARK_BTN, CasinoStyle.DARK_BTN};
        for (int k = 0; k < 6; k++)
            cells.add(new Cell(nx + k * 2 * CELL_W, gy + 4 * CELL_H + 2, 2 * CELL_W - 1, CELL_H - 1, outType[k], 0, outLabel[k], outColor[k]));
        spinBtn = new CasinoButton(left + w - 118, top + h - 30, 106, 22, "LANCER", CasinoStyle.GREEN, this::spin);
        clearBtn = new CasinoButton(left + 12, top + h - 30, 70, 22, "Effacer", CasinoStyle.RED, () -> { if (!busy()) bets.clear(); });
        addRenderableWidget(spinBtn);
        addRenderableWidget(clearBtn);
    }

    private boolean busy() { return waiting || result != null; }

    private long total() {
        long t = 0;
        for (long v : bets.values()) t += v;
        return t;
    }

    private void spin() {
        if (busy() || bets.isEmpty()) return;
        List<Bet> list = new ArrayList<>();
        for (Map.Entry<Integer, Long> e : bets.entrySet()) list.add(new Bet(e.getKey() / 100, e.getKey() % 100, e.getValue()));
        waiting = true;
        ticks = 0;
        landStart = -1;
        revealed = false;
        shownNumber = -1;
        lastRattle = clock();
        status = "";
        sound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.get(), 1.0f, 0.8f);
        ModNetwork.CHANNEL.sendToServer(new ModNetwork.RouletteSpinPacket(list));
        sentBets.clear();
        sentBets.putAll(bets);
        bets.clear();   // les jetons quittent le tapis dès que le tirage commence
    }

    /** Résultat du serveur : la bille quitte sa course et va se poser exactement dans la case du numéro tiré. */
    public void result(ModNetwork.RouletteResultPacket p) {
        result = p;
        waiting = false;
        ticks = 0;
        landStart = clock();
        revealed = false;
        lastPocket = Integer.MIN_VALUE;
        double a = BALL_SPEED * landStart;                                  // angle actuel de la bille
        ballEnd = wheelAngle(landStart + LAND) + pocketOf(p.number()) * STEP;   // angle final : dans la case, sur la roue
        double r = (a - ballEnd) % (2 * Math.PI);
        if (r < 0) r += 2 * Math.PI;
        ballDelta = r - 2 * Math.PI * 3;   // la bille fait encore 2 à 3 tours en ralentissant
    }

    @Override
    public void message(boolean ok, String msg) {
        super.message(ok, msg);
        if (!ok && waiting) {            // tirage refusé par le serveur : on remet les jetons sur le tapis
            waiting = false;
            bets.putAll(sentBets);
        }
    }

    @Override
    public void tick() {
        if (!busy()) { flushHeld(); return; }
        ticks++;
        double c = clock();
        if (!revealed && ticks % 2 == 0) shownNumber = anim.nextInt(37);   // numéros qui défilent pendant le tirage
        if (result == null) {
            if (ticks > 200) { waiting = false; bets.putAll(sentBets); }   // pas de réponse du serveur
            else if (c - lastRattle >= 3) {     // la bille tourne : petit cliquetis
                lastRattle = c;
                soundHat(1.6f + anim.nextFloat() * 0.3f, 0.25f);
            }
            return;
        }
        double u = (c - landStart) / LAND;
        if (u < 1) {
            // En ralentissant, la bille saute de case en case : un « tic » à chaque case dépassée.
            double rel = ballAngle(Math.min(1, Math.max(0, u))) - wheelAngle(c);
            int pocket = (int) Math.floor(rel / STEP);
            if (pocket != lastPocket && u > 0.3 && c - lastTickSound >= 1) {
                lastTickSound = c;
                soundHat(1.2f + 0.5f * (float) u + anim.nextFloat() * 0.15f, 0.5f);
            }
            lastPocket = pocket;
        } else if (!revealed) {
            revealed = true;
            lastNumber = shownNumber = result.number();
            flushHeld();
            if (result.win() > 0) {
                status = "Le " + lastNumber + " : vous gagnez " + CasinoStyle.euros(result.win()) + " !"; statusOk = true;
                sound(net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, 1.1f, 0.8f);
            } else {
                status = "Le " + lastNumber + " : perdu."; statusOk = false;
                soundHat(0.6f, 0.9f);
                sound(net.minecraft.sounds.SoundEvents.VILLAGER_NO, 1.0f, 0.5f);
            }
        }
        if (c - landStart >= LAND + HOLD) {
            result = null;
            bets.clear();
            if (pendingHistory != null) { history = pendingHistory; pendingHistory = null; }
        }
    }

    private void soundHat(float pitch, float volume) { sound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_HAT.value(), pitch, volume); }

    /** Angle de la bille pendant l'atterrissage (u de 0 à 1) : tours de plus en plus lents, arrêt dans la case. */
    private double ballAngle(double u) { return ballEnd + ballDelta * (1 - u) * (1 - u); }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        boolean can = !busy() && !bets.isEmpty();
        spinBtn.enabled(can);
        clearBtn.enabled(!busy() && !bets.isEmpty());
        super.render(g, mx, my, pt);
        for (Cell c : cells) {
            boolean hov = !busy() && mx >= c.x && mx < c.x + c.w && my >= c.y && my < c.y + c.h;
            g.fill(c.x, c.y, c.x + c.w, c.y + c.h, hov ? CasinoStyle.lighten(c.color) : c.color);
            CasinoStyle.centered(g, c.label, c.x + c.w / 2, c.y + (c.h - 8) / 2, CasinoStyle.WHITE);
            Long amount = bets.get(key(c.type, c.value));
            if (amount != null) {
                g.fill(c.x + c.w - 4, c.y, c.x + c.w, c.y + 4, CasinoStyle.GOLD);
                g.pose().pushPose();
                g.pose().translate(c.x + 1, c.y + c.h - 7, 0);
                g.pose().scale(0.7f, 0.7f, 1f);
                g.drawString(font, CasinoStyle.euros(amount), 0, 0, CasinoStyle.GOLD, true);
                g.pose().popPose();
            }
        }
        int y = top + 50 + 4 * CELL_H + 2 + CELL_H + 8;
        g.drawString(font, "Jeton", left + 12, y + 3, CasinoStyle.MUTED, false);
        chipRow(g, left + 48, y, mx, my);
        g.drawString(font, fit("Mise totale : " + CasinoStyle.euros(total()) + "   (" + CasinoStyle.euros(info.min()) + " par pari, "
                + CasinoStyle.euros(info.max()) + " max)", w - 24), left + 12, y + 22, CasinoStyle.WHITE, false);
        drawStatus(g, top + h - 50);
        // Gains : sous le numéro (ils chevauchaient le nom du casino en haut de l'écran).
        CasinoStyle.centered(g, fit("Plein x" + (info.gains().get(0) + 1) + "  Douzaine/Colonne x" + (info.gains().get(7) + 1)
                + "  Chances simples x" + (info.gains().get(1) + 1), w - 16), left + w / 2, top + h - 62, CasinoStyle.MUTED);
        historyPanel(g);
        if (busy()) wheel(g);
    }

    /** Derniers numéros : le plus récent en haut à gauche (entouré d'or), puis de gauche à droite et de haut en bas. */
    private void historyPanel(GuiGraphics g) {
        int px = left + w + 6, py = top;
        g.fill(px - 3, py - 3, px + PANEL_W + 3, py + h + 3, CasinoStyle.FRAME);
        g.fill(px, py, px + PANEL_W, py + h, CasinoStyle.PANEL);
        g.fill(px, py, px + 4, py + 38, CasinoStyle.GOLD);
        CasinoStyle.scaled(g, CasinoStyle.bold("Tirages"), px + 10, py + 8, 1.2f, CasinoStyle.WHITE);
        g.drawString(font, "Derniers numéros", px + 10, py + 25, CasinoStyle.MUTED, false);
        g.fill(px + 8, py + 40, px + PANEL_W - 8, py + 41, CasinoStyle.HOVER);
        if (history.isEmpty()) {
            g.drawString(font, "Aucun tirage", px + 10, py + 50, CasinoStyle.MUTED, false);
            return;
        }
        int cw = 21, ch = 18;
        for (int i = 0; i < history.size() && i < HIST_COLS * HIST_ROWS; i++) {
            int n = history.get(i);
            int x = px + 7 + (i % HIST_COLS) * (cw + 1), y = py + 48 + (i / HIST_COLS) * (ch + 2);
            if (i == 0) g.fill(x - 1, y - 1, x + cw + 1, y + ch + 1, CasinoStyle.GOLD);
            g.fill(x, y, x + cw, y + ch, numberColor(n));
            CasinoStyle.centered(g, String.valueOf(n), x + cw / 2, y + (ch - 8) / 2, CasinoStyle.WHITE);
        }
    }

    /** Disque plein dessiné ligne par ligne. */
    private static void disc(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int half = (int) Math.round(Math.sqrt((double) r * r - (double) dy * dy));
            g.fill(cx - half, cy + dy, cx + half, cy + dy + 1, color);
        }
    }

    /** Roue et bille par-dessus la table pendant le tirage ; le numéro sorti s'affiche à droite. */
    private void wheel(GuiGraphics g) {
        int x0 = left + 6, y0 = top + 46, x1 = left + w - 6, y1 = top + 148;
        g.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, CasinoStyle.GOLD);
        g.fill(x0, y0, x1, y1, 0xFF0E0A34);
        int cx = x0 + 56, cy = (y0 + y1) / 2;
        double c = clock();
        double u = result == null ? -1 : Math.min(1, (c - landStart) / LAND);
        disc(g, cx, cy, 47, 0xFF3A200E);
        disc(g, cx, cy, 44, 0xFF7E4C20);
        disc(g, cx, cy, 39, 0xFF1A1A1E);
        double wa = wheelAngle(c) - Math.PI / 2;
        int win = revealed && result != null ? pocketOf(result.number()) : -1;
        for (int i = 0; i < WHEEL.length; i++) {
            double a = wa + i * STEP;
            int px = cx + (int) Math.round(Math.cos(a) * 33), py = cy + (int) Math.round(Math.sin(a) * 33);
            if (i == win) g.fill(px - 5, py - 5, px + 5, py + 5, CasinoStyle.GOLD);
            g.fill(px - 3, py - 3, px + 3, py + 3, numberColor(WHEEL[i]));
        }
        disc(g, cx, cy, 24, 0xFF2A2A30);
        disc(g, cx, cy, 10, 0xFFB8A040);
        // Bille : sur la piste extérieure tant que ça tourne, puis elle descend vers sa case.
        double ba, br;
        if (u < 0) { ba = BALL_SPEED * c; br = 41; }
        else {
            ba = ballAngle(u);
            double d = Math.min(1, Math.max(0, (u - 0.5) / 0.35));
            br = 41 - 8 * d * d * (3 - 2 * d) + Math.sin(u * 50) * 1.2 * (1 - u);
        }
        int bx = cx + (int) Math.round(Math.cos(ba - Math.PI / 2) * br), by = cy + (int) Math.round(Math.sin(ba - Math.PI / 2) * br);
        g.fill(bx - 2, by - 2, bx + 2, by + 2, 0xFFFFFFFF);
        // Texte
        int tx = x0 + 126;
        boolean done = revealed && result != null;
        int n = done ? result.number() : shownNumber;
        if (n >= 0) {
            g.fill(tx, y0 + 18, tx + 50, y0 + 62, CasinoStyle.GOLD);
            g.fill(tx + 2, y0 + 20, tx + 48, y0 + 60, numberColor(n));
            String s = String.valueOf(n);
            CasinoStyle.scaled(g, Component.literal(s), tx + 25 - font.width(s), y0 + 34, 2f, CasinoStyle.WHITE);
        }
        if (done) {
            g.drawString(font, result.win() > 0 ? "Gagné : +" + CasinoStyle.euros(result.win()) : "Perdu", tx + 62, y0 + 34,
                    result.win() > 0 ? CasinoStyle.OK : CasinoStyle.ALERT, false);
        } else {
            g.drawString(font, "Rien ne va plus...", tx + 56, y0 + 34, CasinoStyle.WARN, false);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!busy()) {
            int y = top + 50 + 4 * CELL_H + 2 + CELL_H + 8;
            if (button == 0 && chipClick(left + 48, y, mx, my)) return true;
            for (Cell c : cells) {
                if (mx < c.x || mx >= c.x + c.w || my < c.y || my >= c.y + c.h) continue;
                int k = key(c.type, c.value);
                if (button == 1) bets.remove(k);
                else if (button == 0) {
                    if (total() + chip > info.max()) { message(false, "Mise totale maximale atteinte."); return true; }
                    bets.merge(k, chip, Long::sum);
                    status = "";
                }
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }
}
