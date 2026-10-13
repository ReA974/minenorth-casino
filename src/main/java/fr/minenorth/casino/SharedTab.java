package fr.minenorth.casino;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Les objets de ce mod vont dans l'onglet créatif commun « MineNorth » (fourni par minenorth_api). */
@Mod.EventBusSubscriber(modid = "minenorthcasino", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class SharedTab {
    private SharedTab() {}

    @SubscribeEvent
    public static void fill(BuildCreativeModeTabContentsEvent e) {
        if (!e.getTabKey().location().equals(new ResourceLocation("minenorthapi", "minenorth"))) return;
        fr.minenorth.casino.block.ModBlocks.TAB_ITEMS.accept(e.getParameters(), e);
    }
}
