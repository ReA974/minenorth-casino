package fr.minenorth.casino.client;

import fr.minenorth.casino.network.ModNetwork;
import fr.minenorth.casino.network.ModNetwork.OpenPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Machine à sous : le serveur tire les trois rouleaux, l'écran ne fait que l'animation et l'affichage. */
public class SlotScreen extends CasinoScreen {
    private static final int[] STOP_AT = {22, 34, 46};
    private final List<ItemStack> symbols = new ArrayList<>();
    private final Random anim = new Random();
    private final int[] shown = new int[3];
    private int[] finalReels;
    private boolean waiting;
    private int ticks;
    private long lastWin = -1;
    private CasinoButton spin;

    public SlotScreen(OpenPacket info) {
        super(info, "Machine à sous", 330, 214);
        for (String id : info.items()) {
            Item it = ResourceLocation.tryParse(id) == null ? null : ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(id));
            symbols.add(new ItemStack(it == null || it == Items.AIR ? Items.BARRIER : it));
        }
    }

    @Override
    protected void init() {
        super.init();
        spin = new CasinoButton(left + 40, top + 176, 130, 22, "LANCER", CasinoStyle.GREEN, this::spin);
        addRenderableWidget(spin);
    }

    private boolean spinning() { return waiting || finalReels != null; }

    private void spin() {
        if (spinning()) return;
        waiting = true;
        ticks = 0;
        lastWin = -1;
        status = "";
        ModNetwork.CHANNEL.sendToServer(new ModNetwork.SlotSpinPacket(chip));
        sound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.get(), 0.8f, 0.8f);
        sound(net.minecraft.sounds.SoundEvents.PISTON_EXTEND, 1.6f, 0.4f);   // levier
    }

    private static void sound(net.minecraft.sounds.SoundEvent e, float pitch, float volume) {
        net.minecraft.client.Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(e, pitch, volume));
    }

    public void result(ModNetwork.SlotResultPacket p) {
        finalReels = new int[]{p.a(), p.b(), p.c()};
        waiting = false;
        ticks = 0;
        lastWin = p.win();
    }

    @Override
    public void message(boolean ok, String msg) {
        super.message(ok, msg);
        if (!ok && waiting) waiting = false;
    }

    @Override
    public void tick() {
        if (!spinning()) return;
        ticks++;
        if (ticks % 2 == 0) {
            for (int i = 0; i < 3; i++) shown[i] = anim.nextInt(symbols.size());
            // cliquetis des rouleaux tant qu'au moins un tourne
            if (finalReels == null || ticks < STOP_AT[2]) sound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_HAT.value(), 1.4f + anim.nextFloat() * 0.4f, 0.3f);
        }
        if (finalReels != null) {
            for (int i = 0; i < 3; i++) {
                if (ticks >= STOP_AT[i]) {
                    if (ticks == STOP_AT[i]) sound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BASEDRUM.value(), 0.9f + 0.15f * i, 0.8f);   // arrêt du rouleau
                    shown[i] = finalReels[i];
                }
            }
            if (ticks >= STOP_AT[2] + 4) {
                finalReels = null;
                if (lastWin > 0) {
                    status = "GAGNÉ ! +" + CasinoStyle.euros(lastWin); statusOk = true;
                    sound(net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, 1.2f, 0.8f);
                    sound(net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP, 1.5f, 0.8f);
                } else {
                    status = "Perdu."; statusOk = false;
                    sound(net.minecraft.sounds.SoundEvents.VILLAGER_NO, 1.0f, 0.5f);
                }
            }
        } else if (ticks > 200) waiting = false;   // pas de réponse du serveur
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        spin.enabled(!spinning());
        super.render(g, mx, my, pt);
        // Rouleaux
        for (int i = 0; i < 3; i++) {
            int x = left + 20 + i * 62, y = top + 54;
            boolean stopped = finalReels != null && ticks >= STOP_AT[i];
            g.fill(x - 2, y - 2, x + 56, y + 62, stopped ? CasinoStyle.GOLD : CasinoStyle.FRAME);
            g.fill(x, y, x + 54, y + 60, CasinoStyle.LIST);
            if (!symbols.isEmpty()) CasinoStyle.item(g, symbols.get(Math.min(shown[i], symbols.size() - 1)), x + 3, y + 6, 3f);
        }
        // Table des gains
        int px = left + 214;
        g.drawString(font, "Gains (x la mise)", px, top + 48, CasinoStyle.GOLD, false);
        int y = top + 62;
        for (int i = 0; i < symbols.size() && i < 12; i++) {
            if (y > top + h - 24) break;
            CasinoStyle.item(g, symbols.get(i), px, y - 3, 0.75f);
            CasinoStyle.item(g, symbols.get(i), px + 14, y - 3, 0.75f);
            CasinoStyle.item(g, symbols.get(i), px + 28, y - 3, 0.75f);
            g.drawString(font, "x" + fmt(info.x3().get(i)), px + 46, y, CasinoStyle.TEXT, false);
            y += 11;
            if (info.x2().get(i) > 0) {
                CasinoStyle.item(g, symbols.get(i), px, y - 3, 0.75f);
                CasinoStyle.item(g, symbols.get(i), px + 14, y - 3, 0.75f);
                g.drawString(font, "x" + fmt(info.x2().get(i)), px + 46, y, CasinoStyle.MUTED, false);
                y += 11;
            }
        }
        g.drawString(font, "Mise", left + 20, top + 122, CasinoStyle.MUTED, false);
        g.drawString(font, CasinoStyle.euros(chip), left + 50, top + 122, CasinoStyle.WHITE, false);
        g.drawString(font, "(" + CasinoStyle.euros(info.min()) + " à " + CasinoStyle.euros(info.max()) + ")", left + 100, top + 122, CasinoStyle.MUTED, false);
        chipRow(g, left + 20, top + 138, mx, my);
        drawStatus(g, top + 160);
    }

    private static String fmt(float f) { return f == Math.floor(f) ? String.valueOf((int) f) : String.valueOf(f); }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!spinning() && chipClick(left + 20, top + 138, mx, my)) return true;
        return super.mouseClicked(mx, my, button);
    }
}
