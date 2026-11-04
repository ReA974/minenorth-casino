package fr.minenorth.casino.block;

import fr.minenorth.casino.CockfightService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import org.jetbrains.annotations.Nullable;

/**
 * Arène clandestine de combats (5 x 4 blocs : coqs de la maison ou animaux des joueurs). Plusieurs arènes peuvent être posées : une seule est ouverte à la fois
 * ({@link #OPEN}), les autres ressemblent à un tas de caisses bâché. L'emplacement ouvert change régulièrement, comme les boutiques
 * illégales ; c'est {@link CockfightService} qui tient la liste et choisit.
 */
public class ArenaBlock extends CasinoBlock {
    public static final BooleanProperty OPEN = BooleanProperty.create("open");
    /** Morceaux de l'arène (5 x 4 = 20) : propriété à part, les autres tables du casino gardent 0 à 5. */
    public static final IntegerProperty PART_A = IntegerProperty.create("part", 0, 19);

    public ArenaBlock() {
        super(Game.COCKFIGHT);
        registerDefaultState(defaultBlockState().setValue(OPEN, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(FACING, PART_A, OPEN);   // pas super : la propriété « part » de l'arène va jusqu'à 19
    }

    @Override
    public IntegerProperty part() { return PART_A; }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack stack) {
        super.setPlacedBy(level, pos, state, by, stack);
        if (level instanceof ServerLevel sl) CockfightService.register(sl, pos);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (level instanceof ServerLevel sl && !state.is(newState.getBlock())) CockfightService.unregister(sl, masterOf(pos, state));
        super.onRemove(state, level, pos, newState, moved);
    }

    /** Ouvre ou ferme tous les morceaux d'une arène (seulement si son chunk est chargé). */
    public void setOpen(Level level, BlockPos master, boolean open) {
        if (!level.isLoaded(master)) return;
        BlockState ms = level.getBlockState(master);
        if (!ms.is(this) || ms.getValue(PART_A) != 0) return;
        Direction f = ms.getValue(FACING);
        for (int idx = 0; idx < game.parts(); idx++) {
            BlockPos q = partPos(master, f, idx);
            BlockState o = level.getBlockState(q);
            if (o.is(this) && o.getValue(OPEN) != open) level.setBlock(q, o.setValue(OPEN, open), 3);
        }
    }
}
