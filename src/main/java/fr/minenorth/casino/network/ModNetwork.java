package fr.minenorth.casino.network;

import fr.minenorth.casino.CasinoService;
import fr.minenorth.casino.MineNorthCasino;
import fr.minenorth.casino.client.ClientHandler;
import fr.minenorth.casino.game.RouletteGame;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Réseau du casino. Le serveur décide de tout : le client envoie seulement des intentions (miser, tirer, rester…) et
 * affiche ce que le serveur renvoie. Aucun résultat de jeu n'est calculé côté client.
 */
public final class ModNetwork {
    private ModNetwork() {}

    private static final String PROTOCOL = "4";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MineNorthCasino.MOD_ID, "network"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
    private static int id = 0;

    /** Bornes de taille des listes reçues (rejet avant toute allocation). */
    static final int MAX_BETS = 64, MAX_LIST = 32;

    public static void register() {
        CHANNEL.registerMessage(id++, OpenPacket.class, OpenPacket::encode, OpenPacket::decode, OpenPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, MsgPacket.class, MsgPacket::encode, MsgPacket::decode, MsgPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, SlotSpinPacket.class, SlotSpinPacket::encode, SlotSpinPacket::decode, SlotSpinPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, SlotResultPacket.class, SlotResultPacket::encode, SlotResultPacket::decode, SlotResultPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, RouletteSpinPacket.class, RouletteSpinPacket::encode, RouletteSpinPacket::decode, RouletteSpinPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, RouletteResultPacket.class, RouletteResultPacket::encode, RouletteResultPacket::decode, RouletteResultPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, BjActionPacket.class, BjActionPacket::encode, BjActionPacket::decode, BjActionPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, BjStatePacket.class, BjStatePacket::encode, BjStatePacket::decode, BjStatePacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, ChipsPacket.class, ChipsPacket::encode, ChipsPacket::decode, ChipsPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, HistoryPacket.class, HistoryPacket::encode, HistoryPacket::decode, HistoryPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, CashierActionPacket.class, CashierActionPacket::encode, CashierActionPacket::decode, CashierActionPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, PokerActionPacket.class, PokerActionPacket::encode, PokerActionPacket::decode, PokerActionPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, PokerStatePacket.class, PokerStatePacket::encode, PokerStatePacket::decode, PokerStatePacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, CockActionPacket.class, CockActionPacket::encode, CockActionPacket::decode, CockActionPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, CockStatePacket.class, CockStatePacket::encode, CockStatePacket::decode, CockStatePacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, JackpotPacket.class, JackpotPacket::encode, JackpotPacket::decode, JackpotPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void send(ServerPlayer p, Object packet) { CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), packet); }

    static <T> List<T> readList(FriendlyByteBuf b, Function<FriendlyByteBuf, T> reader, int max) {
        int n = b.readVarInt();
        if (n < 0 || n > max) throw new DecoderException("Liste trop longue : " + n + " (max " + max + ")");
        List<T> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(reader.apply(b));
        return out;
    }

    private static void toClient(Supplier<NetworkEvent.Context> c, Runnable r) {
        c.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> r::run));
        c.get().setPacketHandled(true);
    }

    private static void toServer(Supplier<NetworkEvent.Context> c, java.util.function.Consumer<ServerPlayer> r) {
        c.get().enqueueWork(() -> { ServerPlayer sp = c.get().getSender(); if (sp != null) r.accept(sp); });
        c.get().setPacketHandled(true);
    }

    // ------------------------------------------------------------------ serveur → client

    /**
     * Ouverture d'un jeu. {@code game} : 0 machine à sous, 1 roulette, 2 blackjack, 3 caisse, 4 poker, 5 combats de coqs. Les listes ne
     * servent qu'au jeu concerné (symboles et gains de la machine, gains de la roulette, gain du blackjack). Poker et coqs :
     * {@code bjGain} porte la commission en %.
     */
    public record OpenPacket(int game, String company, long min, long max, List<Long> chips,
                             List<String> items, List<Float> x3, List<Float> x2, List<Integer> gains, float bjGain, boolean soft17) {
        static void encode(OpenPacket p, FriendlyByteBuf b) {
            b.writeVarInt(p.game); b.writeUtf(p.company, 64); b.writeVarLong(p.min); b.writeVarLong(p.max);
            b.writeVarInt(p.chips.size()); for (long l : p.chips) b.writeVarLong(l);
            b.writeVarInt(p.items.size()); for (String s : p.items) b.writeUtf(s, 128);
            b.writeVarInt(p.x3.size()); for (float f : p.x3) b.writeFloat(f);
            b.writeVarInt(p.x2.size()); for (float f : p.x2) b.writeFloat(f);
            b.writeVarInt(p.gains.size()); for (int i : p.gains) b.writeVarInt(i);
            b.writeFloat(p.bjGain); b.writeBoolean(p.soft17);
        }
        static OpenPacket decode(FriendlyByteBuf b) {
            int game = b.readVarInt(); String company = b.readUtf(64); long min = b.readVarLong(), max = b.readVarLong();
            List<Long> chips = readList(b, FriendlyByteBuf::readVarLong, MAX_LIST);
            List<String> items = readList(b, x -> x.readUtf(128), MAX_LIST);
            List<Float> x3 = readList(b, FriendlyByteBuf::readFloat, MAX_LIST);
            List<Float> x2 = readList(b, FriendlyByteBuf::readFloat, MAX_LIST);
            List<Integer> gains = readList(b, FriendlyByteBuf::readVarInt, MAX_LIST);
            return new OpenPacket(game, company, min, max, chips, items, x3, x2, gains, b.readFloat(), b.readBoolean());
        }
        static void handle(OpenPacket p, Supplier<NetworkEvent.Context> c) { toClient(c, () -> ClientHandler.open(p)); }
    }

    /** Message affiché dans l'écran de jeu ouvert (erreur ou information). */
    public record MsgPacket(boolean ok, String message) {
        static void encode(MsgPacket p, FriendlyByteBuf b) { b.writeBoolean(p.ok); b.writeUtf(p.message, 200); }
        static MsgPacket decode(FriendlyByteBuf b) { return new MsgPacket(b.readBoolean(), b.readUtf(200)); }
        static void handle(MsgPacket p, Supplier<NetworkEvent.Context> c) { toClient(c, () -> ClientHandler.message(p.ok, p.message)); }
    }

    public record SlotResultPacket(int a, int b, int c, long bet, long win) {
        static void encode(SlotResultPacket p, FriendlyByteBuf b) {
            b.writeVarInt(p.a); b.writeVarInt(p.b); b.writeVarInt(p.c); b.writeVarLong(p.bet); b.writeVarLong(p.win);
        }
        static SlotResultPacket decode(FriendlyByteBuf b) {
            return new SlotResultPacket(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarLong(), b.readVarLong());
        }
        static void handle(SlotResultPacket p, Supplier<NetworkEvent.Context> c) { toClient(c, () -> ClientHandler.slotResult(p)); }
    }

    public record RouletteResultPacket(int number, long stake, long win) {
        static void encode(RouletteResultPacket p, FriendlyByteBuf b) { b.writeVarInt(p.number); b.writeVarLong(p.stake); b.writeVarLong(p.win); }
        static RouletteResultPacket decode(FriendlyByteBuf b) { return new RouletteResultPacket(b.readVarInt(), b.readVarLong(), b.readVarLong()); }
        static void handle(RouletteResultPacket p, Supplier<NetworkEvent.Context> c) { toClient(c, () -> ClientHandler.rouletteResult(p)); }
    }

    /** État d'une main de blackjack. {@code phase} : 0 aucune main, 1 en cours, 2 terminée. Carte cachée du croupier = -1. */
    public record BjStatePacket(int phase, long bet, List<Integer> player, List<Integer> dealer, int playerTotal,
                                String message, long payout, boolean canDouble) {
        static void encode(BjStatePacket p, FriendlyByteBuf b) {
            b.writeVarInt(p.phase); b.writeVarLong(p.bet);
            b.writeVarInt(p.player.size()); for (int i : p.player) b.writeVarInt(i);
            b.writeVarInt(p.dealer.size()); for (int i : p.dealer) b.writeVarInt(i + 1);
            b.writeVarInt(p.playerTotal); b.writeUtf(p.message, 120); b.writeVarLong(p.payout); b.writeBoolean(p.canDouble);
        }
        static BjStatePacket decode(FriendlyByteBuf b) {
            int phase = b.readVarInt(); long bet = b.readVarLong();
            List<Integer> pl = readList(b, FriendlyByteBuf::readVarInt, MAX_LIST);
            List<Integer> de = readList(b, x -> x.readVarInt() - 1, MAX_LIST);
            return new BjStatePacket(phase, bet, pl, de, b.readVarInt(), b.readUtf(120), b.readVarLong(), b.readBoolean());
        }
        static void handle(BjStatePacket p, Supplier<NetworkEvent.Context> c) { toClient(c, () -> ClientHandler.bjState(p)); }
    }

    /** Derniers numéros de la roulette, le plus récent en premier (statistiques affichées sur l'écran de la roulette). */
    public record HistoryPacket(List<Integer> draws) {
        static void encode(HistoryPacket p, FriendlyByteBuf b) { b.writeVarInt(p.draws.size()); for (int i : p.draws) b.writeVarInt(i); }
        static HistoryPacket decode(FriendlyByteBuf b) { return new HistoryPacket(readList(b, FriendlyByteBuf::readVarInt, 128)); }
        static void handle(HistoryPacket p, Supplier<NetworkEvent.Context> c) { toClient(c, () -> ClientHandler.history(p.draws)); }
    }

    /** Valeur (centimes) des jetons que le joueur porte, affichée dans l'écran ouvert. */
    public record ChipsPacket(long held) {
        static void encode(ChipsPacket p, FriendlyByteBuf b) { b.writeVarLong(p.held); }
        static ChipsPacket decode(FriendlyByteBuf b) { return new ChipsPacket(b.readVarLong()); }
        static void handle(ChipsPacket p, Supplier<NetworkEvent.Context> c) { toClient(c, () -> ClientHandler.held(p.held)); }
    }

    /** Jackpot progressif actuel (centimes), affiché sur l'écran de la machine à sous. */
    public record JackpotPacket(long cents) {
        static void encode(JackpotPacket p, FriendlyByteBuf b) { b.writeVarLong(p.cents); }
        static JackpotPacket decode(FriendlyByteBuf b) { return new JackpotPacket(b.readVarLong()); }
        static void handle(JackpotPacket p, Supplier<NetworkEvent.Context> c) { toClient(c, () -> ClientHandler.jackpot(p.cents)); }
    }

    /**
     * État d'une table de poker, préparé pour UN joueur : ses cartes, celles des autres seulement au showdown (-1 = dos de carte,
     * -2 = pas de carte). {@code phase} : ordinal de {@link fr.minenorth.casino.game.PokerTable.Phase}.
     */
    public record PokerStatePacket(int phase, int hand, int button, int toAct, int mySeat, long pot, long currentBet, long toCall,
                                   long minRaiseTo, long maxRaiseTo, int seconds, List<Integer> board, String message, long sb, long bb,
                                   List<SeatView> seats) {
        /** {@code flags} : 1 dans la main, 2 couché, 4 tapis, 8 part, 16 gagnant. */
        public record SeatView(boolean present, String name, long stack, long bet, String last, int flags, int c1, int c2, String hand, long won) {
            public static final SeatView EMPTY = new SeatView(false, "", 0, 0, "", 0, -2, -2, "", 0);
        }

        static void encode(PokerStatePacket p, FriendlyByteBuf b) {
            b.writeVarInt(p.phase); b.writeVarInt(p.hand); b.writeVarInt(p.button + 1); b.writeVarInt(p.toAct + 1); b.writeVarInt(p.mySeat + 1);
            b.writeVarLong(p.pot); b.writeVarLong(p.currentBet); b.writeVarLong(p.toCall); b.writeVarLong(p.minRaiseTo); b.writeVarLong(p.maxRaiseTo);
            b.writeVarInt(p.seconds);
            b.writeVarInt(p.board.size()); for (int c : p.board) b.writeVarInt(c);
            b.writeUtf(p.message, 300); b.writeVarLong(p.sb); b.writeVarLong(p.bb);
            b.writeVarInt(p.seats.size());
            for (SeatView s : p.seats) {
                b.writeBoolean(s.present);
                if (!s.present) continue;
                b.writeUtf(s.name, 64); b.writeVarLong(s.stack); b.writeVarLong(s.bet); b.writeUtf(s.last, 64); b.writeVarInt(s.flags);
                b.writeVarInt(s.c1 + 2); b.writeVarInt(s.c2 + 2); b.writeUtf(s.hand, 32); b.writeVarLong(s.won);
            }
        }

        static PokerStatePacket decode(FriendlyByteBuf b) {
            int phase = b.readVarInt(), hand = b.readVarInt(), button = b.readVarInt() - 1, toAct = b.readVarInt() - 1, mine = b.readVarInt() - 1;
            long pot = b.readVarLong(), cur = b.readVarLong(), call = b.readVarLong(), min = b.readVarLong(), max = b.readVarLong();
            int sec = b.readVarInt();
            List<Integer> board = readList(b, FriendlyByteBuf::readVarInt, 5);
            String msg = b.readUtf(300);
            long sb = b.readVarLong(), bb = b.readVarLong();
            List<SeatView> seats = readList(b, x -> {
                if (!x.readBoolean()) return SeatView.EMPTY;
                return new SeatView(true, x.readUtf(64), x.readVarLong(), x.readVarLong(), x.readUtf(64), x.readVarInt(),
                        x.readVarInt() - 2, x.readVarInt() - 2, x.readUtf(32), x.readVarLong());
            }, 10);
            return new PokerStatePacket(phase, hand, button, toAct, mine, pot, cur, call, min, max, sec, board, msg, sb, bb, seats);
        }

        static void handle(PokerStatePacket p, Supplier<NetworkEvent.Context> c) { toClient(c, () -> ClientHandler.poker(p)); }
    }

    /**
     * État de l'arène de combats de coqs. {@code phase} : 0 en attente du premier pari, 1 paris, 2 combat, 3 fin. {@code seq} : numéro
     * de l'échange qui vient d'avoir lieu (le client l'anime), -1 pour une simple mise à jour. {@code bets} : « R|B » + nom + « | » + centimes.
     */
    public record CockStatePacket(int phase, int seconds, String nameA, String nameB, int colorA, int colorB,
                                  int forceA, int agiA, int staA, int forceB, int agiB, int staB, int hpMaxA, int hpMaxB, int hpA, int hpB,
                                  long poolA, long poolB, float pA, int attacker, int damage, int seq, boolean crit, long mineA, long mineB,
                                  int winner, String message, List<String> bets, long cash, boolean police, boolean organizer, long caisse) {
        static void encode(CockStatePacket p, FriendlyByteBuf b) {
            b.writeVarInt(p.phase); b.writeVarInt(p.seconds); b.writeUtf(p.nameA, 64); b.writeUtf(p.nameB, 64); b.writeInt(p.colorA); b.writeInt(p.colorB);
            b.writeVarInt(p.forceA); b.writeVarInt(p.agiA); b.writeVarInt(p.staA); b.writeVarInt(p.forceB); b.writeVarInt(p.agiB); b.writeVarInt(p.staB);
            b.writeVarInt(p.hpMaxA); b.writeVarInt(p.hpMaxB); b.writeVarInt(p.hpA); b.writeVarInt(p.hpB);
            b.writeVarLong(p.poolA); b.writeVarLong(p.poolB); b.writeFloat(p.pA);
            b.writeVarInt(p.attacker + 1); b.writeVarInt(p.damage + 1); b.writeVarInt(p.seq + 1); b.writeBoolean(p.crit);
            b.writeVarLong(p.mineA); b.writeVarLong(p.mineB); b.writeVarInt(p.winner + 1); b.writeUtf(p.message, 200);
            b.writeVarInt(p.bets.size()); for (String s : p.bets) b.writeUtf(s, 96);
            b.writeVarLong(p.cash); b.writeBoolean(p.police); b.writeBoolean(p.organizer); b.writeVarLong(p.caisse);
        }

        static CockStatePacket decode(FriendlyByteBuf b) {
            int phase = b.readVarInt(), sec = b.readVarInt();
            String na = b.readUtf(64), nb = b.readUtf(64);
            int ca = b.readInt(), cb = b.readInt();
            int fa = b.readVarInt(), aa = b.readVarInt(), sa = b.readVarInt(), fb = b.readVarInt(), ab = b.readVarInt(), sb = b.readVarInt();
            int ma = b.readVarInt(), mb = b.readVarInt(), ha = b.readVarInt(), hb = b.readVarInt();
            long pa = b.readVarLong(), pb = b.readVarLong();
            float pA = b.readFloat();
            int att = b.readVarInt() - 1, dmg = b.readVarInt() - 1, seq = b.readVarInt() - 1;
            boolean crit = b.readBoolean();
            long mya = b.readVarLong(), myb = b.readVarLong();
            int win = b.readVarInt() - 1;
            String msg = b.readUtf(200);
            List<String> bets = readList(b, x -> x.readUtf(96), MAX_LIST);
            return new CockStatePacket(phase, sec, na, nb, ca, cb, fa, aa, sa, fb, ab, sb, ma, mb, ha, hb, pa, pb, pA, att, dmg, seq, crit, mya, myb,
                    win, msg, bets, b.readVarLong(), b.readBoolean(), b.readBoolean(), b.readVarLong());
        }

        static void handle(CockStatePacket p, Supplier<NetworkEvent.Context> c) { toClient(c, () -> ClientHandler.cock(p)); }
    }

    // ------------------------------------------------------------------ client → serveur

    /** Poker : 0 couché, 1 check, 2 suivre, 3 relancer à {@code amount}, 4 tapis, 10 s'asseoir au siège {@code seat} avec {@code amount}, 11 se lever. */
    public record PokerActionPacket(int action, int seat, long amount) {
        static void encode(PokerActionPacket p, FriendlyByteBuf b) { b.writeVarInt(p.action); b.writeVarInt(p.seat); b.writeVarLong(p.amount); }
        static PokerActionPacket decode(FriendlyByteBuf b) { return new PokerActionPacket(b.readVarInt(), b.readVarInt(), b.readVarLong()); }
        static void handle(PokerActionPacket p, Supplier<NetworkEvent.Context> c) {
            toServer(c, sp -> fr.minenorth.casino.PokerService.action(sp, p.action, p.seat, p.amount));
        }
    }

    /** Combats de coqs : 0 parier {@code cents} sur le coq {@code side} (0 rouge, 1 bleu), 1 récupérer la caisse, 2 descente de police. */
    public record CockActionPacket(int action, int side, long cents) {
        static void encode(CockActionPacket p, FriendlyByteBuf b) { b.writeVarInt(p.action); b.writeVarInt(p.side); b.writeVarLong(p.cents); }
        static CockActionPacket decode(FriendlyByteBuf b) { return new CockActionPacket(b.readVarInt(), b.readVarInt(), b.readVarLong()); }
        static void handle(CockActionPacket p, Supplier<NetworkEvent.Context> c) {
            toServer(c, sp -> fr.minenorth.casino.CockfightService.action(sp, p.action, p.side, p.cents));
        }
    }

    /** Caisse : 0 acheter {@code cents} de jetons, 1 racheter tous ses jetons. */
    public record CashierActionPacket(int action, long cents) {
        static void encode(CashierActionPacket p, FriendlyByteBuf b) { b.writeVarInt(p.action); b.writeVarLong(p.cents); }
        static CashierActionPacket decode(FriendlyByteBuf b) { return new CashierActionPacket(b.readVarInt(), b.readVarLong()); }
        static void handle(CashierActionPacket p, Supplier<NetworkEvent.Context> c) { toServer(c, sp -> CasinoService.cashier(sp, p.action, p.cents)); }
    }

    public record SlotSpinPacket(long bet) {
        static void encode(SlotSpinPacket p, FriendlyByteBuf b) { b.writeVarLong(p.bet); }
        static SlotSpinPacket decode(FriendlyByteBuf b) { return new SlotSpinPacket(b.readVarLong()); }
        static void handle(SlotSpinPacket p, Supplier<NetworkEvent.Context> c) { toServer(c, sp -> CasinoService.spinSlot(sp, p.bet)); }
    }

    public record RouletteSpinPacket(List<RouletteGame.Bet> bets) {
        static void encode(RouletteSpinPacket p, FriendlyByteBuf b) {
            b.writeVarInt(p.bets.size());
            for (RouletteGame.Bet x : p.bets) { b.writeVarInt(x.type()); b.writeVarInt(x.value()); b.writeVarLong(x.cents()); }
        }
        static RouletteSpinPacket decode(FriendlyByteBuf b) {
            return new RouletteSpinPacket(readList(b, x -> new RouletteGame.Bet(x.readVarInt(), x.readVarInt(), x.readVarLong()), MAX_BETS));
        }
        static void handle(RouletteSpinPacket p, Supplier<NetworkEvent.Context> c) { toServer(c, sp -> CasinoService.spinRoulette(sp, p.bets)); }
    }

    /** Blackjack : 0 miser (distribuer), 1 tirer, 2 rester, 3 doubler. */
    public record BjActionPacket(int action, long bet) {
        public static final int DEAL = 0, HIT = 1, STAND = 2, DOUBLE = 3;
        static void encode(BjActionPacket p, FriendlyByteBuf b) { b.writeVarInt(p.action); b.writeVarLong(p.bet); }
        static BjActionPacket decode(FriendlyByteBuf b) { return new BjActionPacket(b.readVarInt(), b.readVarLong()); }
        static void handle(BjActionPacket p, Supplier<NetworkEvent.Context> c) { toServer(c, sp -> CasinoService.blackjack(sp, p.action, p.bet)); }
    }
}
