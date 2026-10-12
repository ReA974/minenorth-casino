package fr.minenorth.casino;

import fr.minenorth.casino.block.ModBlocks;
import fr.minenorth.casino.config.CasinoConfig;
import fr.minenorth.casino.network.ModNetwork;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(MineNorthCasino.MOD_ID)
public class MineNorthCasino {
    public static final String MOD_ID = "minenorthcasino";

    public MineNorthCasino() {
        CasinoConfig.load();
        ModNetwork.register();
        ModBlocks.register(FMLJavaModLoadingContext.get().getModEventBus());
    }
}
