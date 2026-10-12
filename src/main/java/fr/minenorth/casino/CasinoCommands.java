package fr.minenorth.casino;

import com.mojang.brigadier.CommandDispatcher;
import fr.minenorth.casino.CompanyLink.Casino;
import fr.minenorth.casino.config.CasinoConfig;
import fr.minenorth.casino.game.SlotGame;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;

/** /casino (état, caisse, statistiques) et /casinoreload : OP / console uniquement. */
@Mod.EventBusSubscriber(modid = MineNorthCasino.MOD_ID)
public final class CasinoCommands {
    private CasinoCommands() {}

    private static String euros(long cents) {
        long a = Math.abs(cents);
        return (cents < 0 ? "-" : "") + (a / 100) + "," + String.format("%02d", a % 100) + " €";
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent e) {
        CommandDispatcher<CommandSourceStack> d = e.getDispatcher();
        d.register(Commands.literal("casino").requires(s -> s.hasPermission(2)).executes(ctx -> {
            CommandSourceStack src = ctx.getSource();
            Casino c = CompanyLink.resolve(src.getServer());
            if (c == null) {
                src.sendSuccess(() -> Component.literal("§cCasino hors service : entreprise introuvable, inactive (ou fermée) ou non configurée "
                        + "(config/minenorth_casino.json)."), false);
                return 0;
            }
            long cash = CasinoBank.houseBalance(src.getServer(), c);
            src.sendSuccess(() -> Component.literal("§6Casino : §f" + c.name() + " §7(entreprise n°" + c.id() + ") §6caisse : §f" + euros(cash)), false);
            double rtp = SlotGame.rtp(CasinoConfig.get().machine_a_sous.symboles) * 100;
            src.sendSuccess(() -> Component.literal(String.format("§7Machine à sous : retour joueur théorique %.1f %%", rtp)), false);
            Map<String, long[]> stats = CasinoStats.get(src.getServer()).all();
            if (stats.isEmpty()) src.sendSuccess(() -> Component.literal("§7Aucune partie jouée."), false);
            for (Map.Entry<String, long[]> en : stats.entrySet()) {
                long[] v = en.getValue();
                src.sendSuccess(() -> Component.literal("§e" + en.getKey() + " §7: " + v[0] + " parties, misé " + euros(v[1])
                        + ", payé " + euros(v[2]) + ", résultat casino " + euros(v[1] - v[2])), false);
            }
            return 1;
        }));
        d.register(Commands.literal("casinoreload").requires(s -> s.hasPermission(2)).executes(ctx -> {
            boolean ok = CasinoConfig.load();
            ctx.getSource().sendSuccess(() -> Component.literal(ok ? "§aConfiguration du casino rechargée."
                    : "§cminenorth_casino.json illisible : ancienne configuration conservée."), true);
            return ok ? 1 : 0;
        }));
    }
}
