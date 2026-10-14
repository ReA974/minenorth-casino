package fr.minenorth.casino.block;

import fr.minenorth.casino.MineNorthCasino;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.List;

/** Blocs, objets et onglet créatif du casino. */
public final class ModBlocks {
    private ModBlocks() {}

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MineNorthCasino.MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MineNorthCasino.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MineNorthCasino.MOD_ID);

    /** Valeurs des jetons, en euros (de la plus petite à la plus grande). */
    public static final int[] CHIP_VALUES = {1, 5, 25, 100, 500};

    // Trois habillages de la machine à sous (même jeu, même bloc, autre texture) : blanche et or, bleue, rouge.
    public static final RegistryObject<Block> SLOT = BLOCKS.register("machine_a_sous", () -> new CasinoBlock(CasinoBlock.Game.SLOT));
    public static final RegistryObject<Block> SLOT_BLUE = BLOCKS.register("machine_a_sous_bleue", () -> new CasinoBlock(CasinoBlock.Game.SLOT));
    public static final RegistryObject<Block> SLOT_RED = BLOCKS.register("machine_a_sous_rouge", () -> new CasinoBlock(CasinoBlock.Game.SLOT));
    /** Machine à sous dorée, réservée aux grades VIP (avantage « casinoVip » du mod VIP) ; les trois autres restent libres d'accès. */
    public static final RegistryObject<Block> SLOT_VIP = BLOCKS.register("machine_a_sous_vip", () -> new CasinoBlock(CasinoBlock.Game.SLOT));
    public static final RegistryObject<Block> ROULETTE =BLOCKS.register("table_roulette", () -> new CasinoBlock(CasinoBlock.Game.ROULETTE));
    public static final RegistryObject<Block> BLACKJACK = BLOCKS.register("table_blackjack", () -> new CasinoBlock(CasinoBlock.Game.BLACKJACK));
    public static final RegistryObject<Block> CASHIER = BLOCKS.register("caisse_casino", () -> new CasinoBlock(CasinoBlock.Game.CASHIER));

    public static final RegistryObject<Item> SLOT_ITEM = ITEMS.register("machine_a_sous", () -> new BlockItem(SLOT.get(), new Item.Properties()));
    public static final RegistryObject<Item> SLOT_BLUE_ITEM = ITEMS.register("machine_a_sous_bleue", () -> new BlockItem(SLOT_BLUE.get(), new Item.Properties()));
    public static final RegistryObject<Item> SLOT_RED_ITEM = ITEMS.register("machine_a_sous_rouge", () -> new BlockItem(SLOT_RED.get(), new Item.Properties()));
    public static final RegistryObject<Item> SLOT_VIP_ITEM = ITEMS.register("machine_a_sous_vip", () -> new BlockItem(SLOT_VIP.get(), new Item.Properties().rarity(net.minecraft.world.item.Rarity.RARE)));
    public static final RegistryObject<Item> ROULETTE_ITEM =ITEMS.register("table_roulette", () -> new BlockItem(ROULETTE.get(), new Item.Properties()));
    public static final RegistryObject<Item> BLACKJACK_ITEM = ITEMS.register("table_blackjack", () -> new BlockItem(BLACKJACK.get(), new Item.Properties()));
    public static final RegistryObject<Item> CASHIER_ITEM = ITEMS.register("caisse_casino", () -> new BlockItem(CASHIER.get(), new Item.Properties()));

    /** Jetons jeton_1, jeton_5, jeton_25, jeton_100, jeton_500. */
    public static final List<RegistryObject<Item>> CHIPS = new ArrayList<>();
    static {
        for (int v : CHIP_VALUES) CHIPS.add(ITEMS.register("jeton_" + v, () -> new ChipItem(v)));
    }

    public static final net.minecraft.world.item.CreativeModeTab.DisplayItemsGenerator TAB_ITEMS = (params, out) -> {
                out.accept(SLOT_ITEM.get()); out.accept(SLOT_BLUE_ITEM.get()); out.accept(SLOT_RED_ITEM.get()); out.accept(SLOT_VIP_ITEM.get());
                out.accept(ROULETTE_ITEM.get()); out.accept(BLACKJACK_ITEM.get()); out.accept(CASHIER_ITEM.get());
                for (RegistryObject<Item> c : CHIPS) out.accept(c.get());
            };

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
    }
}
