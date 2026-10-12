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
    private final List<Cell> cells = new ArrayList<>();
    private final Random anim = new Random();
    private boolean waiting;
    private int ticks, shownNumber = -1;
    private ModNetwork.RouletteResultPacket result;
    private CasinoButton spinBtn, clearBtn;
    private int lastNumber = -1;

    public RouletteScreen(OpenPacket info) { super(info, "Roulette", 300, 268); }

    private static int key(int type, int value) { return type * 100 + value; }

    private int numberColor(int n) { return n == 0 ? CasinoStyle.FELT : RouletteGame.isRed(n) ? 0xFFB0202C : 0xFF111111; }

    @Override
    protected void init() {
        super.init();
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
        status = "";
        ModNetwork.CHANNEL.sendToServer(new ModNetwork.RouletteSpinPacket(list));
    }

    public void result(ModNetwork.RouletteResultPacket p) {
        result = p;
        waiting = false;
        ticks = 0;
    }

    @Override
    public void message(boolean ok, String msg) {
        super.message(ok, msg);
        if (!ok && waiting) waiting = false;
    }

    @Override
    public void tick() {
        if (!busy()) return;
        ticks++;
        if (ticks % 2 == 0) shownNumber = anim.nextInt(37);
        if (result != null && ticks >= 36) {
            shownNumber = lastNumber = result.number();
            if (result.win() > 0) { status = "Le " + lastNumber + " : vous gagnez " + CasinoStyle.euros(result.win()) + " !"; statusOk = true; }
            else { status = "Le " + lastNumber + " : perdu."; statusOk = false; }
            result = null;
            bets.clear();
        } else if (result == null && ticks > 200) waiting = false;   // pas de réponse du serveur
    }

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
        g.drawString(font, "Mise totale : " + CasinoStyle.euros(total()) + "   (" + CasinoStyle.euros(info.min()) + " par pari, "
                + CasinoStyle.euros(info.max()) + " max)", left + 12, y + 22, CasinoStyle.WHITE, false);
        // Numéro
        int shown = busy() ? shownNumber : lastNumber;
        if (shown >= 0) {
            g.fill(left + w / 2 - 22, y + 36, left + w / 2 + 22, y + 66, CasinoStyle.GOLD);
            g.fill(left + w / 2 - 20, y + 38, left + w / 2 + 20, y + 64, numberColor(shown));
            CasinoStyle.scaled(g, Component.literal(String.valueOf(shown)), left + w / 2 - font.width(String.valueOf(shown)) - 2 + (shown < 10 ? 4 : 0), y + 44, 2f, CasinoStyle.WHITE);
        }
        drawStatus(g, top + h - 50);
        CasinoStyle.centered(g, "Plein x" + (info.gains().get(0) + 1) + "  Douzaine/Colonne x" + (info.gains().get(7) + 1)
                + "  Chances simples x" + (info.gains().get(1) + 1), left + w / 2, top + 27, CasinoStyle.MUTED);
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
