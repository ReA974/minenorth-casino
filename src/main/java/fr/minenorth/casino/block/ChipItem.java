package fr.minenorth.casino.block;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Jeton de casino : une valeur fixe en euros, achetée et rachetée à la caisse. */
public class ChipItem extends Item {
    public final int euros;

    public ChipItem(int euros) {
        super(new Item.Properties());
        this.euros = euros;
    }

    @Override
    public void appendHoverText(ItemStack st, @Nullable Level level, List<Component> tip, TooltipFlag flag) {
        tip.add(Component.literal("Valeur : " + euros + " €").withStyle(net.minecraft.ChatFormatting.GOLD));
    }
}
