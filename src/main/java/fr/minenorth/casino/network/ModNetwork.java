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

    private static final String PROTOCOL = "2";
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
        CHANNEL.registerMessage(id++, CashierActionPacket.class, CashierActionPacket::encode, CashierActionPacket::decode, CashierActionPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
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
     * Ouverture d'un jeu. {@code game} : 0 machine à sous, 1 roulette, 2 blackjack. Les listes ne servent qu'au jeu concerné
     * (symboles et gains de la machine, gains de la roulette, gain du blackjack).
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

    /** Valeur (centimes) des jetons que le joueur porte, affichée dans l'écran ouvert. */
    public record ChipsPacket(long held) {
        static void encode(ChipsPacket p, FriendlyByteBuf b) { b.writeVarLong(p.held); }
        static ChipsPacket decode(FriendlyByteBuf b) { return new ChipsPacket(b.readVarLong()); }
        static void handle(ChipsPacket p, Supplier<NetworkEvent.Context> c) { toClient(c, () -> ClientHandler.held(p.held)); }
    }

    // ------------------------------------------------------------------ client → serveur

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
