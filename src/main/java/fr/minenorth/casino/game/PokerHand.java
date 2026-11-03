package fr.minenorth.casino.game;

import java.util.List;

/**
 * Évaluation d'une main de Texas Hold'em : meilleure combinaison de 5 cartes parmi 5 à 7. Carte = 0..51 comme au blackjack
 * (rang = c % 13, 0 = as ; couleur = c / 13). Le score est comparable directement : plus grand = meilleure main.
 */
public final class PokerHand {
    private PokerHand() {}

    public static final String[] NAMES = {"Carte haute", "Paire", "Double paire", "Brelan", "Quinte", "Couleur", "Full", "Carré",
            "Quinte flush"};

    /** Valeur de 2 (2) à as (14). */
    static int value(int card) { int r = card % 13; return r == 0 ? 14 : r + 1; }

    /** Catégorie (0 carte haute … 8 quinte flush) d'un score. */
    public static int category(long score) { return (int) (score >> 20); }

    public static String name(long score) { return NAMES[category(score)]; }

    /** Score de la meilleure main de 5 cartes parmi {@code cards} (5 à 7 cartes). */
    public static long best(List<Integer> cards) {
        int n = cards.size();
        long best = -1;
        // Au plus 21 combinaisons de 5 parmi 7 : on les essaie toutes.
        for (int a = 0; a < n; a++) for (int b = a + 1; b < n; b++) for (int c = b + 1; c < n; c++)
            for (int d = c + 1; d < n; d++) for (int e = d + 1; e < n; e++) {
                long s = five(cards.get(a), cards.get(b), cards.get(c), cards.get(d), cards.get(e));
                if (s > best) best = s;
            }
        return best;
    }

    private static long five(int... c) {
        int[] v = new int[5];
        boolean flush = true;
        for (int i = 0; i < 5; i++) { v[i] = value(c[i]); if (c[i] / 13 != c[0] / 13) flush = false; }
        java.util.Arrays.sort(v);
        // décroissant
        for (int i = 0; i < 2; i++) { int t = v[i]; v[i] = v[4 - i]; v[4 - i] = t; }
        boolean straight = true;
        for (int i = 1; i < 5; i++) if (v[i] != v[i - 1] - 1) { straight = false; break; }
        int high = v[0];
        // Quinte « roue » A-2-3-4-5 : l'as compte 1.
        if (!straight && v[0] == 14 && v[1] == 5 && v[2] == 4 && v[3] == 3 && v[4] == 2) { straight = true; high = 5; }
        if (straight && flush) return score(8, high);
        if (straight) return score(4, high);
        // groupes : nombre d'exemplaires puis valeur, triés (le plus gros groupe d'abord)
        int[] count = new int[15];
        for (int x : v) count[x]++;
        int[] order = new int[5];
        int k = 0;
        for (int size = 4; size >= 1; size--) for (int x = 14; x >= 2; x--) if (count[x] == size) for (int i = 0; i < size; i++) order[k++] = x;
        int top = count[order[0]], second = count[order[top]];
        int cat;
        if (top == 4) cat = 7;
        else if (top == 3 && second == 2) cat = 6;
        else if (flush) cat = 5;
        else if (top == 3) cat = 3;
        else if (top == 2 && second == 2) cat = 2;
        else if (top == 2) cat = 1;
        else cat = 0;
        if (cat == 5) return score(5, v);
        return score(cat, order);
    }

    private static long score(int cat, int... kickers) {
        long s = cat;
        for (int i = 0; i < 5; i++) s = (s << 4) | (i < kickers.length ? kickers[i] : 0);
        return s;
    }
}
