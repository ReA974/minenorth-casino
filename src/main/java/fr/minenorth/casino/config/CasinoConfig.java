package fr.minenorth.casino.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration : config/minenorth_casino.json (rechargée avec /casinoreload).
 * Le casino est relié à UNE entreprise : les mises vont sur son compte, les gains en sortent.
 */
public final class CasinoConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static CasinoConfig current = new CasinoConfig();

    public String _aide = "entreprise = nom exact de l'entreprise (ou entreprise_id = son numero, prioritaire s'il est >= 0). "
            + "Les mises sont versees sur son compte bancaire et les gains en sortent : si la caisse est trop basse pour couvrir "
            + "le gain maximum possible, la mise est refusee. Montants en euros. Rechargez avec /casinoreload.";
    /** Nom de l'entreprise (insensible à la casse). Vide = casino hors service. */
    public String entreprise = "";
    /** Numéro de l'entreprise ; prioritaire sur le nom s'il est ≥ 0. */
    public int entreprise_id = -1;
    /** true : le casino ne fonctionne que si l'entreprise est ouverte (au moins un membre connecté). */
    public boolean exiger_entreprise_ouverte = false;
    /** true : seuls les OP posent et cassent les machines et tables. */
    public boolean pose_op_seulement = true;
    /** Délai minimal entre deux parties d'un même joueur (anti-spam), en millisecondes. */
    public int delai_entre_parties_ms = 1500;
    /** Distance maximale entre le joueur et la machine / table pour jouer. */
    public int distance_max_blocs = 8;

    public Slots machine_a_sous = new Slots();
    public Roulette roulette = new Roulette();
    public Blackjack blackjack = new Blackjack();

    public static final class Symbol {
        /** Objet Minecraft affiché (ex. minecraft:diamond). */
        public String item = "minecraft:apple";
        public int poids = 1;
        /** Gain TOTAL (mise comprise) si les 3 rouleaux sont identiques, en multiple de la mise. */
        public double x3 = 0;
        /** Gain total si exactement 2 rouleaux affichent ce symbole (0 = rien). */
        public double x2 = 0;
        public Symbol() {}
        Symbol(String item, int poids, double x3, double x2) { this.item = item; this.poids = poids; this.x3 = x3; this.x2 = x2; }
    }

    public static final class Slots {
        public double mise_min_euros = 1;
        public double mise_max_euros = 500;
        public List<Double> mises_rapides_euros = new ArrayList<>(List.of(1.0, 5.0, 25.0, 100.0));
        public List<Symbol> symboles = new ArrayList<>(List.of(
                new Symbol("minecraft:sweet_berries", 30, 7, 1),
                new Symbol("minecraft:melon_slice", 24, 10, 0),
                new Symbol("minecraft:gold_nugget", 18, 18, 0),
                new Symbol("minecraft:emerald", 10, 40, 0),
                new Symbol("minecraft:diamond", 5, 100, 0),
                new Symbol("minecraft:nether_star", 1, 500, 0)));
    }

    public static final class Roulette {
        /** Mise minimale par pari. */
        public double mise_min_euros = 1;
        /** Total maximum misé sur un tour. */
        public double mise_max_euros = 1000;
        public List<Double> mises_rapides_euros = new ArrayList<>(List.of(1.0, 5.0, 25.0, 100.0));
        public int max_paris = 30;
        /** Gain net en multiple de la mise (la mise est rendue en plus) : plein 35 = 35 contre 1. */
        public Map<String, Integer> gains = defaultGains();

        static Map<String, Integer> defaultGains() {
            Map<String, Integer> m = new LinkedHashMap<>();
            m.put("plein", 35); m.put("douzaine", 2); m.put("colonne", 2);
            m.put("rouge", 1); m.put("noir", 1); m.put("pair", 1); m.put("impair", 1); m.put("manque", 1); m.put("passe", 1);
            return m;
        }
    }

    public static final class Blackjack {
        public double mise_min_euros = 5;
        public double mise_max_euros = 1000;
        public List<Double> mises_rapides_euros = new ArrayList<>(List.of(5.0, 25.0, 100.0, 500.0));
        /** Gain net d'un blackjack naturel (1.5 = 3 contre 2). */
        public double gain_blackjack = 1.5;
        /** true : le croupier tire sur un 17 « soft » (avec un as compté 11). */
        public boolean croupier_tire_soft17 = false;
        public int nombre_de_jeux = 6;
    }

    public static CasinoConfig get() { return current; }

    private static Path file() { return FMLPaths.CONFIGDIR.get().resolve("minenorth_casino.json"); }

    public static boolean load() {
        // Config côté serveur uniquement : le client ne crée ni ne lit aucun fichier.
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist != net.minecraftforge.api.distmarker.Dist.DEDICATED_SERVER) return true;
        Path f = file();
        boolean ok = true;
        try {
            if (Files.exists(f)) {
                CasinoConfig c = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), CasinoConfig.class);
                if (c != null) current = c;
            }
        } catch (Exception e) {
            ok = false;
        }
        sanitize(current);
        // On ne réécrit pas un fichier illisible : l'admin peut ainsi corriger sa faute de frappe.
        if (ok) {
            try {
                Files.createDirectories(f.getParent());
                Files.writeString(f, GSON.toJson(current), StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
        }
        return ok;
    }

    private static void sanitize(CasinoConfig c) {
        if (c.entreprise == null) c.entreprise = "";
        c.delai_entre_parties_ms = Math.max(0, c.delai_entre_parties_ms);
        c.distance_max_blocs = Math.max(2, c.distance_max_blocs);
        if (c.machine_a_sous == null) c.machine_a_sous = new Slots();
        if (c.roulette == null) c.roulette = new Roulette();
        if (c.blackjack == null) c.blackjack = new Blackjack();
        Slots s = c.machine_a_sous;
        if (s.symboles == null) s.symboles = new Slots().symboles;
        s.symboles.removeIf(x -> x == null || x.item == null || x.poids <= 0);
        if (s.symboles.size() < 2) s.symboles = new Slots().symboles;
        if (s.symboles.size() > 12) s.symboles = new ArrayList<>(s.symboles.subList(0, 12));
        for (Symbol y : s.symboles) { y.x3 = Math.max(0, y.x3); y.x2 = Math.max(0, y.x2); }
        s.mise_min_euros = Math.max(0.01, s.mise_min_euros);
        s.mise_max_euros = Math.max(s.mise_min_euros, s.mise_max_euros);
        Roulette r = c.roulette;
        r.mise_min_euros = Math.max(0.01, r.mise_min_euros);
        r.mise_max_euros = Math.max(r.mise_min_euros, r.mise_max_euros);
        r.max_paris = Math.max(1, Math.min(60, r.max_paris));
        if (r.gains == null) r.gains = Roulette.defaultGains();
        Map<String, Integer> def = Roulette.defaultGains();
        for (String k : def.keySet()) r.gains.putIfAbsent(k, def.get(k));
        r.gains.replaceAll((k, v) -> v == null ? 0 : Math.max(0, Math.min(1000, v)));
        Blackjack b = c.blackjack;
        b.mise_min_euros = Math.max(0.01, b.mise_min_euros);
        b.mise_max_euros = Math.max(b.mise_min_euros, b.mise_max_euros);
        b.gain_blackjack = Math.max(0, Math.min(10, b.gain_blackjack));
        b.nombre_de_jeux = Math.max(1, Math.min(8, b.nombre_de_jeux));
        s.mises_rapides_euros = chips(s.mises_rapides_euros, s.mise_min_euros, s.mise_max_euros);
        r.mises_rapides_euros = chips(r.mises_rapides_euros, r.mise_min_euros, r.mise_max_euros);
        b.mises_rapides_euros = chips(b.mises_rapides_euros, b.mise_min_euros, b.mise_max_euros);
    }

    private static List<Double> chips(List<Double> in, double min, double max) {
        List<Double> out = new ArrayList<>();
        if (in != null) for (Double d : in) if (d != null && d >= min && d <= max && !out.contains(d) && out.size() < 6) out.add(d);
        if (out.isEmpty()) out.add(min);
        Collections.sort(out);
        return out;
    }

    public static long cents(double euros) { return Math.max(0, Math.round(euros * 100.0)); }

    public static List<Long> centsList(List<Double> euros) {
        List<Long> out = new ArrayList<>();
        for (Double d : euros) out.add(cents(d));
        return out;
    }
}
