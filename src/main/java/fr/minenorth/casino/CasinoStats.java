package fr.minenorth.casino;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;

/** Statistiques par jeu, conservées avec le monde : nombre de parties, total misé, total payé. */
public final class CasinoStats extends SavedData {
    private static final String NAME = "minenorth_casino";
    private final Map<String, long[]> games = new LinkedHashMap<>();   // {parties, misé, payé} en centimes

    public static CasinoStats get(MinecraftServer s) {
        return s.overworld().getDataStorage().computeIfAbsent(CasinoStats::load, CasinoStats::new, NAME);
    }

    public void add(String game, long bet, long paid) {
        long[] g = games.computeIfAbsent(game, k -> new long[3]);
        g[0]++; g[1] += bet; g[2] += paid;
        setDirty();
    }

    public Map<String, long[]> all() { return games; }

    // ------------------------------------------------------------------ derniers numéros de la roulette (partagés par toutes les tables)
    private static final String DRAWS = "_roulette_tirages";
    public static final int MAX_DRAWS = 100;
    private final java.util.ArrayDeque<Integer> draws = new java.util.ArrayDeque<>();   // le plus récent en tête

    public void addDraw(int number) {
        draws.addFirst(number);
        while (draws.size() > MAX_DRAWS) draws.removeLast();
        setDirty();
    }

    /** Derniers numéros sortis, le plus récent en premier. */
    public java.util.List<Integer> recentDraws() { return new java.util.ArrayList<>(draws); }

    private static CasinoStats load(CompoundTag tag) {
        CasinoStats st = new CasinoStats();
        for (String k : tag.getAllKeys()) {
            if (k.equals(DRAWS)) continue;
            long[] a = tag.getLongArray(k);
            if (a.length == 3) st.games.put(k, a);
        }
        for (int n : tag.getIntArray(DRAWS)) if (n >= 0 && n <= 36 && st.draws.size() < MAX_DRAWS) st.draws.addLast(n);
        return st;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        for (Map.Entry<String, long[]> e : games.entrySet()) tag.putLongArray(e.getKey(), e.getValue());
        tag.putIntArray(DRAWS, draws.stream().mapToInt(Integer::intValue).toArray());
        return tag;
    }
}
