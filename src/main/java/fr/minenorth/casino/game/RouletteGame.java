package fr.minenorth.casino.game;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Roulette européenne (0 à 36, un seul zéro). */
public final class RouletteGame {
    private RouletteGame() {}

    public static final int STRAIGHT = 0, RED = 1, BLACK = 2, ODD = 3, EVEN = 4, LOW = 5, HIGH = 6, DOZEN = 7, COLUMN = 8;
    /** Clé de la config pour chaque type de pari, dans l'ordre des constantes ci-dessus. */
    public static final String[] KEYS = {"plein", "rouge", "noir", "impair", "pair", "manque", "passe", "douzaine", "colonne"};
    private static final Set<Integer> REDS = Set.of(1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36);

    public record Bet(int type, int value, long cents) {}

    public static boolean isRed(int n) { return REDS.contains(n); }

    /** Pari valide (type et valeur dans les bornes). */
    public static boolean valid(Bet b) {
        if (b.cents() <= 0) return false;
        return switch (b.type()) {
            case STRAIGHT -> b.value() >= 0 && b.value() <= 36;
            case DOZEN, COLUMN -> b.value() >= 1 && b.value() <= 3;
            case RED, BLACK, ODD, EVEN, LOW, HIGH -> true;
            default -> false;
        };
    }

    public static boolean wins(Bet b, int n) {
        return switch (b.type()) {
            case STRAIGHT -> n == b.value();
            case RED -> n != 0 && isRed(n);
            case BLACK -> n != 0 && !isRed(n);
            case ODD -> n != 0 && n % 2 == 1;
            case EVEN -> n != 0 && n % 2 == 0;
            case LOW -> n >= 1 && n <= 18;
            case HIGH -> n >= 19 && n <= 36;
            case DOZEN -> n != 0 && (n - 1) / 12 == b.value() - 1;
            case COLUMN -> n != 0 && (n - 1) % 3 == b.value() - 1;
            default -> false;
        };
    }

    /** Montant rendu au joueur (mise comprise) pour ce pari si la bille s'arrête sur n. */
    public static long payout(Bet b, int n, Map<String, Integer> gains) {
        if (!wins(b, n)) return 0;
        return b.cents() * (gains.getOrDefault(KEYS[b.type()], 0) + 1L);
    }

    public static long totalPayout(List<Bet> bets, int n, Map<String, Integer> gains) {
        long t = 0;
        for (Bet b : bets) t += payout(b, n, gains);
        return t;
    }

    /** Gain net maximum que la caisse peut devoir payer sur ces paris (sur les 37 issues). */
    public static long maxNet(List<Bet> bets, Map<String, Integer> gains) {
        long stake = 0;
        for (Bet b : bets) stake += b.cents();
        long best = 0;
        for (int n = 0; n <= 36; n++) best = Math.max(best, totalPayout(bets, n, gains) - stake);
        return best;
    }
}
