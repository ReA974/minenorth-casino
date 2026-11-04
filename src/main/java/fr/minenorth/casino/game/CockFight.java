package fr.minenorth.casino.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Un combat de coqs : deux coqs tirés au hasard (nom, plumage, force, agilité, endurance) et le déroulé complet du combat,
 * calculé d'avance côté serveur au coup d'envoi. La probabilité de victoire (pour les cotes) est estimée en simulant le combat.
 */
public final class CockFight {
    public record Cock(String name, int color, int force, int agility, int stamina) {
        public int hp() { return 20 + stamina * 3; }
    }

    /** Un échange : l'attaquant (0 = coq rouge, 1 = coq bleu), les dégâts (0 = esquive), coup critique, points de vie après le coup. */
    public record Exchange(int attacker, int damage, boolean crit, int hpA, int hpB) {}

    private static final String[] FIRST = {"Le Baron", "Caïd", "Tonnerre", "Brutus", "El Diablo", "Rocco", "Napoléon", "Le Boucher",
            "Mistral", "Gringo", "Pistolero", "Titan", "Kaiser", "Bandit", "Zorro", "Fuego", "Le Duc", "Samouraï", "Vandal", "Ouragan"};
    private static final String[] SECOND = {"de Marseille", "du Nord", "d'Acier", "le Rouge", "le Fou", "Noir", "des Docks", "Junior",
            "le Terrible", "du Port", "Royal", "Sauvage"};
    private static final int[] COLORS = {0xC0392B, 0x2C3E50, 0xE67E22, 0xF1C40F, 0x8E44AD, 0xECF0F1, 0x6E4B2A, 0x16A085};

    public final Cock a, b;
    /** Probabilité que le coq rouge (a) gagne. */
    public final double pA;

    public CockFight(Random rng) { this(rng, null, null); }

    /** Combat entre deux combattants donnés ; null = coq de la maison tiré au hasard. */
    public CockFight(Random rng, Cock fa, Cock fb) {
        a = fa != null ? fa : random(rng, fb == null ? null : fb.name());
        b = fb != null ? fb : random(rng, a.name());
        Random sim = new Random(rng.nextLong());
        int wins = 0, n = 600;
        for (int i = 0; i < n; i++) if (winner(simulate(sim)) == 0) wins++;
        pA = Math.max(0.05, Math.min(0.95, wins / (double) n));
    }

    private static Cock random(Random rng, String avoid) { return random(rng, avoid == null ? java.util.Set.of() : java.util.Set.of(avoid)); }

    /** Coq tiré au hasard (poulailler d'une arène), d'un autre nom que ceux de {@code taken}. */
    public static Cock random(Random rng, java.util.Set<String> taken) {
        String name;
        int tries = 0;
        do name = FIRST[rng.nextInt(FIRST.length)] + (rng.nextInt(3) == 0 ? " " + SECOND[rng.nextInt(SECOND.length)] : "");
        while (taken.contains(name) && ++tries < 50);
        // 15 points répartis entre les trois qualités (chacune de 2 à 9) : aucun coq n'est imbattable.
        int f = 2 + rng.nextInt(6), ag = 2 + rng.nextInt(6);
        int st = Math.max(2, Math.min(9, 15 - f - ag + rng.nextInt(3) - 1));
        return new Cock(name, COLORS[rng.nextInt(COLORS.length)], f, ag, st);
    }

    /** Déroulé d'un combat jusqu'à ce qu'un coq tombe (au plus 60 échanges : ensuite celui qui a le moins de vie perd). */
    public List<Exchange> simulate(Random rng) {
        List<Exchange> out = new ArrayList<>();
        int ha = a.hp(), hb = b.hp();
        for (int k = 0; k < 60 && ha > 0 && hb > 0; k++) {
            // l'initiative revient plus souvent au coq le plus agile
            int att = rng.nextInt(a.agility() + b.agility()) < a.agility() ? 0 : 1;
            Cock at = att == 0 ? a : b, df = att == 0 ? b : a;
            boolean dodge = rng.nextInt(100) < 8 + df.agility() * 3;
            boolean crit = !dodge && rng.nextInt(100) < 6 + at.force();
            int dmg = dodge ? 0 : 1 + rng.nextInt(2 + at.force()) + (crit ? 4 + at.force() : 0);
            if (att == 0) hb = Math.max(0, hb - dmg); else ha = Math.max(0, ha - dmg);
            out.add(new Exchange(att, dmg, crit, ha, hb));
        }
        if (ha > 0 && hb > 0) {
            // temps écoulé : le plus abîmé s'effondre
            if (ha * b.hp() >= hb * a.hp()) hb = 0; else ha = 0;
            out.add(new Exchange(ha > 0 ? 0 : 1, 0, false, ha, hb));
        }
        return out;
    }

    /** Vainqueur d'un déroulé : 0 (rouge) ou 1 (bleu). */
    public static int winner(List<Exchange> script) {
        Exchange last = script.get(script.size() - 1);
        return last.hpB() <= 0 ? 0 : 1;
    }
}
