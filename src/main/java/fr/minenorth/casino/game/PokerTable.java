package fr.minenorth.casino.game;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Une table de Texas Hold'em No Limit : sièges, blinds, tours d'enchères, showdown et pots annexes. Logique pure (aucun objet
 * Minecraft) : le service la pilote, prend et rend les jetons. Montants en centimes, toujours en euros entiers (jetons).
 * Tout est décidé ici, côté serveur : le client ne reçoit que les cartes qu'il a le droit de voir.
 */
public final class PokerTable {
    public static final int SEATS = 6;

    public enum Phase { WAITING, PREFLOP, FLOP, TURN, RIVER, SHOWDOWN }

    public static final class Seat {
        public final UUID id;
        public final String name;
        public long stack;
        /** Mise du tour d'enchères en cours, et total engagé dans la main. */
        public long bet, committed;
        public final List<Integer> hole = new ArrayList<>();
        public boolean inHand, folded, allIn, acted;
        /** Le joueur se lève à la fin de la main (ou tout de suite s'il n'y joue pas). */
        public boolean leaving;
        /** Coups ratés à la suite (temps écoulé) : à 2, il est levé de table. */
        public int timeouts;
        /** Dernière action affichée (« Relance 40 € », « Couché »…). */
        public String last = "";
        /** Gain de la dernière main (affiché au showdown). */
        public long won;
        public String handName = "";
        public boolean showCards;

        Seat(UUID id, String name, long stack) { this.id = id; this.name = name; this.stack = stack; }

        public boolean canAct() { return inHand && !folded && !allIn; }
    }

    /** Un pot réglé : montant, gagnants, nom de la combinaison. */
    public record PotResult(long amount, List<Integer> winners, String hand) {}

    public final Seat[] seats = new Seat[SEATS];
    public Phase phase = Phase.WAITING;
    public final List<Integer> board = new ArrayList<>();
    private final ArrayDeque<Integer> deck = new ArrayDeque<>();
    public int button = -1, toAct = -1;
    public long currentBet, minRaise;
    public long smallBlind, bigBlind;
    /** Commission (rake) : pourcentage du pot et plafond, prélevés seulement si la main voit le flop. */
    public double rakePct;
    public long rakeCap;
    public long lastRake;
    public final List<PotResult> results = new ArrayList<>();
    public int handNumber;
    private final Random rng;

    public PokerTable(Random rng) { this.rng = rng; }

    // ------------------------------------------------------------------ sièges

    public int seatOf(UUID id) {
        for (int i = 0; i < SEATS; i++) if (seats[i] != null && seats[i].id.equals(id)) return i;
        return -1;
    }

    public boolean sit(int index, UUID id, String name, long stack) {
        if (index < 0 || index >= SEATS || seats[index] != null || seatOf(id) >= 0) return false;
        seats[index] = new Seat(id, name, stack);
        return true;
    }

    /** Joueurs assis. */
    public int seated() { int n = 0; for (Seat s : seats) if (s != null) n++; return n; }

    /** Joueurs qui peuvent jouer la prochaine main (tapis d'au moins une grosse blind, pas en train de partir). */
    public int ready() { int n = 0; for (Seat s : seats) if (s != null && !s.leaving && s.stack >= bigBlind) n++; return n; }

    private int next(int from, java.util.function.Predicate<Seat> ok) {
        for (int k = 1; k <= SEATS; k++) {
            int i = Math.floorMod(from + k, SEATS);
            if (seats[i] != null && ok.test(seats[i])) return i;
        }
        return -1;
    }

    private int inHandCount() { int n = 0; for (Seat s : seats) if (s != null && s.inHand && !s.folded) n++; return n; }

    private int canActCount() { int n = 0; for (Seat s : seats) if (s != null && s.canAct()) n++; return n; }

    public long pot() { long t = 0; for (Seat s : seats) if (s != null) t += s.committed; return t; }

    // ------------------------------------------------------------------ main

    /** Démarre une main : bouton, blinds, deux cartes chacun. Faux s'il n'y a pas deux joueurs prêts. */
    public boolean startHand() {
        if (ready() < 2) return false;
        results.clear();
        board.clear();
        lastRake = 0;
        handNumber++;
        List<Integer> cards = new ArrayList<>();
        for (int c = 0; c < 52; c++) cards.add(c);
        Collections.shuffle(cards, rng);
        deck.clear();
        deck.addAll(cards);
        for (Seat s : seats) {
            if (s == null) continue;
            s.hole.clear(); s.bet = 0; s.committed = 0; s.folded = false; s.allIn = false; s.acted = false;
            s.won = 0; s.handName = ""; s.showCards = false; s.last = "";
            s.inHand = !s.leaving && s.stack >= bigBlind;
        }
        button = next(button, x -> x.inHand);
        int players = inHandCount();
        // Tête-à-tête : le bouton paie la petite blind et parle en premier avant le flop.
        int sb = players == 2 ? button : next(button, x -> x.inHand);
        int bb = next(sb, x -> x.inHand);
        post(sb, smallBlind, "Petite blind");
        post(bb, bigBlind, "Grosse blind");
        for (int r = 0; r < 2; r++) for (int k = 1; k <= SEATS; k++) {
            Seat s = seats[Math.floorMod(button + k, SEATS)];
            if (s != null && s.inHand) s.hole.add(deck.poll());
        }
        currentBet = bigBlind;
        minRaise = bigBlind;
        phase = Phase.PREFLOP;
        toAct = next(bb, Seat::canAct);
        if (toAct < 0 || (canActCount() <= 1 && seats[toAct].bet >= currentBet)) runOut();
        return true;
    }

    private void post(int i, long amount, String label) {
        Seat s = seats[i];
        long a = Math.min(amount, s.stack);
        s.stack -= a; s.bet += a; s.committed += a;
        if (s.stack == 0) s.allIn = true;
        s.last = label + " " + (a / 100) + " €";
    }

    // ------------------------------------------------------------------ actions

    public static final int FOLD = 0, CHECK = 1, CALL = 2, RAISE = 3, ALLIN = 4;

    public long toCall(int i) { return Math.max(0, currentBet - seats[i].bet); }

    /** Relance minimale (montant total de la mise du tour), plafonnée au tapis. */
    public long minRaiseTo(int i) { return Math.min(currentBet + minRaise, seats[i].bet + seats[i].stack); }

    public long maxRaiseTo(int i) { return seats[i].bet + seats[i].stack; }

    /**
     * Joue l'action du joueur au siège {@code i}. {@code raiseTo} : montant total de sa mise sur ce tour (relance). Renvoie un message
     * d'erreur, ou null si l'action est acceptée.
     */
    public String act(int i, int action, long raiseTo) {
        if (phase == Phase.WAITING || phase == Phase.SHOWDOWN) return "Aucune main en cours.";
        if (i != toAct) return "Ce n'est pas votre tour.";
        Seat s = seats[i];
        long call = toCall(i);
        switch (action) {
            case FOLD -> { s.folded = true; s.last = "Couché"; }
            case CHECK -> {
                if (call > 0) return "Impossible de checker : il faut suivre " + (call / 100) + " €.";
                s.last = "Check";
            }
            case CALL -> {
                if (call <= 0) return "Rien à suivre : checkez.";
                long a = Math.min(call, s.stack);
                pay(s, a);
                s.last = s.allIn ? "Tapis " + (s.bet / 100) + " €" : "Suit " + (a / 100) + " €";
            }
            case RAISE, ALLIN -> {
                long max = maxRaiseTo(i);
                long to = action == ALLIN ? max : raiseTo;
                if (to % 100 != 0) return "Les mises se font en euros entiers.";
                if (to <= currentBet) {
                    if (action == ALLIN && to > s.bet) {   // tapis inférieur ou égal à la mise : c'est un « suit » à tapis
                        pay(s, to - s.bet);
                        s.last = "Tapis " + (s.bet / 100) + " €";
                        break;
                    }
                    return "La relance doit dépasser " + (currentBet / 100) + " €.";
                }
                if (to > max) return "Vous n'avez pas assez de jetons.";
                if (to < currentBet + minRaise && to < max) return "Relance minimale : " + ((currentBet + minRaise) / 100) + " €.";
                long raise = to - currentBet;
                boolean full = raise >= minRaise;
                pay(s, to - s.bet);
                if (full) {
                    minRaise = raise;
                    // Une relance complète rouvre les enchères pour tout le monde.
                    for (Seat o : seats) if (o != null && o != s) o.acted = false;
                }
                currentBet = to;
                s.last = s.allIn ? "Tapis " + (to / 100) + " €" : (raise == to ? "Mise " : "Relance à ") + (to / 100) + " €";
            }
            default -> { return "Action inconnue."; }
        }
        s.acted = true;
        s.timeouts = 0;
        advance(i);
        return null;
    }

    private static void pay(Seat s, long a) {
        s.stack -= a; s.bet += a; s.committed += a;
        if (s.stack == 0) s.allIn = true;
    }

    /** Action par défaut quand le temps est écoulé : check si possible, sinon couché. */
    public void timeout() {
        if (toAct < 0) return;
        Seat s = seats[toAct];
        int missed = s.timeouts + 1;
        act(toAct, toCall(toAct) > 0 ? FOLD : CHECK, 0);
        s.timeouts = missed;
        if (missed >= 2) s.leaving = true;
    }

    /** Le joueur quitte la table en pleine main : il se couche (si c'était son tour, la main continue normalement). */
    public void forceFold(int i) {
        Seat s = seats[i];
        s.leaving = true;
        if (!s.inHand || s.folded || phase == Phase.WAITING || phase == Phase.SHOWDOWN) return;
        if (i == toAct) { act(i, FOLD, 0); return; }
        s.folded = true;
        s.last = "Couché";
        if (inHandCount() <= 1) finishUncontested();
        else if (roundDone()) nextStreet();
    }

    private boolean roundDone() {
        for (Seat s : seats) if (s != null && s.canAct() && (!s.acted || s.bet < currentBet)) return false;
        return true;
    }

    private void advance(int from) {
        if (inHandCount() <= 1) { finishUncontested(); return; }
        if (roundDone()) { nextStreet(); return; }
        toAct = next(from, x -> x.canAct() && (!x.acted || x.bet < currentBet));
        if (toAct < 0) nextStreet();
    }

    private void nextStreet() {
        for (Seat s : seats) if (s != null) { s.bet = 0; s.acted = false; }
        currentBet = 0;
        minRaise = bigBlind;
        switch (phase) {
            case PREFLOP -> { deal(3); phase = Phase.FLOP; }
            case FLOP -> { deal(1); phase = Phase.TURN; }
            case TURN -> { deal(1); phase = Phase.RIVER; }
            default -> { showdown(); return; }
        }
        // Plus personne (ou un seul joueur) ne peut miser : on retourne toutes les cartes jusqu'à la rivière.
        if (canActCount() <= 1) { runOut(); return; }
        toAct = next(button, Seat::canAct);
    }

    private void runOut() {
        for (Seat s : seats) if (s != null) { s.bet = 0; s.acted = false; }
        while (board.size() < 5) board.add(deck.poll());
        phase = Phase.RIVER;
        showdown();
    }

    private void deal(int n) { for (int k = 0; k < n; k++) board.add(deck.poll()); }

    // ------------------------------------------------------------------ fin de main

    /** Tous les autres se sont couchés : le dernier joueur ramasse le pot sans montrer (pas de commission sans flop). */
    private void finishUncontested() {
        int w = next(-1, x -> x.inHand && !x.folded);
        long total = pot();
        long rake = board.isEmpty() ? 0 : rake(total);
        if (w >= 0) {
            seats[w].stack += total - rake;
            seats[w].won = total - rake;
            results.add(new PotResult(total - rake, List.of(w), ""));
        }
        lastRake = rake;
        end();
    }

    private long rake(long total) {
        if (rakePct <= 0) return 0;
        long r = (long) Math.floor(total * rakePct / 100.0 / 100.0) * 100;   // euros entiers
        return Math.max(0, Math.min(r, rakeCap));
    }

    /**
     * Showdown : pots annexes par paliers d'engagement. Chaque pot va au(x) meilleur(s) joueur(s) encore en jeu qui y ont contribué ;
     * à égalité le pot est partagé (l'euro restant va au premier gagnant après le bouton). La commission est prise sur le pot principal.
     */
    private void showdown() {
        phase = Phase.SHOWDOWN;
        toAct = -1;
        long[] score = new long[SEATS];
        for (int i = 0; i < SEATS; i++) {
            Seat s = seats[i];
            if (s == null || !s.inHand || s.folded) continue;
            List<Integer> seven = new ArrayList<>(s.hole);
            seven.addAll(board);
            score[i] = PokerHand.best(seven);
            s.handName = PokerHand.name(score[i]);
            s.showCards = true;
        }
        List<Long> levels = new ArrayList<>();
        for (Seat s : seats) if (s != null && s.committed > 0 && !levels.contains(s.committed)) levels.add(s.committed);
        Collections.sort(levels);
        long prev = 0, rakeLeft = rake(pot());
        lastRake = rakeLeft;
        for (long level : levels) {
            long amount = 0;
            int contributors = 0;
            List<Integer> eligible = new ArrayList<>();
            for (int i = 0; i < SEATS; i++) {
                Seat s = seats[i];
                if (s == null || s.committed <= prev) continue;
                amount += Math.min(s.committed, level) - prev;
                contributors++;
                if (s.inHand && !s.folded && s.committed >= level) eligible.add(i);
            }
            prev = level;
            if (amount <= 0) continue;
            if (eligible.isEmpty()) {
                // argent de joueurs couchés au-dessus du dernier tapis : il rejoint le pot précédent
                if (!results.isEmpty()) {
                    PotResult last = results.remove(results.size() - 1);
                    give(last.winners(), amount);
                    results.add(new PotResult(last.amount() + amount, last.winners(), last.hand()));
                }
                continue;
            }
            if (contributors > 1 && rakeLeft > 0) { long r = Math.min(rakeLeft, amount); amount -= r; rakeLeft -= r; }
            long best = -1;
            for (int i : eligible) best = Math.max(best, score[i]);
            List<Integer> winners = new ArrayList<>();
            // ordre : à partir du siège après le bouton (pour l'euro indivisible)
            for (int k = 1; k <= SEATS; k++) {
                int i = Math.floorMod(button + k, SEATS);
                if (eligible.contains(i) && score[i] == best) winners.add(i);
            }
            give(winners, amount);
            results.add(new PotResult(amount, winners, contributors > 1 ? PokerHand.name(best) : ""));
        }
        lastRake -= rakeLeft;
        end();
    }

    private void give(List<Integer> winners, long amount) {
        long euros = amount / 100, share = euros / winners.size(), odd = euros % winners.size();
        for (int k = 0; k < winners.size(); k++) {
            long a = (share + (k < odd ? 1 : 0)) * 100;
            seats[winners.get(k)].stack += a;
            seats[winners.get(k)].won += a;
        }
    }

    private void end() {
        phase = Phase.SHOWDOWN;
        toAct = -1;
        for (Seat s : seats) if (s != null) s.bet = 0;
    }

    /** Après l'affichage du résultat : la table attend la main suivante. */
    public void reset() {
        phase = Phase.WAITING;
        toAct = -1;
        currentBet = 0;
        for (Seat s : seats) if (s != null) { s.inHand = false; s.committed = 0; s.bet = 0; s.folded = false; s.allIn = false; }
    }

    /** Arrêt du serveur en pleine main : chacun récupère ce qu'il a engagé. */
    public void refundHand() {
        if (phase == Phase.WAITING || phase == Phase.SHOWDOWN) return;
        for (Seat s : seats) if (s != null) { s.stack += s.committed; s.committed = 0; s.bet = 0; }
        phase = Phase.WAITING;
    }

    public boolean handRunning() { return phase != Phase.WAITING && phase != Phase.SHOWDOWN; }
}
