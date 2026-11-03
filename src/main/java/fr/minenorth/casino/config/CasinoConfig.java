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

    public Caisse caisse = new Caisse();
    public Slots machine_a_sous = new Slots();
    public Roulette roulette = new Roulette();
    public Blackjack blackjack = new Blackjack();
    public Poker poker = new Poker();
    public Jackpot jackpot = new Jackpot();
    public Coqs combats_de_coqs = new Coqs();

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

    /** Caisse du casino : achat et rachat des jetons (1, 5, 25, 100 et 500 €). Les jeux se jouent uniquement avec des jetons. */
    public static final class Caisse {
        /** Achat minimal / maximal par opération, en euros entiers. */
        public double achat_min_euros = 1;
        public double achat_max_euros = 10000;
        public List<Double> achats_rapides_euros = new ArrayList<>(List.of(10.0, 50.0, 100.0, 500.0, 1000.0));
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

    /** Texas Hold'em No Limit entre joueurs (table de 6) : le casino ne joue pas, il prend une commission sur les pots. */
    public static final class Poker {
        public double petite_blind_euros = 1;
        public double grosse_blind_euros = 2;
        /** Cave (jetons apportés à table) minimale et maximale, en grosses blinds. */
        public int cave_min_blinds = 20;
        public int cave_max_blinds = 100;
        /** Commission du croupier (rake) en % du pot, plafonnée ; prise seulement si la main voit le flop (« pas de flop, pas de commission »). */
        public double commission_pourcent = 5;
        public double commission_max_euros = 20;
        /** Temps pour jouer ; écoulé : check si possible, sinon couché (2 fois de suite : le joueur est levé de table). */
        public int temps_par_action_secondes = 30;
        /** Pause entre deux mains (résultat affiché). */
        public int pause_entre_mains_secondes = 6;
    }

    /** Jackpot progressif des machines à sous : une part de chaque mise l'alimente, 3 symboles les plus rares (le dernier de la liste) le remportent. */
    public static final class Jackpot {
        public boolean actif = true;
        /** Part de chaque mise ajoutée au jackpot, en %. */
        public double part_des_mises_pourcent = 1;
        /** Montant de départ (et de remise à zéro après un gain), en euros. Payé par l'entreprise comme les autres gains. */
        public double montant_depart_euros = 500;
        /** Les gains d'au moins ce montant sont annoncés aux joueurs proches du casino. */
        public double annonce_gros_gain_euros = 500;
        public int rayon_annonce_blocs = 48;
    }

    /**
     * Combats de coqs clandestins : paris en espèces, au pari mutuel (les perdants paient les gagnants, la maison prend une commission
     * sur la mise des perdants). L'arène change d'emplacement comme les boutiques illégales, la police peut la démanteler.
     */
    public static final class Coqs {
        public double mise_min_euros = 10;
        public double mise_max_euros = 2000;
        public List<Double> mises_rapides_euros = new ArrayList<>(List.of(10.0, 50.0, 100.0, 500.0));
        /** Durée des paris avant le combat ; le compte à rebours part au premier pari. */
        public int duree_paris_secondes = 60;
        /** Pause après un combat. */
        public int pause_secondes = 20;
        /** Commission de l'organisateur, en % des mises perdantes. */
        public double commission_pourcent = 10;
        /**
         * Nom du gang qui tient l'arène (mod Portes). Ses membres présents récupèrent la caisse (les commissions, en espèces).
         * Vide : les commissions partent au trésor public.
         */
        public String gang_organisateur = "";
        /** Minutes entre deux déplacements de l'arène (s'il y a au moins 2 emplacements posés). */
        public int deplacement_minutes = 45;
        /** Pas de déplacement si un joueur est à moins de ce rayon de l'arène ouverte (ni pendant un combat). */
        public int rayon_presence_blocs = 24;
        /** Probabilité (0 à 1) qu'un témoin prévienne la police au début d'un combat. */
        public double chance_alerte_police = 0.2;
        /** true : seul un policier EN SERVICE peut démanteler l'arène. */
        public boolean police_en_service = true;
        /** Après une descente de police, plus aucune arène n'ouvre pendant ce temps. */
        public int fermeture_apres_descente_minutes = 60;
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
        if (c.caisse == null) c.caisse = new Caisse();
        Caisse cs = c.caisse;
        cs.achat_min_euros = Math.max(1, Math.round(cs.achat_min_euros));
        cs.achat_max_euros = Math.max(cs.achat_min_euros, Math.round(cs.achat_max_euros));
        cs.achats_rapides_euros = chips(cs.achats_rapides_euros, cs.achat_min_euros, cs.achat_max_euros);
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
        // Roulette : le jeton de 1 € est toujours disponible (mise minimale 1 €), même avec une ancienne config qui ne l'avait pas.
        r.mise_min_euros = Math.min(r.mise_min_euros, 1);
        if (r.mises_rapides_euros == null) r.mises_rapides_euros = new ArrayList<>();
        if (!r.mises_rapides_euros.contains(1.0)) r.mises_rapides_euros = new ArrayList<>(r.mises_rapides_euros) {{ add(1.0); }};
        r.mises_rapides_euros = chips(r.mises_rapides_euros, r.mise_min_euros, r.mise_max_euros);
        while (r.mises_rapides_euros.size() > 5) r.mises_rapides_euros.remove(1);   // la rangée de boutons de la roulette en contient 5 au plus
        b.mises_rapides_euros = chips(b.mises_rapides_euros, b.mise_min_euros, b.mise_max_euros);
        if (c.poker == null) c.poker = new Poker();
        Poker p = c.poker;
        p.petite_blind_euros = Math.max(1, Math.round(p.petite_blind_euros));
        p.grosse_blind_euros = Math.max(p.petite_blind_euros, Math.round(p.grosse_blind_euros));
        p.cave_min_blinds = Math.max(5, Math.min(500, p.cave_min_blinds));
        p.cave_max_blinds = Math.max(p.cave_min_blinds, Math.min(1000, p.cave_max_blinds));
        p.commission_pourcent = Math.max(0, Math.min(25, p.commission_pourcent));
        p.commission_max_euros = Math.max(0, Math.round(p.commission_max_euros));
        p.temps_par_action_secondes = Math.max(10, Math.min(120, p.temps_par_action_secondes));
        p.pause_entre_mains_secondes = Math.max(2, Math.min(30, p.pause_entre_mains_secondes));
        if (c.jackpot == null) c.jackpot = new Jackpot();
        Jackpot j = c.jackpot;
        j.part_des_mises_pourcent = Math.max(0, Math.min(10, j.part_des_mises_pourcent));
        j.montant_depart_euros = Math.max(0, Math.round(j.montant_depart_euros));
        j.annonce_gros_gain_euros = Math.max(0, j.annonce_gros_gain_euros);
        j.rayon_annonce_blocs = Math.max(8, Math.min(256, j.rayon_annonce_blocs));
        if (c.combats_de_coqs == null) c.combats_de_coqs = new Coqs();
        Coqs k = c.combats_de_coqs;
        k.mise_min_euros = Math.max(1, Math.round(k.mise_min_euros));
        k.mise_max_euros = Math.max(k.mise_min_euros, Math.round(k.mise_max_euros));
        k.mises_rapides_euros = chips(k.mises_rapides_euros, k.mise_min_euros, k.mise_max_euros);
        while (k.mises_rapides_euros.size() > 5) k.mises_rapides_euros.remove(1);
        k.duree_paris_secondes = Math.max(15, Math.min(600, k.duree_paris_secondes));
        k.pause_secondes = Math.max(5, Math.min(600, k.pause_secondes));
        k.commission_pourcent = Math.max(0, Math.min(50, k.commission_pourcent));
        if (k.gang_organisateur == null) k.gang_organisateur = "";
        k.deplacement_minutes = Math.max(5, k.deplacement_minutes);
        k.rayon_presence_blocs = Math.max(4, Math.min(128, k.rayon_presence_blocs));
        k.chance_alerte_police = Math.max(0, Math.min(1, k.chance_alerte_police));
        k.fermeture_apres_descente_minutes = Math.max(0, k.fermeture_apres_descente_minutes);
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
