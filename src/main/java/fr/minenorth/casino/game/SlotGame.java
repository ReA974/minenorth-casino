package fr.minenorth.casino.game;

import fr.minenorth.casino.config.CasinoConfig.Symbol;

import java.util.List;
import java.util.Random;

/** Machine à sous : trois rouleaux, tirage pondéré par symbole. */
public final class SlotGame {
    private SlotGame() {}

    public static int pick(Random rng, List<Symbol> syms) {
        int total = 0;
        for (Symbol s : syms) total += s.poids;
        int r = rng.nextInt(total);
        for (int i = 0; i < syms.size(); i++) {
            r -= syms.get(i).poids;
            if (r < 0) return i;
        }
        return syms.size() - 1;
    }

    public static int[] spin(Random rng, List<Symbol> syms) {
        return new int[]{pick(rng, syms), pick(rng, syms), pick(rng, syms)};
    }

    /** Gain total (mise comprise) en multiple de la mise ; 0 = perdu. */
    public static double multiplier(int[] reels, List<Symbol> syms) {
        if (reels[0] == reels[1] && reels[1] == reels[2]) return syms.get(reels[0]).x3;
        double best = 0;
        for (int i = 0; i < 3; i++) {
            int n = 0;
            for (int j = 0; j < 3; j++) if (reels[j] == reels[i]) n++;
            if (n == 2) best = Math.max(best, syms.get(reels[i]).x2);
        }
        return best;
    }

    /** Plus gros multiple possible (sert à vérifier que la caisse peut payer). */
    public static double maxMultiplier(List<Symbol> syms) {
        double m = 0;
        for (Symbol s : syms) m = Math.max(m, Math.max(s.x3, s.x2));
        return m;
    }

    /** Taux de retour au joueur théorique (1.0 = 100 %), par énumération exacte. */
    public static double rtp(List<Symbol> syms) {
        double w = 0;
        for (Symbol s : syms) w += s.poids;
        double r = 0;
        int n = syms.size();
        for (int a = 0; a < n; a++)
            for (int b = 0; b < n; b++)
                for (int c = 0; c < n; c++) {
                    double p = syms.get(a).poids / w * syms.get(b).poids / w * syms.get(c).poids / w;
                    r += p * multiplier(new int[]{a, b, c}, syms);
                }
        return r;
    }
}
