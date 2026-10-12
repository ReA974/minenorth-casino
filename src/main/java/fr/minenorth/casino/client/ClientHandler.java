package fr.minenorth.casino.client;

import fr.minenorth.casino.network.ModNetwork;
import net.minecraft.client.Minecraft;

/** Réception des paquets du serveur (côté client uniquement). */
public final class ClientHandler {
    private ClientHandler() {}

    public static void open(ModNetwork.OpenPacket p) {
        Minecraft mc = Minecraft.getInstance();
        switch (p.game()) {
            case 0 -> mc.setScreen(new SlotScreen(p));
            case 1 -> mc.setScreen(new RouletteScreen(p));
            default -> mc.setScreen(new BlackjackScreen(p));
        }
    }

    public static void message(boolean ok, String msg) {
        if (Minecraft.getInstance().screen instanceof CasinoScreen s) s.message(ok, msg);
    }

    public static void slotResult(ModNetwork.SlotResultPacket p) {
        if (Minecraft.getInstance().screen instanceof SlotScreen s) s.result(p);
    }

    public static void rouletteResult(ModNetwork.RouletteResultPacket p) {
        if (Minecraft.getInstance().screen instanceof RouletteScreen s) s.result(p);
    }

    public static void bjState(ModNetwork.BjStatePacket p) {
        if (Minecraft.getInstance().screen instanceof BlackjackScreen s) s.state(p);
    }
}
