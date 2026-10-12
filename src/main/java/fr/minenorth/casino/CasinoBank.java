package fr.minenorth.casino;

import com.mojang.logging.LogUtils;
import fr.minenorth.api.BankTx;
import fr.minenorth.api.MineNorth;
import fr.minenorth.api.PayResult;
import fr.minenorth.casino.CompanyLink.Casino;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.UUID;

/** Mouvements d'argent du casino : mise du joueur vers le compte de l'entreprise, gain du compte vers le joueur. */
final class CasinoBank {
    private CasinoBank() {}

    private static final Logger LOG = LogUtils.getLogger();

    static long houseBalance(MinecraftServer s, Casino c) { return MineNorth.bank().balance(s, c.account()); }

    /** Prélève la mise par la carte du joueur et la verse à l'entreprise. */
    static PayResult stake(ServerPlayer p, Casino c, long cents, String label) {
        PayResult r = MineNorth.bank().check(p, cents);
        if (!r.ok()) return r;
        return MineNorth.bank().transfer(p.server, p.getUUID(), c.account(), cents, BankTx.INCOME, label, MineNorth.displayName(p));
    }

    /** Verse un gain du compte de l'entreprise au joueur (connecté ou non). */
    static boolean payout(MinecraftServer s, Casino c, UUID player, long cents, String label) {
        if (cents <= 0) return true;
        PayResult r = MineNorth.bank().transfer(s, c.account(), player, cents, BankTx.PAYMENT, label, "Casino");
        if (!r.ok()) LOG.error("[Casino] Gain de {} cts NON versé à {} ({}) : {}", cents, player, label, r);
        return r.ok();
    }
}
