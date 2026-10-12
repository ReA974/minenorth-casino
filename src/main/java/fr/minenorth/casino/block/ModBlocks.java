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

/** Blocs, objets et onglet créatif du casino. */
public final class ModBlocks {
    private ModBlocks() {}

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MineNorthCasino.MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MineNorthCasino.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MineNorthCasino.MOD_ID);

    public static final RegistryObject<Block> SLOT = BLOCKS.register("machine_a_sous", () -> new CasinoBlock(CasinoBlock.Game.SLOT));
    public static final RegistryObject<Block> ROULETTE = BLOCKS.register("table_roulette", () -> new CasinoBlock(CasinoBlock.Game.ROULETTE));
    public static final RegistryObject<Block> BLACKJACK = BLOCKS.register("table_blackjack", () -> new CasinoBlock(CasinoBlock.Game.BLACKJACK));

    public static final RegistryObject<Item> SLOT_ITEM = ITEMS.register("machine_a_sous", () -> new BlockItem(SLOT.get(), new Item.Properties()));
    public static final RegistryObject<Item> ROULETTE_ITEM = ITEMS.register("table_roulette", () -> new BlockItem(ROULETTE.get(), new Item.Properties()));
    public static final RegistryObject<Item> BLACKJACK_ITEM = ITEMS.register("table_blackjack", () -> new BlockItem(BLACKJACK.get(), new Item.Properties()));

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("casino", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.minenorthcasino"))
            .icon(() -> new ItemStack(SLOT_ITEM.get()))
            .displayItems((params, out) -> { out.accept(SLOT_ITEM.get()); out.accept(ROULETTE_ITEM.get()); out.accept(BLACKJACK_ITEM.get()); })
            .build());

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        TABS.register(bus);
    }
}
