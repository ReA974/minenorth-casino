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

    private static CasinoStats load(CompoundTag tag) {
        CasinoStats st = new CasinoStats();
        for (String k : tag.getAllKeys()) {
            long[] a = tag.getLongArray(k);
            if (a.length == 3) st.games.put(k, a);
        }
        return st;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        for (Map.Entry<String, long[]> e : games.entrySet()) tag.putLongArray(e.getKey(), e.getValue());
        return tag;
    }
}
