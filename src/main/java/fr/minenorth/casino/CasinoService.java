package fr.minenorth.casino;

import fr.minenorth.api.PayResult;
import fr.minenorth.casino.CompanyLink.Casino;
import fr.minenorth.casino.block.CasinoBlock;
import fr.minenorth.casino.block.CasinoBlock.Game;
import fr.minenorth.casino.config.CasinoConfig;
import fr.minenorth.casino.config.CasinoConfig.Symbol;
import fr.minenorth.casino.game.BlackjackGame;
import fr.minenorth.casino.game.RouletteGame;
import fr.minenorth.casino.game.RouletteGame.Bet;
import fr.minenorth.casino.game.SlotGame;
import fr.minenorth.casino.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Règles serveur du casino. Le client n'envoie que des intentions ; le serveur vérifie la distance au bloc, la mise, la
 * caisse de l'entreprise (elle doit pouvoir payer le gain maximum), tire le résultat et règle l'argent.
 */
@Mod.EventBusSubscriber(modid = MineNorthCasino.MOD_ID)
public final class CasinoService {
    private CasinoService() {}

    private static final SecureRandom RNG = new SecureRandom();

    private record Session(Game game, ResourceKey<Level> dim, BlockPos pos) {}

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Map<UUID, Long> LAST = new HashMap<>();
    private static final Map<UUID, BlackjackGame> HANDS = new HashMap<>();

    // ------------------------------------------------------------------ ouverture

    public static void open(ServerPlayer sp, Game game, BlockPos pos) {
        Casino c = CompanyLink.resolve(sp.server);
        if (c == null) { sp.displayClientMessage(Component.literal("§cCasino hors service."), true); return; }
        CasinoConfig cfg = CasinoConfig.get();
        ModNetwork.OpenPacket pk;
        switch (game) {
            case SLOT -> {
                CasinoConfig.Slots s = cfg.machine_a_sous;
                List<String> items = new ArrayList<>();
                List<Float> x3 = new ArrayList<>(), x2 = new ArrayList<>();
                for (Symbol y : s.symboles) { items.add(y.item); x3.add((float) y.x3); x2.add((float) y.x2); }
                pk = new ModNetwork.OpenPacket(0, c.name(), CasinoConfig.cents(s.mise_min_euros), CasinoConfig.cents(s.mise_max_euros),
                        CasinoConfig.centsList(s.mises_rapides_euros), items, x3, x2, List.of(), 0f, false);
            }
            case ROULETTE -> {
                CasinoConfig.Roulette r = cfg.roulette;
                List<Integer> gains = new ArrayList<>();
                for (String k : RouletteGame.KEYS) gains.add(r.gains.getOrDefault(k, 0));
                pk = new ModNetwork.OpenPacket(1, c.name(), CasinoConfig.cents(r.mise_min_euros), CasinoConfig.cents(r.mise_max_euros),
                        CasinoConfig.centsList(r.mises_rapides_euros), List.of(), List.of(), List.of(), gains, 0f, false);
            }
            default -> {
                CasinoConfig.Blackjack b = cfg.blackjack;
                pk = new ModNetwork.OpenPacket(2, c.name(), CasinoConfig.cents(b.mise_min_euros), CasinoConfig.cents(b.mise_max_euros),
                        CasinoConfig.centsList(b.mises_rapides_euros), List.of(), List.of(), List.of(), List.of(),
                        (float) b.gain_blackjack, b.croupier_tire_soft17);
            }
        }
        SESSIONS.put(sp.getUUID(), new Session(game, sp.level().dimension(), pos.immutable()));
        ModNetwork.send(sp, pk);
        BlackjackGame g = HANDS.get(sp.getUUID());
        if (game == Game.BLACKJACK && g != null) sendBj(sp, g, "Main en cours.");
    }

    // ------------------------------------------------------------------ vérifications communes

    private static void reply(ServerPlayer p, boolean ok, String msg) { ModNetwork.send(p, new ModNetwork.MsgPacket(ok, msg)); }

    /** Casino utilisable pour ce joueur et ce jeu : session ouverte, bloc proche et toujours là, entreprise active. */
    private static Casino guard(ServerPlayer sp, Game game) {
        Session s = SESSIONS.get(sp.getUUID());
        if (s == null || s.game() != game) { reply(sp, false, "Aucun jeu ouvert."); return null; }
        int d = CasinoConfig.get().distance_max_blocs;
        if (sp.level().dimension() != s.dim() || sp.blockPosition().distSqr(s.pos()) > (double) d * d) {
            reply(sp, false, "Vous êtes trop loin."); return null;
        }
        BlockState st = sp.level().getBlockState(s.pos());
        if (!(st.getBlock() instanceof CasinoBlock cb) || cb.game != game) {
            SESSIONS.remove(sp.getUUID()); reply(sp, false, "Ce poste n'existe plus."); return null;
        }
        Casino c = CompanyLink.resolve(sp.server);
        if (c == null) { reply(sp, false, "Casino hors service."); return null; }
        return c;
    }

    private static boolean cooldown(ServerPlayer sp) {
        long now = System.currentTimeMillis();
        Long last = LAST.get(sp.getUUID());
        if (last != null && now - last < CasinoConfig.get().delai_entre_parties_ms) { reply(sp, false, "Patientez un instant."); return false; }
        LAST.put(sp.getUUID(), now);
        return true;
    }

    private static boolean houseCovers(ServerPlayer sp, Casino c, long needed) {
        if (needed <= 0) return true;
        if (CasinoBank.houseBalance(sp.server, c) >= needed) return true;
        reply(sp, false, "La caisse du casino ne peut pas couvrir cette mise.");
        return false;
    }

    // ------------------------------------------------------------------ machine à sous

    public static void spinSlot(ServerPlayer sp, long bet) {
        Casino c = guard(sp, Game.SLOT);
        if (c == null) return;
        CasinoConfig.Slots cfg = CasinoConfig.get().machine_a_sous;
        long min = CasinoConfig.cents(cfg.mise_min_euros), max = CasinoConfig.cents(cfg.mise_max_euros);
        if (bet < min || bet > max) { reply(sp, false, "Mise invalide."); return; }
        if (!cooldown(sp)) return;
        long need = (long) Math.ceil(bet * Math.max(0, SlotGame.maxMultiplier(cfg.symboles) - 1));
        if (!houseCovers(sp, c, need)) return;
        PayResult r = CasinoBank.stake(sp, c, bet, "Casino : machine à sous");
        if (!r.ok()) { reply(sp, false, r.message()); return; }
        int[] reels = SlotGame.spin(RNG, cfg.symboles);
        long win = Math.round(bet * SlotGame.multiplier(reels, cfg.symboles));
        long paid = win;
        if (win > 0 && !CasinoBank.payout(sp.server, c, sp.getUUID(), win, "Casino : gain machine à sous")) {
            paid = 0;
            reply(sp, false, "Gain non versé : prévenez un responsable du casino.");
        }
        CasinoStats.get(sp.server).add("machine_a_sous", bet, paid);
        ModNetwork.send(sp, new ModNetwork.SlotResultPacket(reels[0], reels[1], reels[2], bet, paid));
    }

    // ------------------------------------------------------------------ roulette

    public static void spinRoulette(ServerPlayer sp, List<Bet> bets) {
        Casino c = guard(sp, Game.ROULETTE);
        if (c == null) return;
        CasinoConfig.Roulette cfg = CasinoConfig.get().roulette;
        long min = CasinoConfig.cents(cfg.mise_min_euros), max = CasinoConfig.cents(cfg.mise_max_euros);
        if (bets.isEmpty() || bets.size() > cfg.max_paris) { reply(sp, false, "Nombre de paris invalide."); return; }
        long total = 0;
        for (Bet b : bets) {
            if (!RouletteGame.valid(b) || b.cents() < min || b.cents() > max) { reply(sp, false, "Pari invalide."); return; }
            total += b.cents();
        }
        if (total > max) { reply(sp, false, "Mise totale trop élevée."); return; }
        if (!cooldown(sp)) return;
        if (!houseCovers(sp, c, RouletteGame.maxNet(bets, cfg.gains))) return;
        PayResult r = CasinoBank.stake(sp, c, total, "Casino : roulette");
        if (!r.ok()) { reply(sp, false, r.message()); return; }
        int n = RNG.nextInt(37);
        long win = RouletteGame.totalPayout(bets, n, cfg.gains);
        long paid = win;
        if (win > 0 && !CasinoBank.payout(sp.server, c, sp.getUUID(), win, "Casino : gain roulette")) {
            paid = 0;
            reply(sp, false, "Gain non versé : prévenez un responsable du casino.");
        }
        CasinoStats.get(sp.server).add("roulette", total, paid);
        ModNetwork.send(sp, new ModNetwork.RouletteResultPacket(n, total, paid));
    }

    // ------------------------------------------------------------------ blackjack

    public static void blackjack(ServerPlayer sp, int action, long bet) {
        Casino c = guard(sp, Game.BLACKJACK);
        if (c == null) return;
        CasinoConfig.Blackjack cfg = CasinoConfig.get().blackjack;
        BlackjackGame g = HANDS.get(sp.getUUID());
        switch (action) {
            case ModNetwork.BjActionPacket.DEAL -> {
                if (g != null) { reply(sp, false, "Une main est déjà en cours."); return; }
                long min = CasinoConfig.cents(cfg.mise_min_euros), max = CasinoConfig.cents(cfg.mise_max_euros);
                if (bet < min || bet > max) { reply(sp, false, "Mise invalide."); return; }
                if (!cooldown(sp)) return;
                if (!houseCovers(sp, c, bet * 2)) return;
                PayResult r = CasinoBank.stake(sp, c, bet, "Casino : blackjack");
                if (!r.ok()) { reply(sp, false, r.message()); return; }
                g = new BlackjackGame(bet, RNG, cfg.nombre_de_jeux);
                g.player.add(g.draw()); g.dealer.add(g.draw()); g.player.add(g.draw()); g.dealer.add(g.draw());
                HANDS.put(sp.getUUID(), g);
                if (BlackjackGame.blackjack(g.player) || BlackjackGame.blackjack(g.dealer)) finish(sp.server, sp, c, sp.getUUID(), g);
                else sendBj(sp, g, "À vous de jouer.");
            }
            case ModNetwork.BjActionPacket.HIT -> {
                if (g == null) return;
                g.player.add(g.draw());
                int t = BlackjackGame.total(g.player);
                if (t > 21) finish(sp.server, sp, c, sp.getUUID(), g);
                else if (t == 21) { g.dealerPlay(cfg.croupier_tire_soft17); finish(sp.server, sp, c, sp.getUUID(), g); }
                else sendBj(sp, g, "À vous de jouer.");
            }
            case ModNetwork.BjActionPacket.STAND -> {
                if (g == null) return;
                g.dealerPlay(cfg.croupier_tire_soft17);
                finish(sp.server, sp, c, sp.getUUID(), g);
            }
            case ModNetwork.BjActionPacket.DOUBLE -> {
                if (g == null || g.player.size() != 2 || g.doubled) { reply(sp, false, "Doublage impossible."); return; }
                if (!cooldown(sp)) return;
                if (!houseCovers(sp, c, g.bet * 3)) return;
                PayResult r = CasinoBank.stake(sp, c, g.bet, "Casino : blackjack (double)");
                if (!r.ok()) { reply(sp, false, r.message()); return; }
                g.bet *= 2;
                g.doubled = true;
                g.player.add(g.draw());
                if (BlackjackGame.total(g.player) <= 21) g.dealerPlay(cfg.croupier_tire_soft17);
                finish(sp.server, sp, c, sp.getUUID(), g);
            }
            default -> { }
        }
    }

    /** Règle la main (gain versé par l'entreprise) ; {@code sp} est null si le joueur s'est déconnecté. */
    private static void finish(MinecraftServer s, ServerPlayer sp, Casino c, UUID id, BlackjackGame g) {
        g.settle(CasinoConfig.get().blackjack.gain_blackjack);
        HANDS.remove(id);
        long paid = g.payout;
        boolean ok = CasinoBank.payout(s, c, id, g.payout, "Casino : gain blackjack");
        if (!ok) paid = 0;
        CasinoStats.get(s).add("blackjack", g.bet, paid);
        if (sp != null) {
            sendBj(sp, g, ok ? g.outcome : "Gain non versé : prévenez un responsable du casino.");
        }
    }

    private static void sendBj(ServerPlayer sp, BlackjackGame g, String msg) {
        boolean playing = !g.finished;
        List<Integer> dealer = new ArrayList<>(g.dealer);
        if (playing && dealer.size() >= 2) dealer.set(1, -1);
        ModNetwork.send(sp, new ModNetwork.BjStatePacket(playing ? 1 : 2, g.bet, new ArrayList<>(g.player), dealer,
                BlackjackGame.total(g.player), msg, g.payout, playing && g.player.size() == 2 && !g.doubled));
    }

    /** Joueur déconnecté en pleine main : il reste (le croupier tire) et la main est réglée normalement. */
    private static void abandon(MinecraftServer s, UUID id) {
        BlackjackGame g = HANDS.get(id);
        if (g == null) return;
        Casino c = CompanyLink.resolve(s);
        g.dealerPlay(CasinoConfig.get().blackjack.croupier_tire_soft17);
        if (c == null) { HANDS.remove(id); return; }   // entreprise disparue : la mise reste à la caisse (déjà versée)
        finish(s, null, c, id, g);
    }

    // ------------------------------------------------------------------ événements

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer sp)) return;
        abandon(sp.server, sp.getUUID());
        SESSIONS.remove(sp.getUUID());
        LAST.remove(sp.getUUID());
    }

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent e) {
        for (UUID id : new ArrayList<>(HANDS.keySet())) abandon(e.getServer(), id);
        SESSIONS.clear();
        LAST.clear();
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (!(e.getState().getBlock() instanceof CasinoBlock) || !CasinoConfig.get().pose_op_seulement) return;
        if (!e.getPlayer().hasPermissions(2)) {
            e.setCanceled(true);
            e.getPlayer().displayClientMessage(Component.literal("§cSeul un OP peut retirer ce poste de jeu."), true);
        }
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (!(e.getPlacedBlock().getBlock() instanceof CasinoBlock) || !CasinoConfig.get().pose_op_seulement) return;
        if (e.getEntity() instanceof ServerPlayer sp && !sp.hasPermissions(2)) {
            e.setCanceled(true);
            sp.displayClientMessage(Component.literal("§cSeul un OP peut poser ce poste de jeu."), true);
        }
    }
}
