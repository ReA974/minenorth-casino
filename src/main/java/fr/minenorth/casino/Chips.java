package fr.minenorth.casino;

import fr.minenorth.casino.block.ChipItem;
import fr.minenorth.casino.block.ModBlocks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Jetons dans l'inventaire du joueur. Les montants sont en centimes, les jetons valent des euros entiers. */
public final class Chips {
    private Chips() {}

    /** Valeur totale des jetons de l'inventaire (main secondaire comprise), en centimes. */
    public static long total(ServerPlayer p) {
        long t = 0;
        Inventory inv = p.getInventory();
        for (List<ItemStack> list : List.of(inv.items, inv.offhand))
            for (ItemStack s : list) if (s.getItem() instanceof ChipItem c) t += (long) c.euros * 100L * s.getCount();
        return t;
    }

    /**
     * Retire {@code cents} de jetons et rend la monnaie en jetons. {@code cents} doit être un nombre entier d'euros.
     * Faux (sans rien toucher) si le joueur n'a pas assez de jetons.
     */
    public static boolean take(ServerPlayer p, long cents) {
        if (cents <= 0 || cents % 100 != 0) return false;
        long total = total(p);
        if (total < cents) return false;
        clear(p);
        give(p, total - cents);
        return true;
    }

    /** Retire tous les jetons et renvoie leur valeur (centimes). */
    public static long takeAll(ServerPlayer p) {
        long total = total(p);
        clear(p);
        return total;
    }

    /** Donne des jetons pour {@code cents} (euros entiers) ; renvoie les centimes restants non convertibles. Déborde au sol si l'inventaire est plein. */
    public static long give(ServerPlayer p, long cents) {
        long euros = cents / 100;
        List<net.minecraftforge.registries.RegistryObject<net.minecraft.world.item.Item>> chips = ModBlocks.CHIPS;
        for (int i = chips.size() - 1; i >= 0 && euros > 0; i--) {
            ChipItem c = (ChipItem) chips.get(i).get();
            long n = euros / c.euros;
            euros -= n * c.euros;
            while (n > 0) {
                int q = (int) Math.min(n, 64);
                ItemStack st = new ItemStack(c, q);
                if (!p.getInventory().add(st)) p.drop(st, false);
                n -= q;
            }
        }
        return cents % 100;
    }

    private static void clear(ServerPlayer p) {
        Inventory inv = p.getInventory();
        for (List<ItemStack> list : List.of(inv.items, inv.offhand))
            for (int i = 0; i < list.size(); i++) if (list.get(i).getItem() instanceof ChipItem) list.set(i, ItemStack.EMPTY);
        p.containerMenu.broadcastChanges();
    }
}
