package fr.minenorth.casino;

import com.mojang.logging.LogUtils;
import fr.minenorth.api.MineNorth;
import fr.minenorth.casino.block.ArenaBlock;
import fr.minenorth.casino.block.CasinoBlock;
import fr.minenorth.casino.block.ModBlocks;
import fr.minenorth.casino.config.CasinoConfig;
import fr.minenorth.casino.game.CockFight;
import fr.minenorth.casino.game.CockFight.Exchange;
import fr.minenorth.casino.network.ModNetwork;
import fr.minenorth.casino.network.ModNetwork.CockStatePacket;
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
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Combats de coqs clandestins. Hors casino : pas d'entreprise, paris en ESPÈCES (billets du mod EuroBank), au pari mutuel :
 * les mises perdantes paient les gagnants au prorata, l'organisateur garde une commission sur les mises perdantes. Aucun argent
 * n'est créé : ce qui est misé est rendu, redistribué, mis dans la caisse de l'arène (récupérée par le gang organisateur) ou saisi
 * par la police (trésor public).
 *
 * Plusieurs arènes peuvent être posées ; une seule est ouverte, et elle change d'emplacement régulièrement (comme les boutiques
 * illégales), jamais pendant un combat ni quand un joueur est tout près. Une descente de police ferme tout un moment.
 *
 * Animaux des joueurs : un maître inscrit son animal apprivoisé (chien, chat, animal Polly Pets…) à la place d'un coq de la maison.
 * Dans l'arène se bat un double de l'animal (même apparence, sans IA, invulnérable) : le vrai animal ne risque rien. Ses qualités
 * (force, agilité, endurance), victoires et défaites sont gardées par animal ; on les fait monter à l'entraînement (espèces, délai).
 * Menu de l'organisateur (gang organisateur ou staff) : arène ouverte / fermée, combats lancés automatiquement ou à la main,
 * commission et bénéficiaire de la commission (caisse du gang, maître du vainqueur, partage, ou un joueur désigné).
 */
@Mod.EventBusSubscriber(modid = MineNorthCasino.MOD_ID)
public final class CockfightService {
    private CockfightService() {}

    private static final Logger LOG = LogUtils.getLogger();
    private static final SecureRandom RNG = new SecureRandom();
    private static final String TAG = "minenorth_coq";

    public enum Phase { IDLE, BETS, FIGHT, PAUSE }

    private record Bet(UUID id, String name, int side, long cents) {}

    private record View(ResourceKey<Level> dim, BlockPos master) {}

    // ------------------------------------------------------------------ état du combat (une seule arène ouverte à la fois)
    private static Phase phase = Phase.IDLE;
    private static CockFight fight;
    private static final List<Bet> BETS = new ArrayList<>();
    private static List<Exchange> script = List.of();
    private static int step;
    private static long deadline;
    private static int winner = -1;
    private static String message = "";
    private static final UUID[] CHICKENS = new UUID[2];
    /** Animal de joueur inscrit de chaque côté (null = coq de la maison). snapshot : données de l'animal pour son double dans l'arène. */
    private record Entrant(UUID pet, UUID owner, String ownerName, String name, String species, CompoundTag snapshot) {}
    private static final Entrant[] ENTRANTS = new Entrant[2];
    private static long nextStepTick, hopBackTick;
    private static int hopper = -1;
    /** Joueurs qui ont l'écran de l'arène ouvert. */
    private static final Map<UUID, View> VIEWERS = new HashMap<>();

    // ------------------------------------------------------------------ données conservées : emplacements, caisse, gains en attente

    public static final class Arenas extends SavedData {
        private static final String NAME = "minenorth_casino_coqs";
        record Spot(ResourceKey<Level> dim, BlockPos pos) {}

        final List<Spot> spots = new ArrayList<>();
        int active = -1;
        long nextMove, closedUntil;
        /** Commissions accumulées (centimes), à récupérer par le gang organisateur. */
        long caisse;
        long fights, staked, seized;
        /** Gains d'un joueur parti avant la fin du combat : remis en espèces à sa prochaine connexion. */
        final Map<UUID, Long> pending = new LinkedHashMap<>();
        // réglages de l'organisateur (menu de l'arène)
        /** Arène fermée par l'organisateur : les caisses restent bâchées même à l'emplacement actif. */
        boolean closedByOrg;
        /** Combats lancés à la main par l'organisateur (sinon : fin du compte à rebours des paris). */
        boolean manual;
        /** Qui touche la commission : 0 caisse du gang (trésor sans gang), 1 maître de l'animal vainqueur, 2 moitié / moitié, 3 joueur désigné. */
        int beneficiary;
        UUID beneficiaryId;
        String beneficiaryName = "";
        /** Commission en % des mises perdantes (-1 = valeur de la config). */
        double commission = -1;
        /** Fiches des animaux de joueurs (qualités, palmarès), par animal. */
        final Map<UUID, Fighter> fighters = new LinkedHashMap<>();

        static Arenas get(MinecraftServer s) { return s.overworld().getDataStorage().computeIfAbsent(Arenas::load, Arenas::new, NAME); }

        Spot activeSpot() { return active >= 0 && active < spots.size() ? spots.get(active) : null; }

        static Arenas load(CompoundTag t) {
            Arenas a = new Arenas();
            ListTag l = t.getList("spots", 10);
            for (int i = 0; i < l.size(); i++) {
                CompoundTag c = l.getCompound(i);
                a.spots.add(new Spot(ResourceKey.create(Registries.DIMENSION, new ResourceLocation(c.getString("dim"))), BlockPos.of(c.getLong("pos"))));
            }
            a.active = t.getInt("active");
            a.nextMove = t.getLong("nextMove");
            a.closedUntil = t.getLong("closedUntil");
            a.caisse = t.getLong("caisse");
            a.fights = t.getLong("fights");
            a.staked = t.getLong("staked");
            a.seized = t.getLong("seized");
            CompoundTag p = t.getCompound("pending");
            for (String k : p.getAllKeys()) {
                try { a.pending.put(UUID.fromString(k), p.getLong(k)); } catch (IllegalArgumentException ignored) {}
            }
            a.closedByOrg = t.getBoolean("closedByOrg");
            a.manual = t.getBoolean("manual");
            a.beneficiary = t.getInt("beneficiary");
            if (t.hasUUID("beneficiaryId")) a.beneficiaryId = t.getUUID("beneficiaryId");
            a.beneficiaryName = t.getString("beneficiaryName");
            a.commission = t.contains("commission") ? t.getDouble("commission") : -1;
            ListTag fl = t.getList("fighters", 10);
            for (int i = 0; i < fl.size(); i++) {
                Fighter f = Fighter.load(fl.getCompound(i));
                if (f != null) a.fighters.put(f.pet, f);
            }
            return a;
        }

        @Override
        public CompoundTag save(CompoundTag t) {
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
            t.putLong("caisse", caisse);
            t.putLong("fights", fights);
            t.putLong("staked", staked);
            t.putLong("seized", seized);
            CompoundTag p = new CompoundTag();
            pending.forEach((k, v) -> p.putLong(k.toString(), v));
            t.put("pending", p);
            t.putBoolean("closedByOrg", closedByOrg);
            t.putBoolean("manual", manual);
            t.putInt("beneficiary", beneficiary);
            if (beneficiaryId != null) t.putUUID("beneficiaryId", beneficiaryId);
            t.putString("beneficiaryName", beneficiaryName);
            t.putDouble("commission", commission);
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

    /** Arène posée : nouvel emplacement possible. */
    public static void register(ServerLevel l, BlockPos master) {
        Arenas a = Arenas.get(l.getServer());
        Arenas.Spot s = new Arenas.Spot(l.dimension(), master.immutable());
        if (!a.spots.contains(s)) a.spots.add(s);
        a.setDirty();
        sync(l.getServer(), a);
    }

    /** Arène cassée : l'emplacement disparaît (si c'était l'arène ouverte, le combat en cours est annulé et les mises rendues). */
    public static void unregister(ServerLevel l, BlockPos master) {
        Arenas a = Arenas.get(l.getServer());
        Arenas.Spot s = new Arenas.Spot(l.dimension(), master);
        int i = a.spots.indexOf(s);
        if (i < 0) return;
        Arenas.Spot was = a.activeSpot();
        a.spots.remove(i);
        if (s.equals(was)) { cancel(l.getServer(), "L'arène a été démontée : mises rendues."); a.active = -1; }
        else if (was != null) a.active = a.spots.indexOf(was);
        a.setDirty();
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

    // ------------------------------------------------------------------ gangs (mod Portes) et police

    /** Gang du joueur « nom|tag|couleur|grade », vide sans gang ou sans le mod Portes. */
    private static String gangOf(MinecraftServer s, UUID id) {
        try {
            return (String) Class.forName("fr.minenorth.portes.api.GangApi").getMethod("gangOf", MinecraftServer.class, UUID.class).invoke(null, s, id);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "";
        }
    }

    /** Membre du gang organisateur, ou staff (OP) : récupère la caisse et règle l'arène depuis son menu. */
    private static boolean organizer(ServerPlayer p) {
        if (p.hasPermissions(2)) return true;
        String g = CasinoConfig.get().combats_de_coqs.gang_organisateur.trim();
        if (g.isEmpty()) return false;
        String row = gangOf(p.server, p.getUUID());
        return !row.isEmpty() && row.split("\\|")[0].equalsIgnoreCase(g);
    }

    /** Nom affiché d'un parieur, avec le tag de son gang s'il en a un. */
    private static String bettorName(ServerPlayer p) {
        String row = gangOf(p.server, p.getUUID());
        String[] f = row.split("\\|");
        return (f.length >= 2 && !f[1].isBlank() ? "[" + f[1] + "] " : "") + MineNorth.displayName(p);
    }

    private static boolean police(ServerPlayer p) {
        if (!MineNorth.police().isPolice(p)) return false;
        return !CasinoConfig.get().combats_de_coqs.police_en_service || MineNorth.police().onDuty(p.server, p.getUUID());
    }

    /** Alerte police (chat, repère de carte, onglet ALERTES) via le mod Police, position approximative (un témoin, pas un GPS). */
    private static void alertPolice(MinecraftServer s, ResourceKey<Level> dim, BlockPos pos) {
        int x = pos.getX() + RNG.nextInt(41) - 20, z = pos.getZ() + RNG.nextInt(41) - 20;
        String label = "Combat de coqs clandestin", desc = "un témoin signale des cris d'animaux et des paris dans le secteur (position approximative).";
        try {
            Class.forName("fr.minenorth.police.RadarHack").getMethod("raise", MinecraftServer.class, String.class, String.class, String.class,
                    int.class, int.class, int.class, long.class).invoke(null, s, label, desc, dim.location().toString(), x, pos.getY(), z, 10L);
            return;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // mod Police absent ou ancien : simple message aux policiers
        }
        for (ServerPlayer p : MineNorth.police().online(s))
            p.sendSystemMessage(Component.literal("§c§l[ALERTE] §r§c" + label + " vers §f" + x + " " + pos.getY() + " " + z + " §7— " + desc));
    }

    // ------------------------------------------------------------------ ouverture

    public static void open(ServerPlayer sp, BlockPos master) {
        Arenas a = Arenas.get(sp.server);
        BlockState st = sp.level().getBlockState(master);
        boolean isActive = new Arenas.Spot(sp.level().dimension(), master).equals(a.activeSpot());
        // l'organisateur ouvre le menu même quand il a bâché l'arène (pour la rouvrir)
        if (isActive && a.closedByOrg && organizer(sp)) {
            if (fight == null) newFight();
            CasinoConfig.Coqs k = CasinoConfig.get().combats_de_coqs;
            VIEWERS.put(sp.getUUID(), new View(sp.level().dimension(), master.immutable()));
            ModNetwork.send(sp, new ModNetwork.OpenPacket(5, "Arène clandestine", CasinoConfig.cents(k.mise_min_euros), CasinoConfig.cents(k.mise_max_euros),
                    CasinoConfig.centsList(k.mises_rapides_euros), List.of(), List.of(), List.of(), List.of(), (float) commission(a), false));
            send(sp, -1);
            return;
        }
        if (!isActive || !st.getValue(ArenaBlock.OPEN) || a.closedByOrg) {
            if (isActive) sync(sp.server, a);
            else sp.displayClientMessage(Component.literal(police(sp) ? "§7Des caisses bâchées… rien de suspect pour l'instant."
                    : "§7Des caisses empilées sous une bâche. Il n'y a personne."), true);
            if (!isActive) return;
        }
        if (fight == null) newFight();
        CasinoConfig.Coqs k = CasinoConfig.get().combats_de_coqs;
        VIEWERS.put(sp.getUUID(), new View(sp.level().dimension(), master.immutable()));
        ModNetwork.send(sp, new ModNetwork.OpenPacket(5, "Arène clandestine", CasinoConfig.cents(k.mise_min_euros), CasinoConfig.cents(k.mise_max_euros),
                CasinoConfig.centsList(k.mises_rapides_euros), List.of(), List.of(), List.of(), List.of(), (float) commission(a), false));
        send(sp, -1);
    }

    /** Commission en vigueur (%) : réglée par l'organisateur, sinon celle de la config. */
    private static double commission(Arenas a) {
        CasinoConfig.Coqs k = CasinoConfig.get().combats_de_coqs;
        return a.commission >= 0 ? Math.min(a.commission, k.commission_max_pourcent) : k.commission_pourcent;
    }

    /** L'écran est-il encore valable : arène ouverte, joueur assez près ? */
    private static View guard(ServerPlayer sp) {
        View v = VIEWERS.get(sp.getUUID());
        Arenas.Spot act = Arenas.get(sp.server).activeSpot();
        if (v == null || act == null || !act.equals(new Arenas.Spot(v.dim(), v.master()))) { reply(sp, false, "L'arène est fermée."); return null; }
        if (Arenas.get(sp.server).closedByOrg && !organizer(sp)) { reply(sp, false, "L'organisateur a fermé l'arène."); return null; }
        int d = CasinoConfig.get().distance_max_blocs + 3;
        if (sp.level().dimension() != v.dim() || sp.blockPosition().distSqr(v.master()) > (double) d * d) { reply(sp, false, "Vous êtes trop loin."); return null; }
        return v;
    }

    private static void reply(ServerPlayer p, boolean ok, String msg) { ModNetwork.send(p, new ModNetwork.MsgPacket(ok, msg)); }

    // ------------------------------------------------------------------ actions

    public static final int BET = 0, COLLECT = 1, RAID = 2, REGISTER = 3, WITHDRAW = 4, TRAIN = 5;
    /** Réglages de l'organisateur (paquet CockSettingPacket) : value / text selon le réglage. */
    public static final int SET_OPEN = 0, SET_MANUAL = 1, SET_BENEFICIARY = 2, SET_COMMISSION = 3, SET_PLAYER = 4, START_NOW = 5;

    public static void action(ServerPlayer sp, int action, int side, long cents) {
        View v = guard(sp);
        if (v == null) return;
        CasinoConfig.Coqs k = CasinoConfig.get().combats_de_coqs;
        Arenas a = Arenas.get(sp.server);
        switch (action) {
            case BET -> {
                if (police(sp)) { reply(sp, false, "Un policier ne parie pas : démantelez plutôt l'arène."); return; }
                if (phase != Phase.IDLE && phase != Phase.BETS) { reply(sp, false, "Les paris sont fermés pour ce combat."); return; }
                if (side != 0 && side != 1) return;
                if (fight == null) newFight();
                long min = CasinoConfig.cents(k.mise_min_euros), max = CasinoConfig.cents(k.mise_max_euros);
                long already = 0;
                for (Bet b : BETS) if (b.id().equals(sp.getUUID())) {
                    if (b.side() != side) { reply(sp, false, "Vous avez déjà parié sur l'autre coq."); return; }
                    already += b.cents();
                }
                if (cents < min || already + cents > max) { reply(sp, false, "Pari entre " + CasinoService.euros(min) + " et " + CasinoService.euros(max) + " par combat."); return; }
                if (!takeCash(sp, cents)) { reply(sp, false, "Pas assez d'espèces sur vous : ici, on ne prend pas la carte."); return; }
                BETS.add(new Bet(sp.getUUID(), bettorName(sp), side, cents));
                a.staked += cents;
                a.setDirty();
                if (phase == Phase.IDLE) {
                    phase = Phase.BETS;
                    deadline = System.currentTimeMillis() + k.duree_paris_secondes * 1000L;
                }
                message = MineNorth.displayName(sp) + " mise " + CasinoService.euros(cents) + " sur " + (side == 0 ? fight.a : fight.b).name() + ".";
                sp.level().playSound(null, v.master(), SoundEvents.CHICKEN_AMBIENT, SoundSource.NEUTRAL, 1f, side == 0 ? 0.9f : 1.2f);
                broadcast(sp.server);
            }
            case COLLECT -> {
                if (!organizer(sp)) { reply(sp, false, "Seul le gang qui tient l'arène récupère la caisse."); return; }
                if (a.caisse <= 0) { reply(sp, false, "La caisse est vide."); return; }
                long c = a.caisse;
                a.caisse = 0;
                a.setDirty();
                payCash(sp.server, sp.getUUID(), c);
                reply(sp, true, "Vous récupérez la caisse : " + euros(c) + " en espèces.");
                send(sp, -1);
            }
            case RAID -> {
                if (!police(sp)) {
                    reply(sp, false, CasinoConfig.get().combats_de_coqs.police_en_service && MineNorth.police().isPolice(sp)
                            ? "Prenez votre service pour intervenir." : "Action réservée à la police.");
                    return;
                }
                raid(sp, v);
            }
            case REGISTER -> register(sp);
            case WITHDRAW -> withdraw(sp);
            case TRAIN -> train(sp, side);
            default -> { }
        }
    }

    // ------------------------------------------------------------------ animaux des joueurs

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

    private static boolean isEntrant(UUID pet) {
        for (Entrant e : ENTRANTS) if (e != null && e.pet().equals(pet)) return true;
        return false;
    }

    private static void register(ServerPlayer sp) {
        CasinoConfig.Coqs k = CasinoConfig.get().combats_de_coqs;
        if (police(sp)) { reply(sp, false, "Un policier en service n'inscrit pas d'animal."); return; }
        if (phase != Phase.IDLE || !BETS.isEmpty()) { reply(sp, false, "Les paris sont lancés : inscrivez votre animal pour le combat suivant."); return; }
        for (Entrant e : ENTRANTS) if (e != null && e.owner().equals(sp.getUUID())) { reply(sp, false, "Vous avez déjà un animal inscrit."); return; }
        TamableAnimal pet = nearestPet(sp);
        if (pet == null) { reply(sp, false, "Aucun de vos animaux apprivoisés à moins de " + k.distance_animal_blocs + " blocs."); return; }
        Arenas a = Arenas.get(sp.server);
        Fighter f = fighter(a, pet, sp);
        long rest = f.lastFight + k.repos_animal_minutes * 60_000L - System.currentTimeMillis();
        if (rest > 0) { reply(sp, false, f.name + " se repose encore " + Math.max(1, rest / 60_000) + " min."); return; }
        int side = ENTRANTS[0] == null ? 0 : ENTRANTS[1] == null ? 1 : -1;
        if (side < 0) { reply(sp, false, "Les deux places sont prises."); return; }
        ENTRANTS[side] = new Entrant(pet.getUUID(), sp.getUUID(), MineNorth.displayName(sp), f.name, f.species, snapshot(pet));
        newFight();
        message = MineNorth.displayName(sp) + " inscrit " + f.name + " (" + f.species + ") côté " + (side == 0 ? "rouge" : "bleu") + " !";
        sp.serverLevel().sendParticles(ParticleTypes.ANGRY_VILLAGER, pet.getX(), pet.getY() + pet.getBbHeight(), pet.getZ(), 3, 0.2, 0.1, 0.2, 0);
        broadcast(sp.server);
    }

    private static void withdraw(ServerPlayer sp) {
        if (phase != Phase.IDLE || !BETS.isEmpty()) { reply(sp, false, "Trop tard : des paris sont engagés sur ce combat."); return; }
        for (int i = 0; i < 2; i++) {
            if (ENTRANTS[i] == null || !ENTRANTS[i].owner().equals(sp.getUUID())) continue;
            message = ENTRANTS[i].name() + " est retiré du combat.";
            ENTRANTS[i] = null;
            newFight();
            broadcast(sp.server);
            return;
        }
        reply(sp, false, "Vous n'avez pas d'animal inscrit.");
    }

    /** Entraînement : +1 dans une qualité (0 force, 1 agilité, 2 endurance), payé en espèces, une séance par délai. */
    private static void train(ServerPlayer sp, int stat) {
        CasinoConfig.Coqs k = CasinoConfig.get().combats_de_coqs;
        if (stat < 0 || stat > 2) return;
        TamableAnimal pet = nearestPet(sp);
        if (pet == null) { reply(sp, false, "Amenez votre animal (moins de " + k.distance_animal_blocs + " blocs) pour l'entraîner."); return; }
        if (isEntrant(pet.getUUID())) { reply(sp, false, "Il est inscrit au combat : retirez-le d'abord."); return; }
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
        send(sp, -1);
    }

    // ------------------------------------------------------------------ menu de l'organisateur

    public static void setting(ServerPlayer sp, int kind, int value, String text) {
        View v = VIEWERS.get(sp.getUUID());
        if (v == null) return;
        if (!organizer(sp)) { reply(sp, false, "Réservé à l'organisateur de l'arène."); return; }
        Arenas a = Arenas.get(sp.server);
        CasinoConfig.Coqs k = CasinoConfig.get().combats_de_coqs;
        switch (kind) {
            case SET_OPEN -> {
                if (!a.closedByOrg && (phase == Phase.BETS || phase == Phase.FIGHT || !BETS.isEmpty())) {
                    reply(sp, false, "Un combat est en cours : fermez après la fin.");
                    return;
                }
                a.closedByOrg = !a.closedByOrg;
                message = a.closedByOrg ? "L'organisateur bâche l'arène : plus de combat pour l'instant." : "L'arène rouvre : faites vos jeux !";
                if (a.closedByOrg) { ENTRANTS[0] = ENTRANTS[1] = null; removeChickens(sp.server); phase = Phase.IDLE; fight = null; }
            }
            case SET_MANUAL -> {
                a.manual = !a.manual;
                message = a.manual ? "Les combats partent au signal de l'organisateur." : "Les combats partent à la fin du compte à rebours.";
            }
            case SET_BENEFICIARY -> {
                if (value < 0 || value > 3) return;
                if (value == 3 && a.beneficiaryId == null) { reply(sp, false, "Désignez d'abord un joueur (champ « Joueur »)."); return; }
                a.beneficiary = value;
            }
            case SET_COMMISSION -> {
                double pct = Math.max(0, Math.min(k.commission_max_pourcent, value / 10.0));
                if (phase == Phase.BETS || phase == Phase.FIGHT) { reply(sp, false, "Changez la commission entre deux combats."); return; }
                a.commission = pct;
            }
            case SET_PLAYER -> {
                ServerPlayer t = sp.server.getPlayerList().getPlayerByName(text == null ? "" : text.trim());
                if (t == null) { reply(sp, false, "Joueur introuvable (pseudo exact, connecté)."); return; }
                a.beneficiaryId = t.getUUID();
                a.beneficiaryName = MineNorth.displayName(t);
                a.beneficiary = 3;
            }
            case START_NOW -> {
                if (a.closedByOrg) { reply(sp, false, "Rouvrez d'abord l'arène."); return; }
                if (phase != Phase.IDLE && phase != Phase.BETS) { reply(sp, false, "Un combat est déjà en cours."); return; }
                Arenas.Spot spot = a.activeSpot();
                if (spot == null) return;
                if (fight == null) newFight();
                startFight(sp.server, sp.server.getLevel(spot.dim()), spot);
                return;
            }
            default -> { return; }
        }
        a.setDirty();
        sync(sp.server, a);
        broadcast(sp.server);
    }

    private static String euros(long cents) {
        return (cents / 100) + (cents % 100 == 0 ? "" : "," + String.format("%02d", cents % 100)) + " €";
    }

    /** Descente de police : mises et caisse saisies (trésor public), combat arrêté, arène fermée un moment, liste des parieurs. */
    private static void raid(ServerPlayer officer, View v) {
        MinecraftServer s = officer.server;
        Arenas a = Arenas.get(s);
        CasinoConfig.Coqs k = CasinoConfig.get().combats_de_coqs;
        long bets = 0;
        List<String> names = new ArrayList<>();
        for (Bet b : BETS) { bets += b.cents(); if (!names.contains(b.name())) names.add(b.name()); }
        long seized = bets + a.caisse;
        if (seized > 0) MineNorth.treasury().collect(s, seized, "police:saisie_combats_de_coqs");
        a.seized += seized;
        a.caisse = 0;
        BETS.clear();
        Component alert = Component.literal("§c§l🚨 DESCENTE DE POLICE ! §r§cL'arène est démantelée, les paris sont saisis.");
        for (ServerPlayer p : s.getPlayerList().getPlayers())
            if (p.level().dimension() == v.dim() && p.blockPosition().distSqr(v.master()) < 40 * 40) p.sendSystemMessage(alert);
        officer.sendSystemMessage(Component.literal("§9[Police] §fSaisie : §e" + euros(seized) + " §f(mises " + euros(bets) + ", caisse "
                + euros(seized - bets) + "). " + (names.isEmpty() ? "Aucun parieur en cours." : "Parieurs : §e" + String.join("§f, §e", names))));
        ServerLevel l = s.getLevel(v.dim());
        if (l != null) {
            l.playSound(null, v.master(), SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 1.5f, 0.8f);
            for (UUID id : CHICKENS) {
                Entity c = id == null ? null : l.getEntity(id);
                if (c != null) l.sendParticles(ParticleTypes.POOF, c.getX(), c.getY() + 0.3, c.getZ(), 8, 0.2, 0.2, 0.2, 0.02);
            }
        }
        removeChickens(s);
        ENTRANTS[0] = ENTRANTS[1] = null;
        phase = Phase.IDLE;
        fight = null;
        message = "Descente de police : arène démantelée.";
        broadcast(s);
        VIEWERS.clear();
        a.active = -1;
        a.closedUntil = System.currentTimeMillis() + k.fermeture_apres_descente_minutes * 60_000L;
        a.setDirty();
        sync(s, a);
        LOG.info("[Casino] Descente de police sur l'arène {} par {} : {} cts saisis", v.master(), officer.getGameProfile().getName(), seized);
    }

    /** Combat annulé (arène démontée, serveur arrêté) : chacun récupère sa mise. */
    private static void cancel(MinecraftServer s, String why) {
        for (Bet b : BETS) payCash(s, b.id(), b.cents());
        BETS.clear();
        removeChickens(s);
        ENTRANTS[0] = ENTRANTS[1] = null;
        phase = Phase.IDLE;
        fight = null;
        message = why;
        broadcast(s);
    }

    /** Combattant d'un animal inscrit (couleur tirée de son espèce), d'après sa fiche. */
    private static CockFight.Cock cockOf(Entrant e) {
        if (e == null) return null;
        MinecraftServer s = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        Fighter f = s == null ? null : Arenas.get(s).fighters.get(e.pet());
        int base = Math.max(1, CasinoConfig.get().combats_de_coqs.animal_qualites_depart);
        int color = 0x404040 | (e.species().hashCode() & 0xBFBFBF);
        return new CockFight.Cock(e.name(), color, f == null ? base : f.force, f == null ? base : f.agility, f == null ? base : f.stamina);
    }

    private static void newFight() {
        fight = new CockFight(RNG, cockOf(ENTRANTS[0]), cockOf(ENTRANTS[1]));
        script = List.of();
        step = 0;
        winner = -1;
        message = "Prochain combat : " + fight.a.name() + " contre " + fight.b.name() + ". Faites vos jeux !";
    }

    // ------------------------------------------------------------------ horloge

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        MinecraftServer s = e.getServer();
        long tickNo = s.getTickCount();
        if (tickNo % 20 == 0) rotation(s);
        if (phase == Phase.IDLE) return;
        long now = System.currentTimeMillis();
        Arenas.Spot spot = Arenas.get(s).activeSpot();
        if (spot == null) { cancel(s, "L'arène est fermée : mises rendues."); return; }
        ServerLevel l = s.getLevel(spot.dim());
        switch (phase) {
            case BETS -> {
                if (now < deadline || Arenas.get(s).manual) { if (tickNo % 20 == 0) broadcast(s); return; }
                startFight(s, l, spot);
            }
            case FIGHT -> {
                if (l == null) return;
                if (hopper >= 0 && tickNo >= hopBackTick) { place(l, spot); hopper = -1; }
                if (tickNo < nextStepTick) return;
                if (step < script.size()) {
                    playExchange(s, l, spot, script.get(step));
                    step++;
                    nextStepTick = tickNo + 14;
                    return;
                }
                settle(s, l, spot);
            }
            case PAUSE -> {
                if (now < deadline) return;
                removeChickens(s);
                phase = Phase.IDLE;
                newFight();
                broadcast(s);
            }
            default -> { }
        }
    }

    private static void startFight(MinecraftServer s, ServerLevel l, Arenas.Spot spot) {
        phase = Phase.FIGHT;
        script = fight.simulate(RNG);
        step = 0;
        nextStepTick = s.getTickCount() + 40;
        message = "Les coqs entrent dans l'arène… Les paris sont fermés !";
        if (l != null) {
            spawnChickens(l, spot);
            l.playSound(null, spot.pos(), SoundEvents.CHICKEN_AMBIENT, SoundSource.NEUTRAL, 1.5f, 0.6f);
        }
        if (RNG.nextDouble() < CasinoConfig.get().combats_de_coqs.chance_alerte_police) alertPolice(s, spot.dim(), spot.pos());
        Arenas a = Arenas.get(s);
        a.fights++;
        a.setDirty();
        broadcast(s);
    }

    private static boolean isChicken(Entity e) { return e instanceof Chicken; }

    private static void playExchange(MinecraftServer s, ServerLevel l, Arenas.Spot spot, Exchange x) {
        Entity att = chicken(l, x.attacker()), def = chicken(l, 1 - x.attacker());
        if (att != null && def != null) {
            // l'attaquant bondit sur l'autre, revient à sa place 7 ticks plus tard
            Vec3 to = def.position().subtract(att.position()).scale(0.6).add(att.position());
            att.teleportTo(to.x, att.getY() + 0.35, to.z);
            att.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, def.getEyePosition());
            hopper = x.attacker();
            hopBackTick = s.getTickCount() + 7;
            if (x.damage() > 0) {
                l.broadcastDamageEvent(def, l.damageSources().generic());
                if (isChicken(def)) l.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.FEATHER)), def.getX(), def.getY() + 0.4, def.getZ(),
                        x.crit() ? 14 : 6, 0.15, 0.15, 0.15, 0.08);
                else l.sendParticles(ParticleTypes.DAMAGE_INDICATOR, def.getX(), def.getY() + def.getBbHeight() * 0.6, def.getZ(), x.crit() ? 8 : 3, 0.2, 0.2, 0.2, 0.1);
                if (x.crit()) l.sendParticles(ParticleTypes.CRIT, def.getX(), def.getY() + 0.5, def.getZ(), 10, 0.2, 0.2, 0.2, 0.2);
            } else {
                l.sendParticles(ParticleTypes.CLOUD, def.getX(), def.getY() + 0.1, def.getZ(), 3, 0.1, 0.05, 0.1, 0.01);
            }
        }
        boolean chick = def == null || isChicken(def);
        l.playSound(null, spot.pos(), x.damage() > 0 ? (chick ? SoundEvents.CHICKEN_HURT : SoundEvents.PLAYER_ATTACK_STRONG) : SoundEvents.PLAYER_ATTACK_WEAK, SoundSource.NEUTRAL, 1f,
                0.8f + RNG.nextFloat() * 0.5f);
        CockFight.Cock a = x.attacker() == 0 ? fight.a : fight.b, d = x.attacker() == 0 ? fight.b : fight.a;
        message = x.damage() == 0 ? d.name() + " esquive l'attaque de " + a.name() + " !"
                : (x.crit() ? "COUP CRITIQUE ! " : "") + a.name() + " frappe " + d.name() + " (-" + x.damage() + ").";
        int seq = step;
        for (ServerPlayer p : viewers(s)) send(p, seq);
    }

    /** Fin du combat : le perdant s'effondre, les gagnants se partagent les mises perdantes (moins la commission). */
    private static void settle(MinecraftServer s, ServerLevel l, Arenas.Spot spot) {
        CasinoConfig.Coqs k = CasinoConfig.get().combats_de_coqs;
        Arenas a = Arenas.get(s);
        winner = CockFight.winner(script);
        CockFight.Cock w = winner == 0 ? fight.a : fight.b;
        Entity loser = chicken(l, 1 - winner);
        if (loser != null) l.broadcastEntityEvent(loser, (byte) 3);   // animation de mort côté client
        l.playSound(null, spot.pos(), SoundEvents.CHICKEN_DEATH, SoundSource.NEUTRAL, 1.2f, 0.8f);
        l.playSound(null, spot.pos(), SoundEvents.PLAYER_LEVELUP, SoundSource.NEUTRAL, 0.6f, 1.4f);
        long poolW = 0, poolL = 0;
        for (Bet b : BETS) if (b.side() == winner) poolW += b.cents(); else poolL += b.cents();
        long commission = 0;
        if (poolW == 0) {
            for (Bet b : BETS) payCash(s, b.id(), b.cents());
            message = w.name() + " gagne ! Personne n'avait misé sur lui : mises rendues.";
        } else {
            commission = Math.round(poolL * commission(a) / 100.0);
            long share = poolL - commission, paidOut = 0;
            Map<UUID, Long> gains = new LinkedHashMap<>();
            for (Bet b : BETS) {
                if (b.side() != winner) continue;
                long g = b.cents() + share * b.cents() / poolW;   // arrondi au centime inférieur : le reste va à la commission
                gains.merge(b.id(), g, Long::sum);
                paidOut += g;
            }
            commission = poolW + poolL - paidOut;
            gains.forEach((id, g) -> {
                payCash(s, id, g);
                ServerPlayer p = s.getPlayerList().getPlayer(id);
                if (p != null) p.displayClientMessage(Component.literal("§a" + w.name() + " gagne : vous touchez §e" + euros(g) + "§a en espèces."), true);
            });
            message = w.name() + " gagne ! " + gains.size() + " gagnant(s) se partagent " + euros(poolW + poolL - commission) + ".";
        }
        if (commission > 0) payCommission(s, a, commission, ENTRANTS[winner]);
        long nowMs = System.currentTimeMillis();
        for (int i = 0; i < 2; i++) {
            Entrant e = ENTRANTS[i];
            Fighter f = e == null ? null : a.fighters.get(e.pet());
            if (f == null) continue;
            if (i == winner) f.wins++; else f.losses++;
            f.lastFight = nowMs;
            ServerPlayer o = s.getPlayerList().getPlayer(e.owner());
            if (o != null) o.sendSystemMessage(Component.literal(i == winner ? "§6🏆 " + f.name + " remporte le combat ! (" + f.wins + " victoire(s))"
                    : "§7" + f.name + " a perdu ce combat. Il se repose avant le prochain."));
        }
        ENTRANTS[0] = ENTRANTS[1] = null;   // une inscription vaut pour un combat
        a.setDirty();
        BETS.clear();
        phase = Phase.PAUSE;
        deadline = System.currentTimeMillis() + k.pause_secondes * 1000L;
        for (ServerPlayer p : viewers(s)) send(p, step);
    }

    /** Commission : à qui l'organisateur l'a attribuée (caisse du gang, maître du vainqueur, moitié / moitié, joueur désigné). */
    private static void payCommission(MinecraftServer s, Arenas a, long commission, Entrant win) {
        long toOwner = switch (a.beneficiary) {
            case 1 -> win == null ? 0 : commission;
            case 2 -> win == null ? 0 : commission / 2;
            default -> 0;
        };
        long toPlayer = a.beneficiary == 3 && a.beneficiaryId != null ? commission : 0;
        long rest = commission - toOwner - toPlayer;
        if (toOwner > 0) {
            payCash(s, win.owner(), toOwner);
            ServerPlayer o = s.getPlayerList().getPlayer(win.owner());
            if (o != null) o.sendSystemMessage(Component.literal("§6Prime du maître : §e" + euros(toOwner) + "§6 en espèces."));
        }
        if (toPlayer > 0) {
            payCash(s, a.beneficiaryId, toPlayer);
            ServerPlayer o = s.getPlayerList().getPlayer(a.beneficiaryId);
            if (o != null) o.sendSystemMessage(Component.literal("§6Votre part des paris de l'arène : §e" + euros(toPlayer) + "§6."));
        }
        if (rest > 0) {
            if (CasinoConfig.get().combats_de_coqs.gang_organisateur.isBlank()) MineNorth.treasury().collect(s, rest, "casino:combats_de_coqs");
            else a.caisse += rest;
        }
        a.setDirty();
    }

    // ------------------------------------------------------------------ les combattants dans l'arène (sans IA, invulnérables)

    /** Centre de l'arène et axe de la longueur (vers la droite du joueur qui l'a posée). */
    private static Vec3[] layout(ServerLevel l, Arenas.Spot spot) {
        BlockState st = l.getBlockState(spot.pos());
        Direction f = st.hasProperty(CasinoBlock.FACING) ? st.getValue(CasinoBlock.FACING) : Direction.NORTH;
        CasinoBlock cb = (CasinoBlock) ModBlocks.ARENA.get();
        BlockPos far = cb.partPos(spot.pos(), f, cb.game.parts() - 1);
        Vec3 center = new Vec3((spot.pos().getX() + far.getX()) / 2.0 + 0.5, spot.pos().getY() + 0.15, (spot.pos().getZ() + far.getZ()) / 2.0 + 0.5);
        BlockPos right = cb.partPos(spot.pos(), f, 1);
        Vec3 axis = new Vec3(right.getX() - spot.pos().getX(), 0, right.getZ() - spot.pos().getZ());
        return new Vec3[]{center, axis};
    }

    private static void spawnChickens(ServerLevel l, Arenas.Spot spot) {
        removeChickens(l.getServer());
        if (!l.isLoaded(spot.pos())) return;
        CockFight.Cock[] cocks = {fight.a, fight.b};
        for (int i = 0; i < 2; i++) {
            Entity e = ENTRANTS[i] == null ? null : EntityType.create(ENTRANTS[i].snapshot().copy(), l).orElse(null);
            if (e == null) e = EntityType.CHICKEN.create(l);
            if (e == null) continue;
            if (e instanceof Mob c) { c.setNoAi(true); c.setPersistenceRequired(); }
            if (e instanceof TamableAnimal t) { t.setOrderedToSit(false); t.setInSittingPose(false); }
            Entity c = e;
            c.setInvulnerable(true);
            c.addTag(TAG);
            int rgb = i == 0 ? 0xFF5555 : 0x55AAFF;
            c.setCustomName(Component.literal(cocks[i].name()).withStyle(st -> st.withColor(TextColor.fromRgb(rgb))));
            c.setCustomNameVisible(true);
            CHICKENS[i] = c.getUUID();
            l.addFreshEntity(c);
        }
        place(l, spot);
    }

    /** Remet les deux coqs face à face. */
    private static void place(ServerLevel l, Arenas.Spot spot) {
        Vec3[] lay = layout(l, spot);
        for (int i = 0; i < 2; i++) {
            Entity c = chicken(l, i);
            if (c == null) continue;
            Vec3 p = lay[0].add(lay[1].scale(i == 0 ? -1.6 : 1.6));
            float yaw = (float) Math.toDegrees(Math.atan2(-lay[1].x * (i == 0 ? 1 : -1), lay[1].z * (i == 0 ? 1 : -1)));
            c.moveTo(p.x, p.y, p.z, yaw, 0);
            c.setYHeadRot(yaw);
            c.setYBodyRot(yaw);
        }
    }

    private static Entity chicken(ServerLevel l, int i) { return l == null || CHICKENS[i] == null ? null : l.getEntity(CHICKENS[i]); }

    private static void removeChickens(MinecraftServer s) {
        for (int i = 0; i < 2; i++) {
            if (CHICKENS[i] == null) continue;
            for (ServerLevel l : s.getAllLevels()) {
                Entity c = l.getEntity(CHICKENS[i]);
                if (c != null) c.discard();
            }
            CHICKENS[i] = null;
        }
    }

    /** Un coq de combat qui se recharge (redémarrage, chunk) hors d'un combat en cours est retiré. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || !e.getEntity().getTags().contains(TAG)) return;
        UUID id = e.getEntity().getUUID();
        if (!id.equals(CHICKENS[0]) && !id.equals(CHICKENS[1])) e.setCanceled(true);
    }

    // ------------------------------------------------------------------ déplacement de l'arène

    private static void rotation(MinecraftServer s) {
        Arenas a = Arenas.get(s);
        // emplacements dont le bloc a disparu (chunk chargé) : oubliés
        boolean changed = a.spots.removeIf(sp -> {
            ServerLevel l = s.getLevel(sp.dim());
            if (l == null) return true;
            if (!l.isLoaded(sp.pos())) return false;
            BlockState st = l.getBlockState(sp.pos());
            return !(st.getBlock() instanceof ArenaBlock) || st.getValue(ArenaBlock.PART_A) != 0;
        });
        if (changed) { a.active = Math.min(a.active, a.spots.size() - 1); a.setDirty(); }
        if (a.spots.isEmpty()) return;
        long now = System.currentTimeMillis();
        CasinoConfig.Coqs k = CasinoConfig.get().combats_de_coqs;
        if (now < a.closedUntil) {
            if (a.active != -1) { a.active = -1; a.setDirty(); }
        } else if (a.activeSpot() == null) {
            a.active = RNG.nextInt(a.spots.size());
            a.nextMove = now + k.deplacement_minutes * 60_000L;
            a.setDirty();
        } else if (a.spots.size() >= 2 && now >= a.nextMove && (phase == Phase.IDLE || phase == Phase.PAUSE) && BETS.isEmpty() && ENTRANTS[0] == null && ENTRANTS[1] == null
                && !playerNear(s, a.activeSpot(), k.rayon_presence_blocs)) {
            int next;
            do next = RNG.nextInt(a.spots.size()); while (next == a.active);
            a.active = next;
            a.nextMove = now + k.deplacement_minutes * 60_000L;
            a.setDirty();
            removeChickens(s);
            ENTRANTS[0] = ENTRANTS[1] = null;
            phase = Phase.IDLE;
            fight = null;
            VIEWERS.clear();
        }
        sync(s, a);
        if (!a.pending.isEmpty()) deliverPending(s, a);
    }

    private static boolean playerNear(MinecraftServer s, Arenas.Spot spot, int r) {
        for (ServerPlayer p : s.getPlayerList().getPlayers())
            if (p.level().dimension() == spot.dim() && p.blockPosition().distSqr(spot.pos()) <= (double) r * r) return true;
        return false;
    }

    /** Met l'état ouvert / fermé des arènes chargées en accord avec l'emplacement actif. */
    private static void sync(MinecraftServer s, Arenas a) {
        ArenaBlock block = (ArenaBlock) ModBlocks.ARENA.get();
        Arenas.Spot act = a.activeSpot();
        for (Arenas.Spot sp : a.spots) {
            ServerLevel l = s.getLevel(sp.dim());
            if (l != null) block.setOpen(l, sp.pos(), sp.equals(act) && !a.closedByOrg);
        }
    }

    private static void deliverPending(MinecraftServer s, Arenas a) {
        for (UUID id : new ArrayList<>(a.pending.keySet())) {
            ServerPlayer p = s.getPlayerList().getPlayer(id);
            if (p == null || !money()) continue;
            long c = a.pending.remove(id);
            a.setDirty();
            payCash(s, id, c);
            p.sendSystemMessage(Component.literal("§6Un inconnu vous glisse §e" + euros(c) + "§6 : vos gains des combats de coqs."));
        }
    }

    // ------------------------------------------------------------------ événements

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) { VIEWERS.remove(e.getEntity().getUUID()); }

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent e) {
        cancel(e.getServer(), "Serveur arrêté.");
        VIEWERS.clear();
    }

    /** /casino : état des combats de coqs. */
    static List<String> report(MinecraftServer s) {
        Arenas a = Arenas.get(s);
        Arenas.Spot act = a.activeSpot();
        List<String> out = new ArrayList<>();
        out.add("§6Combats de coqs : §f" + a.spots.size() + " emplacement(s), " + (act == null ? "aucune arène ouverte"
                : "ouverte en " + act.pos().toShortString()) + (a.closedUntil > System.currentTimeMillis() ? " §c(fermée après une descente)" : ""));
        out.add("§7" + a.fights + " combats, misé " + euros(a.staked) + ", saisi par la police " + euros(a.seized) + ", caisse " + euros(a.caisse));
        return out;
    }

    // ------------------------------------------------------------------ envoi de l'état

    private static List<ServerPlayer> viewers(MinecraftServer s) {
        List<ServerPlayer> out = new ArrayList<>();
        for (UUID id : VIEWERS.keySet()) {
            ServerPlayer p = s.getPlayerList().getPlayer(id);
            if (p != null) out.add(p);
        }
        return out;
    }

    private static void broadcast(MinecraftServer s) { for (ServerPlayer p : viewers(s)) send(p, -1); }

    /** {@code seq} : numéro de l'échange qui vient d'avoir lieu (animation côté client), -1 sinon. */
    private static void send(ServerPlayer p, int seq) {
        sendExtra(p);
        sendState(p, seq);
    }

    /** Inscrits, animal du joueur et son entraînement, réglages de l'organisateur. */
    private static void sendExtra(ServerPlayer p) {
        Arenas a = Arenas.get(p.server);
        CasinoConfig.Coqs k = CasinoConfig.get().combats_de_coqs;
        TamableAnimal pet = nearestPet(p);
        Fighter f = pet == null ? null : a.fighters.get(pet.getUUID());
        int base = Math.max(1, k.animal_qualites_depart);
        String petName = pet == null ? "" : PokerService.cut(pet.getDisplayName().getString(), 40);
        String petSpecies = pet == null ? "" : PokerService.cut(species(pet), 40);
        int[] stats = f == null ? new int[]{base, base, base} : new int[]{f.force, f.agility, f.stamina};
        long now = System.currentTimeMillis();
        int trainWait = f == null ? 0 : (int) Math.max(0, (f.lastTrain + k.entrainement_minutes * 60_000L - now) / 1000);
        int restWait = f == null ? 0 : (int) Math.max(0, (f.lastFight + k.repos_animal_minutes * 60_000L - now) / 1000);
        int mySide = -1;
        for (int i = 0; i < 2; i++) if (ENTRANTS[i] != null && ENTRANTS[i].owner().equals(p.getUUID())) mySide = i;
        String benef = switch (a.beneficiary) {
            case 1 -> "Maître du vainqueur";
            case 2 -> "Moitié caisse / moitié maître";
            case 3 -> a.beneficiaryName.isEmpty() ? "Joueur désigné" : a.beneficiaryName;
            default -> k.gang_organisateur.isBlank() ? "Trésor public" : "Caisse du gang";
        };
        ModNetwork.send(p, new ModNetwork.CockExtraPacket(
                ENTRANTS[0] == null ? "" : ENTRANTS[0].ownerName() + " · " + ENTRANTS[0].species(),
                ENTRANTS[1] == null ? "" : ENTRANTS[1].ownerName() + " · " + ENTRANTS[1].species(),
                petName, petSpecies, stats[0], stats[1], stats[2], f == null ? 0 : f.wins, f == null ? 0 : f.losses,
                CasinoConfig.cents(k.prix_entrainement_euros), k.animal_qualite_max, trainWait, restWait, mySide,
                organizer(p), !a.closedByOrg, a.manual, a.beneficiary, benef, (float) commission(a), (float) k.commission_max_pourcent));
    }

    private static void sendState(ServerPlayer p, int seq) {
        if (fight == null) {
            ModNetwork.send(p, new CockStatePacket(Phase.IDLE.ordinal(), 0, "", "", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.5f,
                    -1, -1, 0, false, 0, 0, -1, PokerService.cut(message, 200), List.of(), cash(p), police(p), false, 0));
            return;
        }
        long poolA = 0, poolB = 0, mineA = 0, mineB = 0;
        for (Bet b : BETS) {
            if (b.side() == 0) poolA += b.cents(); else poolB += b.cents();
            if (b.id().equals(p.getUUID())) { if (b.side() == 0) mineA += b.cents(); else mineB += b.cents(); }
        }
        Exchange x = step > 0 && step <= script.size() ? script.get(step - 1) : null;
        int hpA = x != null ? x.hpA() : fight.a.hp(), hpB = x != null ? x.hpB() : fight.b.hp();
        List<String> lines = new ArrayList<>();
        for (Bet b : BETS) if (lines.size() < 8) lines.add((b.side() == 0 ? "R" : "B") + PokerService.cut(b.name(), 60) + "|" + b.cents());
        long left = phase == Phase.BETS || phase == Phase.PAUSE ? Math.max(0, deadline - System.currentTimeMillis()) / 1000 : 0;
        boolean org = organizer(p);
        ModNetwork.send(p, new CockStatePacket(phase.ordinal(), (int) left, fight.a.name(), fight.b.name(), fight.a.color(), fight.b.color(),
                fight.a.force(), fight.a.agility(), fight.a.stamina(), fight.b.force(), fight.b.agility(), fight.b.stamina(),
                fight.a.hp(), fight.b.hp(), hpA, hpB, poolA, poolB, (float) fight.pA, x != null && seq >= 0 ? x.attacker() : -1,
                x != null && seq >= 0 ? x.damage() : -1, seq, x != null && x.crit(), mineA, mineB, winner, PokerService.cut(message, 200), lines, cash(p),
                police(p), org, org ? Arenas.get(p.server).caisse : 0));
    }
}
