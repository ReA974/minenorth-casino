package fr.minenorth.casino;

import com.mojang.logging.LogUtils;
import fr.minenorth.api.MineNorth;
import fr.minenorth.casino.CompanyLink.Casino;
import fr.minenorth.casino.block.CasinoBlock;
import fr.minenorth.casino.block.CasinoBlock.Game;
import fr.minenorth.casino.config.CasinoConfig;
import fr.minenorth.casino.game.PokerTable;
import fr.minenorth.casino.game.PokerTable.Seat;
import fr.minenorth.casino.network.ModNetwork;
import fr.minenorth.casino.network.ModNetwork.PokerStatePacket;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tables de poker (Texas Hold'em No Limit, 6 places). Le casino ne joue pas : il fournit le croupier et prend une commission sur
 * les pots (les jetons gardés sont autant que l'entreprise n'aura pas à racheter). Les joueurs apportent une cave en jetons, la
 * récupèrent en se levant. Toutes les décisions sont prises ici ; chaque joueur ne reçoit que ses propres cartes jusqu'au showdown.
 */
@Mod.EventBusSubscriber(modid = MineNorthCasino.MOD_ID)
public final class PokerService {
    private PokerService() {}

    private static final Logger LOG = LogUtils.getLogger();
    private static final SecureRandom RNG = new SecureRandom();

    record Key(ResourceKey<Level> dim, BlockPos master) {}

    private static final class Table {
        final Key key;
        final PokerTable t = new PokerTable(RNG);
        /** Échéance de l'action en cours, ou de la prochaine main. */
        long deadline;
        boolean settled;
        String message = "En attente de joueurs.";
        int lastToAct = -2;

        Table(Key key) { this.key = key; }
    }

    private static final Map<Key, Table> TABLES = new HashMap<>();

    private static void applyConfig(PokerTable t) {
        CasinoConfig.Poker c = CasinoConfig.get().poker;
        t.smallBlind = CasinoConfig.cents(c.petite_blind_euros);
        t.bigBlind = CasinoConfig.cents(c.grosse_blind_euros);
        t.rakePct = c.commission_pourcent;
        t.rakeCap = CasinoConfig.cents(c.commission_max_euros);
    }

    private static long buyInMin() { CasinoConfig.Poker c = CasinoConfig.get().poker; return CasinoConfig.cents(c.grosse_blind_euros) * c.cave_min_blinds; }

    private static long buyInMax() { CasinoConfig.Poker c = CasinoConfig.get().poker; return CasinoConfig.cents(c.grosse_blind_euros) * c.cave_max_blinds; }

    /** Caves proposées : minimum, 50 BB, maximum. */
    static List<Long> buyIns() {
        long bb = CasinoConfig.cents(CasinoConfig.get().poker.grosse_blind_euros);
        Set<Long> out = new LinkedHashSet<>(List.of(buyInMin(), Math.max(buyInMin(), Math.min(buyInMax(), bb * 50)), buyInMax()));
        return new ArrayList<>(out);
    }

    static ModNetwork.OpenPacket openPacket(Casino c) {
        CasinoConfig.Poker p = CasinoConfig.get().poker;
        return new ModNetwork.OpenPacket(4, c.name(), buyInMin(), buyInMax(), buyIns(), List.of(), List.of(), List.of(), List.of(),
                (float) p.commission_pourcent, false);
    }

    /** Ouverture de l'écran : la table est créée au besoin et l'état envoyé. */
    static void opened(ServerPlayer sp, BlockPos master) {
        Table tb = TABLES.computeIfAbsent(new Key(sp.level().dimension(), master.immutable()), Table::new);
        applyConfig(tb.t);
        send(sp, tb);
    }

    private static Table tableOf(ServerPlayer sp) {
        CasinoService.Session s = CasinoService.session(sp);
        if (s == null || s.game() != Game.POKER) return null;
        return TABLES.get(new Key(s.dim(), s.pos()));
    }

    // ------------------------------------------------------------------ actions du joueur

    public static final int SIT = 10, LEAVE = 11;

    public static void action(ServerPlayer sp, int action, int seat, long amount) {
        Casino c = CasinoService.guard(sp, Game.POKER);
        if (c == null) return;
        Table tb = tableOf(sp);
        if (tb == null) { CasinoService.reply(sp, false, "Table introuvable : rouvrez-la."); return; }
        PokerTable t = tb.t;
        int mine = t.seatOf(sp.getUUID());
        if (action == SIT) {
            if (mine >= 0) { CasinoService.reply(sp, false, "Vous êtes déjà assis."); return; }
            if (seatTakenElsewhere(sp)) { CasinoService.reply(sp, false, "Vous êtes déjà assis à une autre table."); return; }
            if (seat < 0 || seat >= PokerTable.SEATS || t.seats[seat] != null) { CasinoService.reply(sp, false, "Place occupée."); return; }
            if (amount < buyInMin() || amount > buyInMax() || amount % 100 != 0) {
                CasinoService.reply(sp, false, "Cave entre " + CasinoService.euros(buyInMin()) + " et " + CasinoService.euros(buyInMax()) + ".");
                return;
            }
            if (!CasinoService.takeChips(sp, amount)) return;
            t.sit(seat, sp.getUUID(), MineNorth.displayName(sp), amount);
            tb.message = MineNorth.displayName(sp) + " s'assoit avec " + CasinoService.euros(amount) + ".";
            // la main démarre 3 s après l'arrivée du deuxième joueur (le temps de s'installer)
            if (t.phase == PokerTable.Phase.WAITING) tb.deadline = Math.max(tb.deadline, System.currentTimeMillis() + 3000);
            broadcast(sp.server, tb);
            return;
        }
        if (mine < 0) { CasinoService.reply(sp, false, "Asseyez-vous d'abord."); return; }
        if (action == LEAVE) {
            Seat s = t.seats[mine];
            if (t.handRunning() && s.inHand && !s.folded) {
                t.forceFold(mine);
                tb.message = s.name + " se couche et quittera la table à la fin de la main.";
                after(sp.server, tb);
            } else if (t.handRunning() && s.inHand) {
                s.leaving = true;
                tb.message = s.name + " quittera la table à la fin de la main.";
            } else {
                standUp(sp.server, tb, mine);
            }
            broadcast(sp.server, tb);
            return;
        }
        String err = t.act(mine, action, amount);
        if (err != null) { CasinoService.reply(sp, false, err); return; }
        sound(sp.server, tb, action);
        after(sp.server, tb);
        broadcast(sp.server, tb);
    }

    private static boolean seatTakenElsewhere(ServerPlayer sp) {
        for (Table tb : TABLES.values()) if (tb.t.seatOf(sp.getUUID()) >= 0) return true;
        return false;
    }

    private static void sound(MinecraftServer s, Table tb, int action) {
        ServerLevel l = s.getLevel(tb.key.dim());
        if (l == null) return;
        var ev = action == PokerTable.FOLD ? SoundEvents.BOOK_PAGE_TURN : action == PokerTable.CHECK ? SoundEvents.WOOD_HIT
                : SoundEvents.CHAIN_PLACE;
        l.playSound(null, tb.key.master(), ev, SoundSource.BLOCKS, 0.6f, action == PokerTable.ALLIN ? 0.7f : 1.2f);
    }

    /** Après chaque changement : nouvelle échéance si le joueur à parler a changé, règlement si la main est finie. */
    private static void after(MinecraftServer s, Table tb) {
        PokerTable t = tb.t;
        long now = System.currentTimeMillis();
        if (t.phase == PokerTable.Phase.SHOWDOWN && !tb.settled) {
            tb.settled = true;
            tb.deadline = now + CasinoConfig.get().poker.pause_entre_mains_secondes * 1000L;
            CasinoStats.get(s).add("poker", t.lastRake, 0);
            StringBuilder b = new StringBuilder();
            for (PokerTable.PotResult r : t.results) {
                if (r.winners().isEmpty()) continue;
                if (b.length() > 0) b.append(" · ");
                List<String> names = new ArrayList<>();
                for (int w : r.winners()) names.add(t.seats[w].name);
                b.append(String.join(" et ", names)).append(" gagne ").append(CasinoService.euros(r.amount()));
                if (!r.hand().isEmpty()) b.append(" (").append(r.hand()).append(")");
            }
            if (t.lastRake > 0) b.append(" · commission ").append(CasinoService.euros(t.lastRake));
            tb.message = b.toString();
            ServerLevel l = s.getLevel(tb.key.dim());
            if (l != null) l.playSound(null, tb.key.master(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.BLOCKS, 0.8f, 0.9f);
            tb.lastToAct = -2;
            return;
        }
        if (t.toAct != tb.lastToAct) {
            tb.lastToAct = t.toAct;
            tb.deadline = now + CasinoConfig.get().poker.temps_par_action_secondes * 1000L;
        }
    }

    // ------------------------------------------------------------------ se lever, rendre les jetons

    /** Le joueur quitte son siège avec son tapis : en jetons s'il est connecté, sinon viré sur son compte par l'entreprise. */
    private static void standUp(MinecraftServer s, Table tb, int i) {
        Seat seat = tb.t.seats[i];
        if (seat == null) return;
        tb.t.seats[i] = null;
        returnStack(s, seat.id, seat.stack);
        tb.message = seat.name + " quitte la table" + (seat.stack > 0 ? " avec " + CasinoService.euros(seat.stack) : "") + ".";
    }

    private static void returnStack(MinecraftServer s, UUID id, long cents) {
        if (cents <= 0) return;
        ServerPlayer p = s.getPlayerList().getPlayer(id);
        if (p != null) {
            Chips.give(p, cents);
            ModNetwork.send(p, new ModNetwork.ChipsPacket(Chips.total(p)));
            return;
        }
        Casino c = CompanyLink.resolve(s);
        if (c == null || !CasinoBank.payout(s, c, id, cents, "Casino : tapis de poker rendu"))
            LOG.error("[Casino] Tapis de poker de {} cts NON rendu à {} (joueur hors ligne, entreprise indisponible)", cents, id);
    }

    // ------------------------------------------------------------------ horloge

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || TABLES.isEmpty() || e.getServer().getTickCount() % 5 != 0) return;
        MinecraftServer s = e.getServer();
        long now = System.currentTimeMillis();
        for (Table tb : new ArrayList<>(TABLES.values())) {
            PokerTable t = tb.t;
            if (!tableExists(s, tb)) { close(s, tb); continue; }
            boolean changed = false;
            switch (t.phase) {
                case WAITING -> {
                    for (int i = 0; i < PokerTable.SEATS; i++) {
                        Seat seat = t.seats[i];
                        if (seat != null && (seat.leaving || s.getPlayerList().getPlayer(seat.id) == null)) { standUp(s, tb, i); changed = true; }
                    }
                    if (t.ready() >= 2 && now >= tb.deadline) {
                        applyConfig(t);
                        tb.settled = false;
                        if (t.startHand()) {
                            tb.message = "Main n°" + t.handNumber + " : bonne chance !";
                            ServerLevel l = s.getLevel(tb.key.dim());
                            if (l != null) l.playSound(null, tb.key.master(), SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 1f, 1.4f);
                            after(s, tb);
                            changed = true;
                        }
                    } else if (t.ready() < 2 && tb.deadline < now) {
                        tb.deadline = now + 3000;
                        if (t.seated() > 0 && !tb.message.startsWith("En attente")) { tb.message = "En attente d'un deuxième joueur."; changed = true; }
                    }
                }
                case SHOWDOWN -> {
                    if (now >= tb.deadline) {
                        t.reset();
                        long bb = t.bigBlind;
                        for (int i = 0; i < PokerTable.SEATS; i++) {
                            Seat seat = t.seats[i];
                            if (seat == null) continue;
                            seat.last = ""; seat.won = 0; seat.handName = ""; seat.showCards = false;
                            if (seat.leaving || seat.stack < bb || s.getPlayerList().getPlayer(seat.id) == null) standUp(s, tb, i);
                        }
                        tb.deadline = now + 2000;
                        changed = true;
                    }
                }
                default -> {
                    if (now >= tb.deadline && t.toAct >= 0) {
                        Seat seat = t.seats[t.toAct];
                        t.timeout();
                        tb.message = seat.name + " : temps écoulé (" + seat.last.toLowerCase(java.util.Locale.ROOT) + ").";
                        after(s, tb);
                        changed = true;
                    }
                }
            }
            if (changed) broadcast(s, tb);
            if (t.seated() == 0 && !t.handRunning() && viewers(s, tb).isEmpty()) TABLES.remove(tb.key);
        }
    }

    private static boolean tableExists(MinecraftServer s, Table tb) {
        ServerLevel l = s.getLevel(tb.key.dim());
        if (l == null) return false;
        if (!l.isLoaded(tb.key.master())) return true;   // chunk déchargé : on ne conclut rien
        return l.getBlockState(tb.key.master()).getBlock() instanceof CasinoBlock cb && cb.game == Game.POKER;
    }

    /** Table cassée ou serveur arrêté : la main en cours est annulée (chacun récupère sa mise) et tout le monde se lève. */
    private static void close(MinecraftServer s, Table tb) {
        tb.t.refundHand();
        for (int i = 0; i < PokerTable.SEATS; i++) standUp(s, tb, i);
        TABLES.remove(tb.key);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer sp)) return;
        for (Table tb : TABLES.values()) {
            int i = tb.t.seatOf(sp.getUUID());
            if (i < 0) continue;
            if (tb.t.handRunning()) {
                tb.t.forceFold(i);
                tb.message = tb.t.seats[i].name + " s'est déconnecté (couché).";
                after(sp.server, tb);
            } else {
                standUp(sp.server, tb, i);
            }
            broadcast(sp.server, tb);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onStopping(ServerStoppingEvent e) {
        for (Table tb : new ArrayList<>(TABLES.values())) close(e.getServer(), tb);
    }

    // ------------------------------------------------------------------ envoi de l'état

    private static List<ServerPlayer> viewers(MinecraftServer s, Table tb) {
        List<ServerPlayer> out = new ArrayList<>(CasinoService.viewers(s, Game.POKER, tb.key.dim(), tb.key.master()));
        for (Seat seat : tb.t.seats) {
            if (seat == null) continue;
            ServerPlayer p = s.getPlayerList().getPlayer(seat.id);
            if (p != null && !out.contains(p)) out.add(p);
        }
        return out;
    }

    private static void broadcast(MinecraftServer s, Table tb) {
        for (ServerPlayer p : viewers(s, tb)) send(p, tb);
    }

    private static void send(ServerPlayer viewer, Table tb) {
        PokerTable t = tb.t;
        int mine = t.seatOf(viewer.getUUID());
        List<PokerStatePacket.SeatView> seats = new ArrayList<>();
        for (int i = 0; i < PokerTable.SEATS; i++) {
            Seat s = t.seats[i];
            if (s == null) { seats.add(PokerStatePacket.SeatView.EMPTY); continue; }
            int c1 = -2, c2 = -2;
            if (s.inHand && s.hole.size() == 2) {
                boolean visible = i == mine || (t.phase == PokerTable.Phase.SHOWDOWN && s.showCards);
                c1 = visible ? s.hole.get(0) : -1;
                c2 = visible ? s.hole.get(1) : -1;
            }
            int flags = (s.inHand ? 1 : 0) | (s.folded ? 2 : 0) | (s.allIn ? 4 : 0) | (s.leaving ? 8 : 0) | (s.won > 0 ? 16 : 0);
            seats.add(new PokerStatePacket.SeatView(true, cut(s.name, 64), s.stack, s.bet, cut(s.last, 64), flags, c1, c2,
                    t.phase == PokerTable.Phase.SHOWDOWN ? s.handName : "", s.won));
        }
        long left = Math.max(0, tb.deadline - System.currentTimeMillis());
        long toCall = 0, minTo = 0, maxTo = 0;
        if (mine >= 0 && t.handRunning() && t.seats[mine].canAct()) {
            toCall = Math.min(t.toCall(mine), t.seats[mine].stack);
            minTo = t.minRaiseTo(mine);
            maxTo = t.maxRaiseTo(mine);
        }
        ModNetwork.send(viewer, new PokerStatePacket(t.phase.ordinal(), t.handNumber, t.button, t.toAct, mine, t.pot(), t.currentBet,
                toCall, minTo, maxTo, (int) (left / 1000), new ArrayList<>(t.board), cut(tb.message, 300), t.smallBlind, t.bigBlind, seats));
    }

    /** Texte raccourci à la taille maximale du paquet. */
    static String cut(String s, int max) { return s.length() <= max ? s : s.substring(0, max - 1) + "…"; }
}
