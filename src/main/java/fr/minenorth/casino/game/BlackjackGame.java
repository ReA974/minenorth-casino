package fr.minenorth.casino.game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Une main de blackjack contre le croupier. Carte = 0..51 : rang = c % 13 (0 = as, 10 = valet, 11 = dame, 12 = roi), couleur = c / 13. */
public final class BlackjackGame {
    public long bet;
    public final List<Integer> player = new ArrayList<>();
    public final List<Integer> dealer = new ArrayList<>();
    private final ArrayDeque<Integer> shoe = new ArrayDeque<>();
    public boolean doubled;
    public boolean finished;
    public long payout;
    public String outcome = "";

    public BlackjackGame(long bet, Random rng, int decks) {
        this.bet = bet;
        List<Integer> cards = new ArrayList<>();
        for (int d = 0; d < decks; d++) for (int c = 0; c < 52; c++) cards.add(c);
        Collections.shuffle(cards, rng);
        shoe.addAll(cards);
    }

    public int draw() { return shoe.poll(); }

    /** Valeur d'une carte, l'as comptant 11 (réduit à 1 dans {@link #total}). */
    public static int value(int card) {
        int r = card % 13;
        return r == 0 ? 11 : Math.min(10, r + 1);
    }

    public static int total(List<Integer> hand) {
        int t = 0, aces = 0;
        for (int c : hand) { t += value(c); if (c % 13 == 0) aces++; }
        while (t > 21 && aces > 0) { t -= 10; aces--; }
        return t;
    }

    /** Main « soft » : un as est compté 11. */
    public static boolean soft(List<Integer> hand) {
        int t = 0, aces = 0;
        for (int c : hand) { t += value(c); if (c % 13 == 0) aces++; }
        while (t > 21 && aces > 0) { t -= 10; aces--; }
        return aces > 0;
    }

    public static boolean blackjack(List<Integer> hand) { return hand.size() == 2 && total(hand) == 21; }

    public boolean playerBlackjack() { return !doubled && blackjack(player); }

    public void dealerPlay(boolean hitSoft17) {
        while (true) {
            int t = total(dealer);
            if (t < 17 || (t == 17 && hitSoft17 && soft(dealer))) dealer.add(draw());
            else break;
        }
    }

    /** Règle la main : renseigne payout (total rendu au joueur, mise comprise) et outcome. */
    public void settle(double bjGain) {
        finished = true;
        int p = total(player), d = total(dealer);
        boolean pbj = playerBlackjack(), dbj = blackjack(dealer);
        if (p > 21) { payout = 0; outcome = "Vous dépassez 21."; }
        else if (pbj && dbj) { payout = bet; outcome = "Blackjack des deux côtés : égalité."; }
        else if (pbj) { payout = bet + Math.round(bet * bjGain); outcome = "BLACKJACK !"; }
        else if (dbj) { payout = 0; outcome = "Blackjack du croupier."; }
        else if (d > 21) { payout = bet * 2; outcome = "Le croupier dépasse 21."; }
        else if (p > d) { payout = bet * 2; outcome = "Vous gagnez."; }
        else if (p == d) { payout = bet; outcome = "Égalité."; }
        else { payout = 0; outcome = "Le croupier gagne."; }
    }
}
