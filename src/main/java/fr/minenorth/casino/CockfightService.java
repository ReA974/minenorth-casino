package fr.minenorth.casino;

import com.mojang.logging.LogUtils;
import fr.minenorth.api.MineNorth;
import fr.minenorth.casino.block.ArenaBlock;
import fr.minenorth.casino.block.CasinoBlock;
import fr.minenorth.casino.config.CasinoConfig;
import fr.minenorth.casino.game.CockFight;
import fr.minenorth.casino.game.CockFight.Cock;
import fr.minenorth.casino.game.CockFight.Exchange;
import fr.minenorth.casino.network.ModNetwork;
import fr.minenorth.casino.network.ModNetwork.ArenaStatePacket;
import fr.minenorth.casino.network.ModNetwork.ArenaStatePacket.FighterView;
import fr.minenorth.casino.network.ModNetwork.ArenaStatePacket.PetView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Combats clandestins. Hors casino : pas d'entreprise, paris en ESPÈCES (billets du mod EuroBank), au pari mutuel : les mises perdantes
 * paient les gagnants au prorata, l'organisateur garde une commission sur les mises perdantes. Aucun argent n'est créé : ce qui est misé
 * est rendu, redistribué, versé à l'organisateur ou saisi par la police (trésor public).
 *
 * L'ORGANISATEUR est le joueur qui a posé l'arène : il ouvre ou bâche son arène, choisit les deux combattants, ouvre les paris (durée
 * au choix), lance le combat, règle sa commission et la touche en espèces. Deux types d'arène :
 *  - arène des coqs (5 x 4) : l'organisateur choisit dans son poulailler (coqs tirés au hasard, palmarès gardé, poulailler renouvelable) ;
 *  - grande arène des animaux (7 x 6) : les joueurs y inscrivent leur animal apprivoisé ; l'organisateur choisit parmi les inscrits.
 *    Se bat un double de l'animal (même apparence, sans IA, invulnérable) : le vrai ne risque rien. Qualités et palmarès gardés par
 *    animal, entraînement payé en espèces.
 * Un organisateur qui pose plusieurs arènes du même type n'en a qu'une ouverte, qui change de place régulièrement (comme les boutiques
 * illégales) ; il peut aussi l'installer lui-même ailleurs en ouvrant une autre de ses arènes. Les arènes sont indépendantes entre
 * organisateurs. La police (en service) peut démanteler une arène : paris saisis, arènes de l'organisateur fermées un moment.
 */
@Mod.EventBusSubscriber(modid = MineNorthCasino.MOD_ID)
public final class CockfightService {
    private CockfightService() {}

    private static final Logger LOG = LogUtils.getLogger();
    private static final SecureRandom RNG = new SecureRandom();
    private static final String TAG = "minenorth_coq";

    public enum Phase { IDLE, MATCH, BETS, FIGHT, PAUSE }

    private record Bet(UUID id, String name, int side, long cents) {}

    private record View(ResourceKey<Level> dim, BlockPos master) {}

    record Spot(ResourceKey<Level> dim, BlockPos pos) {}

    /** Animal inscrit dans une grande arène. snapshot : données de l'animal pour son double dans l'arène. */
    private record Entrant(UUID pet, UUID owner, String ownerName, String name, String species, CompoundTag snapshot) {}

    /** Joueurs qui ont l'écran d'une arène ouvert. */
    private static final Map<UUID, View> VIEWERS = new HashMap<>();

    // ------------------------------------------------------------------ une arène (un organisateur, un type) et son combat

    /** Coq du poulailler d'une arène des coqs, avec son palmarès. */
    static final class HouseCock {
        Cock cock;
        int wins, losses;

        HouseCock(Cock c) { cock = c; }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("name", cock.name()); t.putInt("color", cock.color());
            t.putInt("f", cock.force()); t.putInt("a", cock.agility()); t.putInt("s", cock.stamina());
            t.putInt("wins", wins); t.putInt("losses", losses);
            return t;
        }

        static HouseCock load(CompoundTag t) {
            HouseCock h = new HouseCock(new Cock(t.getString("name"), t.getInt("color"), t.getInt("f"), t.getInt("a"), t.getInt("s")));
            h.wins = t.getInt("wins");
            h.losses = t.getInt("losses");
            return h;
        }
    }

    /** Déroulé en cours d'une arène (non conservé : un redémarrage rend les mises). */
    private static final class Pit {
        Phase phase = Phase.IDLE;
        int pickA = -1, pickB = -1;
        CockFight fight;
        /** Animaux qui combattent (grande arène), figés quand le combat est choisi. */
        final Entrant[] fighting = new Entrant[2];
        final List<Bet> bets = new ArrayList<>();
        List<Exchange> script = List.of();
        int step, winner = -1, hopper = -1;
        long deadline, nextStepTick, hopBackTick;
        String message = "";
        final UUID[] mobs = new UUID[2];
        /** Animaux inscrits (grande arène). */
        final List<Entrant> queue = new ArrayList<>();
    }

    static final class Group {
        /** Organisateur : le joueur qui a posé l'arène (null : arène d'avant cette version, gérée par les OP). */
        UUID owner;
        String ownerName = "";
        boolean animals;
        final List<Spot> spots = new ArrayList<>();
        int active = -1;
        long nextMove, closedUntil;
        /** Bâchée par l'organisateur. */
        boolean closed;
        double commission = -1;
        /** Commissions touchées par l'organisateur depuis le début (statistique). */
        long earned;
        final List<HouseCock> coop = new ArrayList<>();
        final transient Pit pit = new Pit();

        Spot activeSpot() { return active >= 0 && active < spots.size() ? spots.get(active) : null; }

        /** Arène ouverte aux joueurs : pas bâchée, pas fermée par la police, installée quelque part. */
        boolean open() { return !closed && System.currentTimeMillis() >= closedUntil && activeSpot() != null; }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            if (owner != null) t.putUUID("owner", owner);
            t.putString("ownerName", ownerName);
            t.putBoolean("animals", animals);
            ListTag l = new ListTag();
            for (Spot s : spots) {
                CompoundTag c = new CompoundTag();
                c.putString("dim", s.dim().location().toString());
                c.putLong("pos", s.pos().asLong());
                l.add(c);
            }
            t.put("spots", l);
            t.putInt("active", active);
            t.putLong("nextMove", nextMove);
            t.putLong("closedUntil", closedUntil);
            t.putBoolean("closed", closed);
            t.putDouble("commission", commission);
            t.putLong("earned", earned);
            ListTag c = new ListTag();
            for (HouseCock h : coop) c.add(h.save());
            t.put("coop", c);
            return t;
        }

        static Group load(CompoundTag t) {
            Group g = new Group();
            if (t.hasUUID("owner")) g.owner = t.getUUID("owner");
            g.ownerName = t.getString("ownerName");
            g.animals = t.getBoolean("animals");
            loadSpots(t.getList("spots", 10), g.spots);
            g.active = t.getInt("active");
            g.nextMove = t.getLong("nextMove");
            g.closedUntil = t.getLong("closedUntil");
            g.closed = t.getBoolean("closed");
            g.commission = t.contains("commission") ? t.getDouble("commission") : -1;
            g.earned = t.getLong("earned");
            ListTag c = t.getList("coop", 10);
            for (int i = 0; i < c.size(); i++) g.coop.add(HouseCock.load(c.getCompound(i)));
            return g;
        }
    }

    private static void loadSpots(ListTag l, List<Spot> out) {
        for (int i = 0; i < l.size(); i++) {
            CompoundTag c = l.getCompound(i);
            out.add(new Spot(ResourceKey.create(Registries.DIMENSION, new ResourceLocation(c.getString("dim"))), BlockPos.of(c.getLong("pos"))));
        }
    }

    // ------------------------------------------------------------------ données conservées

    public static final class Arenas extends SavedData {
        private static final String NAME = "minenorth_casino_coqs";

        final List<Group> groups = new ArrayList<>();
        long fights, staked, seized;
        /** Gains d'un joueur parti avant la fin du combat : remis en espèces à sa prochaine connexion. */
        final Map<UUID, Long> pending = new LinkedHashMap<>();
        /** Fiches des animaux de joueurs (qualités, palmarès), par animal. */
        final Map<UUID, Fighter> fighters = new LinkedHashMap<>();
        /** Caisse de l'ancienne version (commissions du gang) : versée au trésor au premier passage, rien ne se perd. */
        long legacyCaisse;

        static Arenas get(MinecraftServer s) { return s.overworld().getDataStorage().computeIfAbsent(Arenas::load, Arenas::new, NAME); }

        Group groupAt(ResourceKey<Level> dim, BlockPos pos) {
            Spot s = new Spot(dim, pos);
            for (Group g : groups) if (g.spots.contains(s)) return g;
            return null;
        }

        static Arenas load(CompoundTag t) {
            Arenas a = new Arenas();
            ListTag gl = t.getList("groups", 10);
            for (int i = 0; i < gl.size(); i++) a.groups.add(Group.load(gl.getCompound(i)));
            if (!t.contains("groups") && t.contains("spots")) {
                // ancien format : une seule arène des coqs, sans organisateur (gérée par les OP)
                Group g = new Group();
                loadSpots(t.getList("spots", 10), g.spots);
                g.active = t.getInt("active");
                g.closedUntil = t.getLong("closedUntil");
                g.closed = t.getBoolean("closedByOrg");
                g.commission = t.contains("commission") ? t.getDouble("commission") : -1;
                if (!g.spots.isEmpty()) a.groups.add(g);
                a.legacyCaisse = t.getLong("caisse");
            }
            a.legacyCaisse += t.getLong("legacyCaisse");
            a.fights = t.getLong("fights");
            a.staked = t.getLong("staked");
            a.seized = t.getLong("seized");
            CompoundTag p = t.getCompound("pending");
            for (String k : p.getAllKeys()) {
                try { a.pending.put(UUID.fromString(k), p.getLong(k)); } catch (IllegalArgumentException ignored) {}
            }
            ListTag fl = t.getList("fighters", 10);
            for (int i = 0; i < fl.size(); i++) {
                Fighter f = Fighter.load(fl.getCompound(i));
                if (f != null) a.fighters.put(f.pet, f);
            }
            return a;
        }

        @Override
        public CompoundTag save(CompoundTag t) {
            ListTag gl = new ListTag();
            for (Group g : groups) gl.add(g.save());
            t.put("groups", gl);
            t.putLong("legacyCaisse", legacyCaisse);
            t.putLong("fights", fights);
            t.putLong("staked", staked);
            t.putLong("seized", seized);
            CompoundTag p = new CompoundTag();
            pending.forEach((k, v) -> p.putLong(k.toString(), v));
            t.put("pending", p);
            ListTag fl = new ListTag();
            for (Fighter f : fighters.values()) fl.add(f.save());
            t.put("fighters", fl);
            return t;
        }
    }

    /** Fiche d'un animal de joueur : qualités (1 à animal_qualite_max), palmarès, derniers entraînement et combat (ms). */
    static final class Fighter {
        final UUID pet;
        UUID owner;
        String name = "", species = "";
        int force, agility, stamina, wins, losses;
        long lastTrain, lastFight;

        Fighter(UUID pet) { this.pet = pet; }

        int stat(int i) { return i == 0 ? force : i == 1 ? agility : stamina; }

        void raise(int i) { if (i == 0) force++; else if (i == 1) agility++; else stamina++; }

        CompoundTag save() {
            CompoundTag c = new CompoundTag();
            c.putUUID("pet", pet);
            if (owner != null) c.putUUID("owner", owner);
            c.putString("name", name);
            c.putString("species", species);
            c.putInt("force", force); c.putInt("agility", agility); c.putInt("stamina", stamina);
            c.putInt("wins", wins); c.putInt("losses", losses);
            c.putLong("lastTrain", lastTrain); c.putLong("lastFight", lastFight);
            return c;
        }

        static Fighter load(CompoundTag c) {
            if (!c.hasUUID("pet")) return null;
            Fighter f = new Fighter(c.getUUID("pet"));
            if (c.hasUUID("owner")) f.owner = c.getUUID("owner");
            f.name = c.getString("name"); f.species = c.getString("species");
            f.force = c.getInt("force"); f.agility = c.getInt("agility"); f.stamina = c.getInt("stamina");
            f.wins = c.getInt("wins"); f.losses = c.getInt("losses");
            f.lastTrain = c.getLong("lastTrain"); f.lastFight = c.getLong("lastFight");
            return f;
        }
    }

    // ------------------------------------------------------------------ pose et retrait des arènes

    /** Arène posée : elle rejoint les arènes de son organisateur (même type) ; la première devient l'arène ouverte. */
    public static void register(ServerLevel l, BlockPos master, ArenaBlock block, ServerPlayer by) {
        Arenas a = Arenas.get(l.getServer());
        Spot s = new Spot(l.dimension(), master.immutable());
        if (a.groupAt(s.dim(), s.pos()) != null) return;
        UUID owner = by == null ? null : by.getUUID();
        Group g = null;
        for (Group x : a.groups) if (x.animals == block.animals() && java.util.Objects.equals(x.owner, owner)) g = x;
        if (g == null) {
            g = new Group();
            g.owner = owner;
            g.animals = block.animals();
            a.groups.add(g);
        }
        if (by != null) g.ownerName = MineNorth.displayName(by);
        g.spots.add(s);
        if (g.activeSpot() == null) {
            g.active = g.spots.size() - 1;
            g.nextMove = System.currentTimeMillis() + CasinoConfig.get().combats_de_coqs.deplacement_minutes * 60_000L;
        }
        a.setDirty();
        sync(l.getServer(), a);
        if (by != null) by.sendSystemMessage(Component.literal("§6Arène posée : vous en êtes l'organisateur. §7Clic droit dessus pour choisir les "
                + (block.animals() ? "animaux" : "coqs") + ", ouvrir les paris et régler votre commission."));
    }

    /** Arène cassée : l'emplacement disparaît (si c'était l'arène ouverte, le combat en cours est annulé et les mises rendues). */
    public static void unregister(ServerLevel l, BlockPos master) {
        Arenas a = Arenas.get(l.getServer());
        Group g = a.groupAt(l.dimension(), master);
        if (g == null) return;
        Spot s = new Spot(l.dimension(), master);
        Spot was = g.activeSpot();
        g.spots.remove(s);
        if (s.equals(was)) {
            cancel(l.getServer(), g, "L'arène a été démontée : mises rendues.");
            g.active = g.spots.isEmpty() ? -1 : 0;
        } else if (was != null) {
            g.active = g.spots.indexOf(was);
        }
        if (g.spots.isEmpty()) a.groups.remove(g);
        a.setDirty();
        sync(l.getServer(), a);
    }

    private static boolean isOwner(ServerPlayer p, Group g) {
        return g.owner != null ? g.owner.equals(p.getUUID()) : p.hasPermissions(2);
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (!(e.getPlacedBlock().getBlock() instanceof ArenaBlock) || CasinoConfig.get().combats_de_coqs.pose_par_les_joueurs) return;
        if (e.getEntity() instanceof ServerPlayer sp && !sp.hasPermissions(2)) {
            e.setCanceled(true);
            sp.displayClientMessage(Component.literal("§cSeul un OP peut poser une arène."), true);
        }
    }

    /** Seuls l'organisateur et les OP démontent une arène. */
    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (!(e.getState().getBlock() instanceof ArenaBlock ab) || !(e.getPlayer() instanceof ServerPlayer sp) || sp.hasPermissions(2)) return;
        Group g = Arenas.get(sp.server).groupAt(sp.level().dimension(), ab.masterOf(e.getPos(), e.getState()));
        if (g != null && !isOwner(sp, g)) {
            e.setCanceled(true);
            sp.displayClientMessage(Component.literal("§cCette arène appartient à " + (g.ownerName.isEmpty() ? "quelqu'un d'autre" : g.ownerName) + "."), true);
        }
    }

    // ------------------------------------------------------------------ espèces (mod EuroBank, par réflexion : pas de dépendance de compilation)

    private static Method cashIn, takeAll, giveCash;

    private static boolean money() {
        if (giveCash != null) return true;
        try {
            Class<?> m = Class.forName("com.minenorth_eurobank.Money");
            cashIn = m.getMethod("cashIn", Player.class);
            takeAll = m.getMethod("takeAllCash", Player.class);
            giveCash = m.getMethod("giveCash", Player.class, long.class);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.error("[Casino] Espèces EuroBank inutilisables : {}", e.toString());
            return false;
        }
    }

    static long cash(ServerPlayer p) {
        try { return money() ? (long) cashIn.invoke(null, p) : 0; } catch (ReflectiveOperationException e) { return 0; }
    }

    /** Prend {@code cents} d'espèces (monnaie rendue). Faux, sans rien toucher, si le joueur n'en a pas assez. */
    static boolean takeCash(ServerPlayer p, long cents) {
        if (!money() || cash(p) < cents) return false;
        try {
            long all = (long) takeAll.invoke(null, p);
            giveCash.invoke(null, p, all - cents);
            return true;
        } catch (ReflectiveOperationException e) {
            LOG.error("[Casino] Prise d'espèces impossible : {}", e.toString());
            return false;
        }
    }

    /** Remet des espèces au joueur ; hors ligne (ou EuroBank absent), elles l'attendent à sa prochaine connexion. */
    static void payCash(MinecraftServer s, UUID id, long cents) {
        if (cents <= 0) return;
        ServerPlayer p = s.getPlayerList().getPlayer(id);
        if (p != null && money()) {
            try { giveCash.invoke(null, p, cents); return; } catch (ReflectiveOperationException e) { LOG.error("[Casino] Espèces non remises : {}", e.toString()); }
        }
        Arenas a = Arenas.get(s);
        a.pending.merge(id, cents, Long::sum);
        a.setDirty();
    }

    // ------------------------------------------------------------------ police

    private static boolean police(ServerPlayer p) {
        if (!MineNorth.police().isPolice(p)) return false;
        return !CasinoConfig.get().combats_de_coqs.police_en_service || MineNorth.police().onDuty(p.server, p.getUUID());
    }

    /** Alerte police (chat, repère de carte, onglet ALERTES) via le mod Police, position approximative (un témoin, pas un GPS). */
    private static void alertPolice(MinecraftServer s, Group g, Spot spot) {
        BlockPos pos = spot.pos();
        int x = pos.getX() + RNG.nextInt(41) - 20, z = pos.getZ() + RNG.nextInt(41) - 20;
        String label = g.animals ? "Combat d'animaux clandestin" : "Combat de coqs clandestin";
        String desc = "un témoin signale des cris d'animaux et des paris dans le secteur (position approximative).";
        try {
            Class.forName("fr.minenorth.police.RadarHack").getMethod("raise", MinecraftServer.class, String.class, String.class, String.class,
                    int.class, int.class, int.class, long.class).invoke(null, s, label, desc, spot.dim().location().toString(), x, pos.getY(), z, 10L);
            return;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // mod Police absent ou ancien : simple message aux policiers
        }
        for (ServerPlayer p : MineNorth.police().online(s))
            p.sendSystemMessage(Component.literal("§c§l[ALERTE] §r§c" + label + " vers §f" + x + " " + pos.getY() + " " + z + " §7— " + desc));
    }

    // ------------------------------------------------------------------ ouverture de l'écran

    public static void open(ServerPlayer sp, BlockPos master) {
        Arenas a = Arenas.get(sp.server);
        Group g = a.groupAt(sp.level().dimension(), master);
        if (g == null) {
            // arène posée avant d'être suivie (ou données perdues) : celui qui clique en premier n'en devient pas maître, seul un OP la reprend
            if (sp.level().getBlockState(master).getBlock() instanceof ArenaBlock ab && sp.hasPermissions(2)) {
                register(sp.serverLevel(), master, ab, sp);
                g = a.groupAt(sp.level().dimension(), master);
            }
            if (g == null) { sp.displayClientMessage(Component.literal("§7Des caisses empilées sous une bâche. Il n'y a personne."), true); return; }
        }
        Spot here = new Spot(sp.level().dimension(), master);
        boolean owner = isOwner(sp, g);
        if (!here.equals(g.activeSpot())) {
            // l'organisateur installe son arène ici (entre deux combats) ; pour les autres, ce n'est qu'un tas de caisses
            if (owner && (g.pit.phase == Phase.IDLE || g.pit.phase == Phase.MATCH || g.pit.phase == Phase.PAUSE) && g.pit.bets.isEmpty()) {
                removeMobs(sp.server, g);
                g.active = g.spots.indexOf(here);
                g.nextMove = System.currentTimeMillis() + CasinoConfig.get().combats_de_coqs.deplacement_minutes * 60_000L;
                a.setDirty();
                sync(sp.server, a);
                sp.displayClientMessage(Component.literal("§6Votre arène s'installe ici."), true);
            } else {
                sp.displayClientMessage(Component.literal(owner ? "§7Un combat est en cours dans votre autre arène."
                        : police(sp) ? "§7Des caisses bâchées… rien de suspect pour l'instant." : "§7Des caisses empilées sous une bâche. Il n'y a personne."), true);
                return;
            }
        }
        if (!g.open() && !owner) {
            sp.displayClientMessage(Component.literal(police(sp) ? "§7Des caisses bâchées… rien de suspect pour l'instant."
                    : "§7Des caisses empilées sous une bâche. Il n'y a personne."), true);
            return;
        }
        if (!g.animals) fillCoop(g);
        CasinoConfig.Coqs k = CasinoConfig.get().combats_de_coqs;
        VIEWERS.put(sp.getUUID(), new View(sp.level().dimension(), master.immutable()));
        ModNetwork.send(sp, new ModNetwork.OpenPacket(5, g.animals ? "Grande arène · animaux" : "Arène des coqs", CasinoConfig.cents(k.mise_min_euros),
                CasinoConfig.cents(k.mise_max_euros), CasinoConfig.centsList(k.mises_rapides_euros), List.of(), List.of(), List.of(), List.of(),
                (float) commission(g), false));
        send(sp, g, -1);
    }

    /** Commission en vigueur (%) : réglée par l'organisateur, sinon celle de la config. */
    private static double commission(Group g) {
        CasinoConfig.Coqs k = CasinoConfig.get().combats_de_coqs;
        return g.commission >= 0 ? Math.min(g.commission, k.commission_max_pourcent) : k.commission_pourcent;
    }

    /** Arène dont le joueur a l'écran ouvert, s'il est encore assez près (sinon message et null). */
    private static Group guard(ServerPlayer sp) {
        View v = VIEWERS.get(sp.getUUID());
        Group g = v == null ? null : Arenas.get(sp.server).groupAt(v.dim(), v.master());
        if (g == null || !new Spot(v.dim(), v.master()).equals(g.activeSpot())) { reply(sp, false, "L'arène est fermée."); return null; }
        int d = CasinoConfig.get().distance_max_blocs + (g.animals ? 6 : 4);
        if (sp.level().dimension() != v.dim() || sp.blockPosition().distSqr(v.master()) > (double) d * d) { reply(sp, false, "Vous êtes trop loin."); return null; }
        return g;
    }

    private static void reply(ServerPlayer p, boolean ok, String msg) { ModNetwork.send(p, new ModNetwork.MsgPacket(ok, msg)); }

    // ------------------------------------------------------------------ actions

    public static final int BET = 0, RAID = 1, REGISTER = 2, WITHDRAW = 3, TRAIN = 4;
    public static final int PICK = 10, OPEN_BETS = 11, START_NOW = 12, CLEAR = 13, TOGGLE_OPEN = 14, SET_COMMISSION = 15, REROLL = 16;

    public static void action(ServerPlayer sp, int action, int a, long b) {
        Group g = guard(sp);
        if (g == null) return;
        if (action >= PICK) { organize(sp, g, action, a, b); return; }
        if (!g.open() && action != RAID) { reply(sp, false, "L'organisateur a bâché l'arène."); return; }
        switch (action) {
            case BET -> bet(sp, g, a, b);
            case RAID -> {
                if (!police(sp)) {
                    reply(sp, false, CasinoConfig.get().combats_de_coqs.police_en_service && MineNorth.police().isPolice(sp)
                            ? "Prenez votre service pour intervenir." : "Action réservée à la police.");
                    return;
                }
                raid(sp, g);
            }
            case REGISTER -> registerPet(sp, g);
            case WITHDRAW -> withdraw(sp, g);
            case TRAIN -> train(sp, g, a);
            default -> { }
        }
    }

    private static void bet(ServerPlayer sp, Group g, int side, long cents) {
        Pit pit = g.pit;
        CasinoConfig.Coqs k = CasinoConfig.get().combats_de_coqs;
        if (police(sp)) { reply(sp, false, "Un policier ne parie pas : démantelez plutôt l'arène."); return; }
        if (pit.phase != Phase.BETS) { reply(sp, false, pit.phase == Phase.FIGHT ? "Les paris sont fermés pour ce combat." : "Les paris ne sont pas encore ouverts."); return; }
        if (side != 0 && side != 1) return;
        long min = CasinoConfig.cents(k.mise_min_euros), max = CasinoConfig.cents(k.mise_max_euros);
        long already = 0;
        for (Bet x : pit.bets) if (x.id().equals(sp.getUUID())) {
            if (x.side() != side) { reply(sp, false, "Vous avez déjà parié sur l'autre combattant."); return; }
            already += x.cents();
        }
        if (cents % 100 != 0) { reply(sp, false, "Les paris se font en euros entiers."); return; }
        if (cents < min || already + cents > max) { reply(sp, false, "Pari entre " + euros(min) + " et " + euros(max) + " par combat."); return; }
        if (!takeCash(sp, cents)) { reply(sp, false, "Pas assez d'espèces sur vous : ici, on ne prend pas la carte."); return; }
        pit.bets.add(new Bet(sp.getUUID(), MineNorth.displayName(sp), side, cents));
        Arenas a = Arenas.get(sp.server);
        a.staked += cents;
        a.setDirty();
        pit.message = MineNorth.displayName(sp) + " mise " + euros(cents) + " sur " + (side == 0 ? pit.fight.a : pit.fight.b).name() + ".";
        Spot s = g.activeSpot();
        sp.level().playSound(null, s.pos(), SoundEvents.CHICKEN_AMBIENT, SoundSource.NEUTRAL, 1f, side == 0 ? 0.9f : 1.2f);
        reply(sp, true, "Pari enregistré : " + euros(already + cents) + " sur " + (side == 0 ? pit.fight.a : pit.fight.b).name() + ".");
        broadcast(sp.server, g);
    }

    // ------------------------------------------------------------------ menu de l'organisateur

    private static void organize(ServerPlayer sp, Group g, int action, int a, long b) {
        if (!isOwner(sp, g)) { reply(sp, false, "Réservé à l'organisateur de l'arène."); return; }
        Pit pit = g.pit;
        CasinoConfig.Coqs k = CasinoConfig.get().combats_de_coqs;
        MinecraftServer s = sp.server;
        switch (action) {
            case PICK -> {
                if (pit.phase != Phase.IDLE && pit.phase != Phase.MATCH) { reply(sp, false, "Le combat est lancé : attendez la fin pour changer."); return; }
                int idx = (int) b;
                if (a != 0 && a != 1 || idx < -1 || idx >= rosterSize(g)) return;
                if (idx >= 0 && idx == (a == 0 ? pit.pickB : pit.pickA)) { reply(sp, false, "Ce combattant est déjà dans l'autre coin."); return; }
                if (idx >= 0 && g.animals) {
                    Fighter f = Arenas.get(s).fighters.get(pit.queue.get(idx).pet());
                    long rest = f == null ? 0 : f.lastFight + k.repos_animal_minutes * 60_000L - System.currentTimeMillis();
                    if (rest > 0) { reply(sp, false, pit.queue.get(idx).name() + " se repose encore " + Math.max(1, rest / 60_000) + " min."); return; }
                }
                if (a == 0) pit.pickA = idx; else pit.pickB = idx;
                buildMatch(g);
            }
            case OPEN_BETS -> {
                if (!g.open()) { reply(sp, false, "Ouvrez d'abord l'arène (onglet Organisation)."); return; }
                if (pit.phase != Phase.MATCH) { reply(sp, false, pit.phase == Phase.IDLE ? "Choisissez d'abord les deux combattants." : "Les paris sont déjà ouverts."); return; }
                int secs = k.durees_paris_secondes.contains(a) ? a : k.durees_paris_secondes.get(0);
                pit.phase = Phase.BETS;
                pit.deadline = System.currentTimeMillis() + secs * 1000L;
                pit.message = "Paris ouverts " + secs + " s : " + pit.fight.a.name() + " contre " + pit.fight.b.name() + ". Faites vos jeux !";
                Spot spot = g.activeSpot();
                ServerLevel l = s.getLevel(spot.dim());
                if (l != null) l.playSound(null, spot.pos(), SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 0.8f, 1.4f);
            }
            case START_NOW -> {
                if (!g.open()) { reply(sp, false, "Ouvrez d'abord l'arène."); return; }
                if (pit.phase != Phase.MATCH && pit.phase != Phase.BETS) { reply(sp, false, "Choisissez d'abord les deux combattants."); return; }
                Spot spot = g.activeSpot();
                startFight(s, g, s.getLevel(spot.dim()), spot);
                return;
            }
            case CLEAR -> {
                if (pit.phase == Phase.BETS) {
                    for (Bet x : pit.bets) payCash(s, x.id(), x.cents());
                    pit.bets.clear();
                } else if (pit.phase != Phase.MATCH) return;
                pit.pickA = pit.pickB = -1;
                buildMatch(g);
                pit.message = "Combat annulé par l'organisateur : mises rendues.";
            }
            case TOGGLE_OPEN -> {
                if (!g.closed && pit.phase != Phase.IDLE && pit.phase != Phase.MATCH && pit.phase != Phase.PAUSE) {
                    reply(sp, false, "Un combat est en cours : bâchez après la fin.");
                    return;
                }
                g.closed = !g.closed;
                pit.message = g.closed ? "L'organisateur bâche l'arène : plus de combat pour l'instant." : "L'arène ouvre : choix des combattants en cours.";
                if (g.closed) { removeMobs(s, g); pit.phase = pit.phase == Phase.PAUSE ? Phase.IDLE : pit.phase; }
            }
            case SET_COMMISSION -> {
                if (pit.phase == Phase.BETS || pit.phase == Phase.FIGHT) { reply(sp, false, "Changez la commission entre deux combats."); return; }
                g.commission = Math.max(0, Math.min(k.commission_max_pourcent, a / 10.0));
            }
            case REROLL -> {
                if (g.animals) return;
                if (pit.phase != Phase.IDLE && pit.phase != Phase.MATCH) { reply(sp, false, "Attendez la fin du combat."); return; }
                g.coop.clear();
                fillCoop(g);
                pit.pickA = pit.pickB = -1;
                buildMatch(g);
                pit.message = "Nouveaux coqs au poulailler !";
            }
            default -> { return; }
        }
        Arenas.get(s).setDirty();
        sync(s, Arenas.get(s));
        broadcast(s, g);
    }

    /** Combat choisi par l'organisateur : les deux coins remplis = combat prêt (cotes calculées), sinon retour au choix. */
    private static void buildMatch(Group g) {
        Pit pit = g.pit;
        pit.fighting[0] = pit.fighting[1] = null;
        if (pit.pickA < 0 || pit.pickB < 0 || pit.pickA >= rosterSize(g) || pit.pickB >= rosterSize(g)) {
            pit.fight = null;
            pit.phase = Phase.IDLE;
            pit.message = g.animals ? "L'organisateur choisit deux animaux parmi les inscrits." : "L'organisateur choisit deux coqs au poulailler.";
            return;
        }
        Cock ca = cockAt(g, pit.pickA), cb = cockAt(g, pit.pickB);
        if (g.animals) { pit.fighting[0] = pit.queue.get(pit.pickA); pit.fighting[1] = pit.queue.get(pit.pickB); }
        pit.fight = new CockFight(RNG, ca, cb);
        pit.script = List.of();
        pit.step = 0;
        pit.winner = -1;
        pit.phase = Phase.MATCH;
        pit.message = "Prochain combat : " + ca.name() + " contre " + cb.name() + ". Les paris vont ouvrir.";
    }

    private static int rosterSize(Group g) { return g.animals ? g.pit.queue.size() : g.coop.size(); }

    /** Combattant n° i du poulailler, ou animal inscrit n° i (couleur tirée de son espèce, qualités de sa fiche). */
    private static Cock cockAt(Group g, int i) {
        if (!g.animals) return g.coop.get(i).cock;
        Entrant e = g.pit.queue.get(i);
        MinecraftServer s = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        Fighter f = s == null ? null : Arenas.get(s).fighters.get(e.pet());
        int base = Math.max(1, CasinoConfig.get().combats_de_coqs.animal_qualites_depart);
        int color = 0x404040 | (e.species().hashCode() & 0xBFBFBF);
        return new Cock(e.name(), color, f == null ? base : f.force, f == null ? base : f.agility, f == null ? base : f.stamina);
    }

    /** Complète le poulailler d'une arène des coqs (noms tous différents). */
    private static void fillCoop(Group g) {
        int n = CasinoConfig.get().combats_de_coqs.coqs_au_poulailler;
        Set<String> names = new HashSet<>();
        for (HouseCock h : g.coop) names.add(h.cock.name());
        boolean added = false;
        while (g.coop.size() < n) {
            Cock c = CockFight.random(RNG, names);
            names.add(c.name());
            g.coop.add(new HouseCock(c));
            added = true;
        }
        if (added) {
            MinecraftServer s = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (s != null) Arenas.get(s).setDirty();
        }
    }

    private static String euros(long cents) {
        return (cents / 100) + (cents % 100 == 0 ? "" : "," + String.format("%02d", cents % 100)) + " €";
    }

    // ------------------------------------------------------------------ animaux des joueurs (grande arène)

    /** Animal apprivoisé du joueur le plus proche (pas un chien de police), ou null. */
    private static TamableAnimal nearestPet(ServerPlayer sp) {
        int d = CasinoConfig.get().combats_de_coqs.distance_animal_blocs;
        TamableAnimal best = null;
        double bd = Double.MAX_VALUE;
        for (TamableAnimal t : sp.serverLevel().getEntitiesOfClass(TamableAnimal.class, sp.getBoundingBox().inflate(d),
                t -> t.isAlive() && sp.getUUID().equals(t.getOwnerUUID()) && !t.getTags().contains(TAG) && !t.getTags().contains("minenorthpets_k9"))) {
            double dd = t.distanceToSqr(sp);
            if (dd < bd) { bd = dd; best = t; }
        }
        return best;
    }

    /** Espèce en français (mod Animaux si présent, sinon nom du jeu). */
    private static String species(Entity e) {
        ResourceLocation k = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
        try {
            Object fr = Class.forName("fr.minenorth.pets.SpeciesFr").getMethod("of", String.class).invoke(null, k == null ? "" : k.getPath());
            if (fr instanceof String str && !str.isBlank()) return str;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // mod Animaux absent
        }
        return e.getType().getDescription().getString();
    }

    private static Fighter fighter(Arenas a, TamableAnimal pet, ServerPlayer owner) {
        int start = Math.max(1, CasinoConfig.get().combats_de_coqs.animal_qualites_depart);
        Fighter f = a.fighters.computeIfAbsent(pet.getUUID(), id -> {
            Fighter n = new Fighter(id);
            n.force = n.agility = n.stamina = start;
            return n;
        });
        f.owner = owner.getUUID();
        f.name = PokerService.cut(pet.getDisplayName().getString(), 40);
        f.species = PokerService.cut(species(pet), 40);
        a.setDirty();
        return f;
    }

    /** Données de l'animal pour son double : sans identité, maître, laisse, passagers ni données des autres mods. */
    private static CompoundTag snapshot(TamableAnimal pet) {
        CompoundTag t = pet.saveWithoutId(new CompoundTag());
        ResourceLocation k = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(pet.getType());
        t.putString("id", k == null ? "minecraft:wolf" : k.toString());
        for (String key : new String[]{"UUID", "Owner", "ForgeData", "ForgeCaps", "Leash", "Passengers", "Sitting", "Tags", "ActiveEffects", "Brain"}) t.remove(key);
        return t;
    }

    private static int queued(Group g, UUID pet) {
        for (int i = 0; i < g.pit.queue.size(); i++) if (g.pit.queue.get(i).pet().equals(pet)) return i;
        return -1;
    }

    private static void registerPet(ServerPlayer sp, Group g) {
        CasinoConfig.Coqs k = CasinoConfig.get().combats_de_coqs;
        if (!g.animals) { reply(sp, false, "Ici, ce sont les coqs de l'organisateur qui se battent : cherchez la grande arène."); return; }
        if (police(sp)) { reply(sp, false, "Un policier en service n'inscrit pas d'animal."); return; }
        TamableAnimal pet = nearestPet(sp);
        if (pet == null) { reply(sp, false, "Aucun de vos animaux apprivoisés à moins de " + k.distance_animal_blocs + " blocs."); return; }
        if (queued(g, pet.getUUID()) >= 0) { reply(sp, false, "Il est déjà inscrit."); return; }
        if (g.pit.queue.size() >= k.animaux_inscrits_max) { reply(sp, false, "La liste des inscrits est complète (" + k.animaux_inscrits_max + ")."); return; }
        Arenas a = Arenas.get(sp.server);
        Fighter f = fighter(a, pet, sp);
        g.pit.queue.add(new Entrant(pet.getUUID(), sp.getUUID(), MineNorth.displayName(sp), f.name, f.species, snapshot(pet)));
        g.pit.message = MineNorth.displayName(sp) + " inscrit " + f.name + " (" + f.species + ").";
        sp.serverLevel().sendParticles(ParticleTypes.ANGRY_VILLAGER, pet.getX(), pet.getY() + pet.getBbHeight(), pet.getZ(), 3, 0.2, 0.1, 0.2, 0);
        reply(sp, true, f.name + " est inscrit : l'organisateur peut le choisir pour un combat.");
        broadcast(sp.server, g);
    }

    private static void withdraw(ServerPlayer sp, Group g) {
        Pit pit = g.pit;
        for (int i = 0; i < pit.queue.size(); i++) {
            Entrant e = pit.queue.get(i);
            if (!e.owner().equals(sp.getUUID())) continue;
            boolean picked = i == pit.pickA || i == pit.pickB;
            if (picked && pit.phase != Phase.IDLE && pit.phase != Phase.MATCH) { reply(sp, false, "Trop tard : il est engagé dans le combat."); return; }
            pit.queue.remove(i);
            // les choix de l'organisateur suivent le décalage de la liste
            pit.pickA = shift(pit.pickA, i);
            pit.pickB = shift(pit.pickB, i);
            if (pit.phase == Phase.IDLE || pit.phase == Phase.MATCH) buildMatch(g);
            pit.message = e.name() + " est retiré de l'arène.";
            broadcast(sp.server, g);
            return;
        }
        reply(sp, false, "Vous n'avez pas d'animal inscrit.");
    }

    private static int shift(int pick, int removed) { return pick == removed ? -1 : pick > removed ? pick - 1 : pick; }

    /** Entraînement : +1 dans une qualité (0 force, 1 agilité, 2 endurance), payé en espèces, une séance par délai. */
    private static void train(ServerPlayer sp, Group g, int stat) {
        CasinoConfig.Coqs k = CasinoConfig.get().combats_de_coqs;
        if (stat < 0 || stat > 2 || !g.animals) return;
        TamableAnimal pet = nearestPet(sp);
        if (pet == null) { reply(sp, false, "Amenez votre animal (moins de " + k.distance_animal_blocs + " blocs) pour l'entraîner."); return; }
        int q = queued(g, pet.getUUID());
        if (q >= 0 && (q == g.pit.pickA || q == g.pit.pickB) && g.pit.phase != Phase.IDLE) { reply(sp, false, "Il est engagé dans le combat : entraînez-le après."); return; }
        Arenas a = Arenas.get(sp.server);
        Fighter f = fighter(a, pet, sp);
        long wait = f.lastTrain + k.entrainement_minutes * 60_000L - System.currentTimeMillis();
        if (wait > 0) { reply(sp, false, f.name + " est fatigué : prochaine séance dans " + Math.max(1, wait / 60_000) + " min."); return; }
        int cur = f.stat(stat);
        if (cur >= k.animal_qualite_max) { reply(sp, false, "Cette qualité est déjà au maximum."); return; }
        long cost = CasinoConfig.cents(k.prix_entrainement_euros) * Math.max(1, cur);
        if (!takeCash(sp, cost)) { reply(sp, false, "Séance à " + euros(cost) + " en espèces : vous n'avez pas assez."); return; }
        f.raise(stat);
        f.lastTrain = System.currentTimeMillis();
        a.setDirty();
        ServerLevel l = sp.serverLevel();
        l.sendParticles(ParticleTypes.HAPPY_VILLAGER, pet.getX(), pet.getY() + pet.getBbHeight(), pet.getZ(), 10, 0.3, 0.3, 0.3, 0);
        l.playSound(null, pet.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.NEUTRAL, 0.5f, 1.6f);
        reply(sp, true, f.name + " : " + new String[]{"force", "agilité", "endurance"}[stat] + " " + f.stat(stat) + "/" + k.animal_qualite_max
                + " (" + euros(cost) + ").");
        if (q >= 0 && g.pit.phase == Phase.MATCH) buildMatch(g);   // cotes recalculées
        broadcast(sp.server, g);
    }

    // ------------------------------------------------------------------ police, annulation

    /** Descente de police : paris saisis (trésor public), combat arrêté, arènes de l'organisateur fermées un moment, liste des parieurs. */
    private static void raid(ServerPlayer officer, Group g) {
        MinecraftServer s = officer.server;
        Arenas a = Arenas.get(s);
        Pit pit = g.pit;
        Spot spot = g.activeSpot();
        long bets = 0;
        List<String> names = new ArrayList<>();
        for (Bet b : pit.bets) { bets += b.cents(); if (!names.contains(b.name())) names.add(b.name()); }
        if (bets > 0) MineNorth.treasury().collect(s, bets, "police:saisie_combats_de_coqs");
        a.seized += bets;
        pit.bets.clear();
        Component alert = Component.literal("§c§l🚨 DESCENTE DE POLICE ! §r§cL'arène est démantelée, les paris sont saisis.");
        for (ServerPlayer p : s.getPlayerList().getPlayers())
            if (p.level().dimension() == spot.dim() && p.blockPosition().distSqr(spot.pos()) < 40 * 40) p.sendSystemMessage(alert);
        officer.sendSystemMessage(Component.literal("§9[Police] §fSaisie : §e" + euros(bets) + "§f. Organisateur : §e"
                + (g.ownerName.isEmpty() ? "inconnu" : g.ownerName) + "§f. " + (names.isEmpty() ? "Aucun parieur en cours." : "Parieurs : §e" + String.join("§f, §e", names))));
        ServerLevel l = s.getLevel(spot.dim());
        if (l != null) {
            l.playSound(null, spot.pos(), SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 1.5f, 0.8f);
            for (UUID id : pit.mobs) {
                Entity c = id == null ? null : l.getEntity(id);
                if (c != null) l.sendParticles(ParticleTypes.POOF, c.getX(), c.getY() + 0.3, c.getZ(), 8, 0.2, 0.2, 0.2, 0.02);
            }
        }
        removeMobs(s, g);
        pit.queue.clear();
        pit.pickA = pit.pickB = -1;
        buildMatch(g);
        pit.message = "Descente de police : arène démantelée.";
        broadcast(s, g);
        closeViewers(g);
        g.closedUntil = System.currentTimeMillis() + CasinoConfig.get().combats_de_coqs.fermeture_apres_descente_minutes * 60_000L;
        a.setDirty();
        sync(s, a);
        LOG.info("[Casino] Descente de police sur l'arène {} ({}) par {} : {} cts saisis", spot.pos(), g.ownerName, officer.getGameProfile().getName(), bets);
    }

    /** Combat annulé (arène démontée, serveur arrêté) : chacun récupère sa mise. */
    private static void cancel(MinecraftServer s, Group g, String why) {
        Pit pit = g.pit;
        for (Bet b : pit.bets) payCash(s, b.id(), b.cents());
        pit.bets.clear();
        removeMobs(s, g);
        pit.pickA = pit.pickB = -1;
        buildMatch(g);
        pit.message = why;
        broadcast(s, g);
    }

    private static void closeViewers(Group g) {
        MinecraftServer s = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (s == null) return;
        VIEWERS.entrySet().removeIf(e -> Arenas.get(s).groupAt(e.getValue().dim(), e.getValue().master()) == g);
    }

    // ------------------------------------------------------------------ horloge

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        MinecraftServer s = e.getServer();
        long tickNo = s.getTickCount();
        Arenas a = Arenas.get(s);
        if (tickNo % 20 == 0) rotation(s, a);
        long now = System.currentTimeMillis();
        for (Group g : new ArrayList<>(a.groups)) {
            Pit pit = g.pit;
            if (pit.phase == Phase.IDLE || pit.phase == Phase.MATCH) continue;
            Spot spot = g.activeSpot();
            if (spot == null) { cancel(s, g, "L'arène est fermée : mises rendues."); continue; }
            ServerLevel l = s.getLevel(spot.dim());
            switch (pit.phase) {
                case BETS -> {
                    if (now < pit.deadline) { if (tickNo % 20 == 0) broadcast(s, g); continue; }
                    startFight(s, g, l, spot);
                }
                case FIGHT -> {
                    if (l == null) continue;
                    if (pit.hopper >= 0 && tickNo >= pit.hopBackTick) { place(l, g, spot); pit.hopper = -1; }
                    if (tickNo < pit.nextStepTick) continue;
                    if (pit.step < pit.script.size()) {
                        playExchange(s, g, l, spot, pit.script.get(pit.step));
                        pit.step++;
                        pit.nextStepTick = tickNo + 14;
                        continue;
                    }
                    settle(s, g, l, spot);
                }
                case PAUSE -> {
                    if (now < pit.deadline) continue;
                    removeMobs(s, g);
                    pit.pickA = pit.pickB = -1;
                    buildMatch(g);
                    broadcast(s, g);
                }
                default -> { }
            }
        }
    }

    private static void startFight(MinecraftServer s, Group g, ServerLevel l, Spot spot) {
        Pit pit = g.pit;
        pit.phase = Phase.FIGHT;
        pit.script = pit.fight.simulate(RNG);
        pit.step = 0;
        pit.nextStepTick = s.getTickCount() + 40;
        pit.message = (g.animals ? "Les combattants entrent dans l'arène…" : "Les coqs entrent dans l'arène…") + " Les paris sont fermés !";
        if (l != null) {
            spawnMobs(l, g, spot);
            l.playSound(null, spot.pos(), g.animals ? SoundEvents.WOLF_GROWL : SoundEvents.CHICKEN_AMBIENT, SoundSource.NEUTRAL, 1.5f, 0.6f);
        }
        if (RNG.nextDouble() < CasinoConfig.get().combats_de_coqs.chance_alerte_police) alertPolice(s, g, spot);
        Arenas a = Arenas.get(s);
        a.fights++;
        a.setDirty();
        broadcast(s, g);
    }

    private static void playExchange(MinecraftServer s, Group g, ServerLevel l, Spot spot, Exchange x) {
        Pit pit = g.pit;
        Entity att = mob(l, g, x.attacker()), def = mob(l, g, 1 - x.attacker());
        if (att != null && def != null) {
            // l'attaquant bondit sur l'autre, revient à sa place 7 ticks plus tard
            Vec3 to = def.position().subtract(att.position()).scale(0.6).add(att.position());
            att.teleportTo(to.x, att.getY() + 0.35, to.z);
            att.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, def.getEyePosition());
            pit.hopper = x.attacker();
            pit.hopBackTick = s.getTickCount() + 7;
            if (x.damage() > 0) {
                l.broadcastDamageEvent(def, l.damageSources().generic());
                if (def instanceof Chicken) l.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.FEATHER)), def.getX(), def.getY() + 0.4,
                        def.getZ(), x.crit() ? 14 : 6, 0.15, 0.15, 0.15, 0.08);
                else l.sendParticles(ParticleTypes.DAMAGE_INDICATOR, def.getX(), def.getY() + def.getBbHeight() * 0.6, def.getZ(), x.crit() ? 8 : 3, 0.2, 0.2, 0.2, 0.1);
                if (x.crit()) l.sendParticles(ParticleTypes.CRIT, def.getX(), def.getY() + 0.5, def.getZ(), 10, 0.2, 0.2, 0.2, 0.2);
            } else {
                l.sendParticles(ParticleTypes.CLOUD, def.getX(), def.getY() + 0.1, def.getZ(), 3, 0.1, 0.05, 0.1, 0.01);
            }
        }
        boolean chick = def == null || def instanceof Chicken;
        l.playSound(null, spot.pos(), x.damage() > 0 ? (chick ? SoundEvents.CHICKEN_HURT : SoundEvents.PLAYER_ATTACK_STRONG) : SoundEvents.PLAYER_ATTACK_WEAK,
                SoundSource.NEUTRAL, 1f, 0.8f + RNG.nextFloat() * 0.5f);
        Cock a = x.attacker() == 0 ? pit.fight.a : pit.fight.b, d = x.attacker() == 0 ? pit.fight.b : pit.fight.a;
        pit.message = x.damage() == 0 ? d.name() + " esquive l'attaque de " + a.name() + " !"
                : (x.crit() ? "COUP CRITIQUE ! " : "") + a.name() + " frappe " + d.name() + " (-" + x.damage() + ").";
        int seq = pit.step;
        for (ServerPlayer p : viewers(s, g)) send(p, g, seq);
    }

    /** Fin du combat : le perdant s'effondre, les gagnants se partagent les mises perdantes, l'organisateur touche sa commission. */
    private static void settle(MinecraftServer s, Group g, ServerLevel l, Spot spot) {
        CasinoConfig.Coqs k = CasinoConfig.get().combats_de_coqs;
        Arenas a = Arenas.get(s);
        Pit pit = g.pit;
        pit.winner = CockFight.winner(pit.script);
        Cock w = pit.winner == 0 ? pit.fight.a : pit.fight.b;
        Entity loser = mob(l, g, 1 - pit.winner);
        if (loser != null) l.broadcastEntityEvent(loser, (byte) 3);   // animation de mort côté client
        l.playSound(null, spot.pos(), g.animals ? SoundEvents.WOLF_WHINE : SoundEvents.CHICKEN_DEATH, SoundSource.NEUTRAL, 1.2f, 0.8f);
        l.playSound(null, spot.pos(), SoundEvents.PLAYER_LEVELUP, SoundSource.NEUTRAL, 0.6f, 1.4f);
        long poolW = 0, poolL = 0;
        for (Bet b : pit.bets) if (b.side() == pit.winner) poolW += b.cents(); else poolL += b.cents();
        long commission = 0;
        if (poolW == 0) {
            for (Bet b : pit.bets) payCash(s, b.id(), b.cents());
            pit.message = w.name() + " gagne ! Personne n'avait misé sur lui : mises rendues.";
        } else {
            commission = Math.round(poolL * commission(g) / 100.0);
            long share = poolL - commission, paidOut = 0;
            Map<UUID, Long> gains = new LinkedHashMap<>();
            for (Bet b : pit.bets) {
                if (b.side() != pit.winner) continue;
                long gain = b.cents() + share * b.cents() / poolW;   // arrondi au centime inférieur : le reste va à la commission
                gains.merge(b.id(), gain, Long::sum);
                paidOut += gain;
            }
            commission = poolW + poolL - paidOut;
            gains.forEach((id, gain) -> {
                payCash(s, id, gain);
                ServerPlayer p = s.getPlayerList().getPlayer(id);
                if (p != null) p.displayClientMessage(Component.literal("§a" + w.name() + " gagne : vous touchez §e" + euros(gain) + "§a en espèces."), true);
            });
            pit.message = w.name() + " gagne ! " + gains.size() + " gagnant(s) se partagent " + euros(poolW + poolL - commission) + ".";
        }
        if (commission > 0) {
            // la commission revient à l'organisateur (espèces ; hors ligne : à sa prochaine connexion). Arène sans organisateur : trésor.
            if (g.owner != null) {
                payCash(s, g.owner, commission);
                g.earned += commission;
                ServerPlayer o = s.getPlayerList().getPlayer(g.owner);
                if (o != null) o.sendSystemMessage(Component.literal("§6Votre commission sur le combat : §e" + euros(commission) + "§6 en espèces."));
            } else {
                MineNorth.treasury().collect(s, commission, "casino:combats_de_coqs");
            }
        }
        // palmarès
        long nowMs = System.currentTimeMillis();
        for (int i = 0; i < 2; i++) {
            boolean won = i == pit.winner;
            if (!g.animals) {
                int idx = i == 0 ? pit.pickA : pit.pickB;
                if (idx >= 0 && idx < g.coop.size()) { if (won) g.coop.get(idx).wins++; else g.coop.get(idx).losses++; }
                continue;
            }
            Entrant e = pit.fighting[i];
            Fighter f = e == null ? null : a.fighters.get(e.pet());
            if (f == null) continue;
            if (won) f.wins++; else f.losses++;
            f.lastFight = nowMs;
            ServerPlayer o = s.getPlayerList().getPlayer(e.owner());
            if (o != null) o.sendSystemMessage(Component.literal(won ? "§6🏆 " + f.name + " remporte le combat ! (" + f.wins + " victoire(s))"
                    : "§7" + f.name + " a perdu ce combat. Il se repose avant le prochain."));
        }
        if (g.animals) {
            // les deux animaux qui ont combattu quittent la liste des inscrits (à réinscrire après leur repos)
            for (Entrant e : pit.fighting) if (e != null) pit.queue.removeIf(q -> q.pet().equals(e.pet()));
        }
        a.setDirty();
        pit.bets.clear();
        pit.phase = Phase.PAUSE;
        pit.deadline = System.currentTimeMillis() + k.pause_secondes * 1000L;
        for (ServerPlayer p : viewers(s, g)) send(p, g, pit.step);
    }

    // ------------------------------------------------------------------ les combattants dans l'arène (sans IA, invulnérables)

    /** Centre de l'arène et axe de la longueur (vers la droite du joueur qui l'a posée). */
    private static Vec3[] layout(ServerLevel l, Spot spot) {
        BlockState st = l.getBlockState(spot.pos());
        if (!(st.getBlock() instanceof ArenaBlock cb)) return new Vec3[]{Vec3.atBottomCenterOf(spot.pos()), new Vec3(1, 0, 0)};
        Direction f = st.getValue(CasinoBlock.FACING);
        BlockPos far = cb.partPos(spot.pos(), f, cb.game.parts() - 1);
        Vec3 center = new Vec3((spot.pos().getX() + far.getX()) / 2.0 + 0.5, spot.pos().getY() + 0.15, (spot.pos().getZ() + far.getZ()) / 2.0 + 0.5);
        BlockPos right = cb.partPos(spot.pos(), f, 1);
        Vec3 axis = new Vec3(right.getX() - spot.pos().getX(), 0, right.getZ() - spot.pos().getZ());
        return new Vec3[]{center, axis};
    }

    private static void spawnMobs(ServerLevel l, Group g, Spot spot) {
        removeMobs(l.getServer(), g);
        if (!l.isLoaded(spot.pos())) return;
        Pit pit = g.pit;
        Cock[] cocks = {pit.fight.a, pit.fight.b};
        for (int i = 0; i < 2; i++) {
            Entrant en = pit.fighting[i];
            Entity e = en == null ? null : EntityType.create(en.snapshot().copy(), l).orElse(null);
            if (e == null) e = EntityType.CHICKEN.create(l);
            if (e == null) continue;
            if (e instanceof Mob c) { c.setNoAi(true); c.setPersistenceRequired(); }
            if (e instanceof TamableAnimal t) { t.setOrderedToSit(false); t.setInSittingPose(false); }
            e.setInvulnerable(true);
            e.addTag(TAG);
            int rgb = i == 0 ? 0xFF5555 : 0x55AAFF;
            e.setCustomName(Component.literal(cocks[i].name()).withStyle(st -> st.withColor(TextColor.fromRgb(rgb))));
            e.setCustomNameVisible(true);
            pit.mobs[i] = e.getUUID();
            l.addFreshEntity(e);
        }
        place(l, g, spot);
    }

    /** Remet les deux combattants face à face (plus écartés dans la grande arène). */
    private static void place(ServerLevel l, Group g, Spot spot) {
        Vec3[] lay = layout(l, spot);
        double gap = g.animals ? 2.8 : 1.6;
        for (int i = 0; i < 2; i++) {
            Entity c = mob(l, g, i);
            if (c == null) continue;
            Vec3 p = lay[0].add(lay[1].scale(i == 0 ? -gap : gap));
            float yaw = (float) Math.toDegrees(Math.atan2(-lay[1].x * (i == 0 ? 1 : -1), lay[1].z * (i == 0 ? 1 : -1)));
            c.moveTo(p.x, p.y, p.z, yaw, 0);
            c.setYHeadRot(yaw);
            c.setYBodyRot(yaw);
        }
    }

    private static Entity mob(ServerLevel l, Group g, int i) { return l == null || g.pit.mobs[i] == null ? null : l.getEntity(g.pit.mobs[i]); }

    private static void removeMobs(MinecraftServer s, Group g) {
        for (int i = 0; i < 2; i++) {
            if (g.pit.mobs[i] == null) continue;
            for (ServerLevel l : s.getAllLevels()) {
                Entity c = l.getEntity(g.pit.mobs[i]);
                if (c != null) c.discard();
            }
            g.pit.mobs[i] = null;
        }
    }

    /** Un combattant qui se recharge (redémarrage, chunk) hors d'un combat en cours est retiré. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || !e.getEntity().getTags().contains(TAG)) return;
        UUID id = e.getEntity().getUUID();
        MinecraftServer s = e.getLevel().getServer();
        if (s != null) for (Group g : Arenas.get(s).groups) if (id.equals(g.pit.mobs[0]) || id.equals(g.pit.mobs[1])) return;
        e.setCanceled(true);
    }

    // ------------------------------------------------------------------ déplacement des arènes

    private static void rotation(MinecraftServer s, Arenas a) {
        if (a.legacyCaisse > 0) {
            MineNorth.treasury().collect(s, a.legacyCaisse, "casino:combats_de_coqs");
            LOG.info("[Casino] Ancienne caisse de l'arène ({} cts) versée au trésor public", a.legacyCaisse);
            a.legacyCaisse = 0;
            a.setDirty();
        }
        long now = System.currentTimeMillis();
        CasinoConfig.Coqs k = CasinoConfig.get().combats_de_coqs;
        for (Group g : new ArrayList<>(a.groups)) {
            // emplacements dont le bloc a disparu (chunk chargé) : oubliés
            Spot was = g.activeSpot();
            boolean changed = g.spots.removeIf(sp -> {
                ServerLevel l = s.getLevel(sp.dim());
                if (l == null) return true;
                if (!l.isLoaded(sp.pos())) return false;
                BlockState st = l.getBlockState(sp.pos());
                return !(st.getBlock() instanceof ArenaBlock) || ArenaBlock.partOf(st) != 0;
            });
            if (changed) {
                if (was != null && !g.spots.contains(was)) cancel(s, g, "L'arène a disparu : mises rendues.");
                g.active = was != null && g.spots.contains(was) ? g.spots.indexOf(was) : g.spots.isEmpty() ? -1 : 0;
                a.setDirty();
            }
            if (g.spots.isEmpty()) { a.groups.remove(g); a.setDirty(); continue; }
            if (g.activeSpot() == null) { g.active = 0; a.setDirty(); }
            Phase ph = g.pit.phase;
            if (g.spots.size() >= 2 && now >= g.nextMove && (ph == Phase.IDLE || ph == Phase.MATCH || ph == Phase.PAUSE) && g.pit.bets.isEmpty()
                    && !playerNear(s, g.activeSpot(), k.rayon_presence_blocs)) {
                int next;
                do next = RNG.nextInt(g.spots.size()); while (next == g.active);
                g.active = next;
                g.nextMove = now + k.deplacement_minutes * 60_000L;
                removeMobs(s, g);
                if (ph == Phase.PAUSE) { g.pit.pickA = g.pit.pickB = -1; buildMatch(g); }
                closeViewers(g);
                a.setDirty();
            } else if (now >= g.nextMove && g.spots.size() < 2) {
                g.nextMove = now + k.deplacement_minutes * 60_000L;
            }
        }
        sync(s, a);
        if (!a.pending.isEmpty()) deliverPending(s, a);
    }

    private static boolean playerNear(MinecraftServer s, Spot spot, int r) {
        for (ServerPlayer p : s.getPlayerList().getPlayers())
            if (p.level().dimension() == spot.dim() && p.blockPosition().distSqr(spot.pos()) <= (double) r * r) return true;
        return false;
    }

    /** Met l'état ouvert / fermé des arènes chargées en accord avec l'emplacement actif de chaque organisateur. */
    private static void sync(MinecraftServer s, Arenas a) {
        for (Group g : a.groups) {
            Spot act = g.activeSpot();
            for (Spot sp : g.spots) {
                ServerLevel l = s.getLevel(sp.dim());
                if (l != null && l.isLoaded(sp.pos()) && l.getBlockState(sp.pos()).getBlock() instanceof ArenaBlock ab) ab.setOpen(l, sp.pos(), sp.equals(act) && g.open());
            }
        }
    }

    private static void deliverPending(MinecraftServer s, Arenas a) {
        for (UUID id : new ArrayList<>(a.pending.keySet())) {
            ServerPlayer p = s.getPlayerList().getPlayer(id);
            if (p == null || !money()) continue;
            long c = a.pending.remove(id);
            a.setDirty();
            payCash(s, id, c);
            p.sendSystemMessage(Component.literal("§6Un inconnu vous glisse §e" + euros(c) + "§6 : votre argent des combats clandestins."));
        }
    }

    // ------------------------------------------------------------------ événements

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) { VIEWERS.remove(e.getEntity().getUUID()); }

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent e) {
        for (Group g : Arenas.get(e.getServer()).groups) if (!g.pit.bets.isEmpty() || g.pit.mobs[0] != null || g.pit.mobs[1] != null)
            cancel(e.getServer(), g, "Serveur arrêté.");
        VIEWERS.clear();
    }

    /** /casino : état des arènes clandestines. */
    static List<String> report(MinecraftServer s) {
        Arenas a = Arenas.get(s);
        List<String> out = new ArrayList<>();
        out.add("§6Arènes clandestines : §f" + a.groups.size() + " organisateur(s) · " + a.fights + " combats, misé " + euros(a.staked)
                + ", saisi par la police " + euros(a.seized));
        for (Group g : a.groups) {
            Spot act = g.activeSpot();
            out.add("§7- " + (g.animals ? "animaux" : "coqs") + " de §f" + (g.ownerName.isEmpty() ? "(OP)" : g.ownerName) + "§7 : " + g.spots.size()
                    + " emplacement(s), " + (act == null ? "nulle part" : "en " + act.pos().toShortString()) + (g.closed ? ", bâchée" : "")
                    + (g.closedUntil > System.currentTimeMillis() ? " §c(fermée après une descente)§7" : "") + ", commissions " + euros(g.earned));
        }
        return out;
    }

    // ------------------------------------------------------------------ envoi de l'état

    private static List<ServerPlayer> viewers(MinecraftServer s, Group g) {
        List<ServerPlayer> out = new ArrayList<>();
        Arenas a = Arenas.get(s);
        for (Map.Entry<UUID, View> e : VIEWERS.entrySet()) {
            if (a.groupAt(e.getValue().dim(), e.getValue().master()) != g) continue;
            ServerPlayer p = s.getPlayerList().getPlayer(e.getKey());
            if (p != null) out.add(p);
        }
        return out;
    }

    private static void broadcast(MinecraftServer s, Group g) { for (ServerPlayer p : viewers(s, g)) send(p, g, -1); }

    private static FighterView view(Group g, int i) {
        Arenas a = null;
        MinecraftServer s = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (s != null) a = Arenas.get(s);
        if (!g.animals) {
            HouseCock h = g.coop.get(i);
            Cock c = h.cock;
            return new FighterView(c.name(), "Coq de combat", c.color(), c.force(), c.agility(), c.stamina(), h.wins, h.losses, "", false);
        }
        Entrant e = g.pit.queue.get(i);
        Cock c = cockAt(g, i);
        Fighter f = a == null ? null : a.fighters.get(e.pet());
        long rest = f == null ? 0 : f.lastFight + CasinoConfig.get().combats_de_coqs.repos_animal_minutes * 60_000L - System.currentTimeMillis();
        return new FighterView(PokerService.cut(c.name(), 60), PokerService.cut(e.species(), 60), c.color(), c.force(), c.agility(), c.stamina(),
                f == null ? 0 : f.wins, f == null ? 0 : f.losses, PokerService.cut(e.ownerName(), 60), rest > 0);
    }

    /** {@code seq} : numéro de l'échange qui vient d'avoir lieu (animation côté client), -1 sinon. */
    private static void send(ServerPlayer p, Group g, int seq) {
        Pit pit = g.pit;
        CasinoConfig.Coqs k = CasinoConfig.get().combats_de_coqs;
        List<FighterView> roster = new ArrayList<>();
        for (int i = 0; i < rosterSize(g) && i < 12; i++) roster.add(view(g, i));
        FighterView fa = FighterView.NONE, fb = FighterView.NONE;
        if (pit.fight != null) {
            fa = pit.pickA >= 0 && pit.pickA < roster.size() && pit.phase != Phase.FIGHT && pit.phase != Phase.PAUSE ? roster.get(pit.pickA) : fv(pit.fight.a, g, 0);
            fb = pit.pickB >= 0 && pit.pickB < roster.size() && pit.phase != Phase.FIGHT && pit.phase != Phase.PAUSE ? roster.get(pit.pickB) : fv(pit.fight.b, g, 1);
        }
        long poolA = 0, poolB = 0, mineA = 0, mineB = 0;
        for (Bet b : pit.bets) {
            if (b.side() == 0) poolA += b.cents(); else poolB += b.cents();
            if (b.id().equals(p.getUUID())) { if (b.side() == 0) mineA += b.cents(); else mineB += b.cents(); }
        }
        Exchange x = pit.step > 0 && pit.step <= pit.script.size() ? pit.script.get(pit.step - 1) : null;
        int hpA = x != null ? x.hpA() : fa.hp(), hpB = x != null ? x.hpB() : fb.hp();
        List<String> lines = new ArrayList<>();
        for (Bet b : pit.bets) if (lines.size() < 8) lines.add((b.side() == 0 ? "R" : "B") + PokerService.cut(b.name(), 60) + "|" + b.cents());
        long left = pit.phase == Phase.BETS || pit.phase == Phase.PAUSE ? Math.max(0, pit.deadline - System.currentTimeMillis()) / 1000 : 0;
        ModNetwork.send(p, new ArenaStatePacket(g.animals, pit.phase.ordinal(), (int) left, PokerService.cut(pit.message, 200), cash(p), isOwner(p, g),
                police(p), PokerService.cut(g.ownerName, 60), g.open(), (float) commission(g), (float) k.commission_max_pourcent,
                k.durees_paris_secondes, isOwner(p, g) ? g.earned : 0, roster, pit.pickA, pit.pickB, fa, fb, hpA, hpB, poolA, poolB,
                pit.fight == null ? 0.5f : (float) pit.fight.pA, x != null && seq >= 0 ? x.attacker() : -1, x != null && seq >= 0 ? x.damage() : -1, seq,
                x != null && x.crit(), mineA, mineB, pit.winner, lines, g.animals ? petView(p, g) : PetView.NONE));
    }

    /** Fiche d'un combattant du combat en cours (le roster a pu changer : retrait d'un inscrit après le combat). */
    private static FighterView fv(Cock c, Group g, int side) {
        Entrant e = g.pit.fighting[side];
        return new FighterView(PokerService.cut(c.name(), 60), e == null ? "Coq de combat" : PokerService.cut(e.species(), 60), c.color(), c.force(),
                c.agility(), c.stamina(), 0, 0, e == null ? "" : PokerService.cut(e.ownerName(), 60), false);
    }

    private static PetView petView(ServerPlayer p, Group g) {
        TamableAnimal pet = nearestPet(p);
        if (pet == null) return PetView.NONE;
        CasinoConfig.Coqs k = CasinoConfig.get().combats_de_coqs;
        Fighter f = Arenas.get(p.server).fighters.get(pet.getUUID());
        int base = Math.max(1, k.animal_qualites_depart);
        long now = System.currentTimeMillis();
        return new PetView(true, PokerService.cut(pet.getDisplayName().getString(), 60), PokerService.cut(species(pet), 60),
                f == null ? base : f.force, f == null ? base : f.agility, f == null ? base : f.stamina, f == null ? 0 : f.wins, f == null ? 0 : f.losses,
                CasinoConfig.cents(k.prix_entrainement_euros), k.animal_qualite_max,
                f == null ? 0 : (int) Math.max(0, (f.lastTrain + k.entrainement_minutes * 60_000L - now) / 1000),
                f == null ? 0 : (int) Math.max(0, (f.lastFight + k.repos_animal_minutes * 60_000L - now) / 1000), queued(g, pet.getUUID()) >= 0);
    }
}
