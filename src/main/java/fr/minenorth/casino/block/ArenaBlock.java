package fr.minenorth.casino.block;

import fr.minenorth.casino.CockfightService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
 * Arène clandestine de combats, posée par un joueur qui en devient l'organisateur. Deux tailles : {@code arene_coqs} (5 x 4, coqs du
 * poulailler de l'organisateur) et {@link BigArenaBlock} {@code arene_animaux} (7 x 6, animaux des joueurs). Un organisateur qui pose
 * plusieurs arènes du même type n'en a qu'une ouverte à la fois ({@link #OPEN}) ; les autres ressemblent à un tas de caisses bâché.
 * C'est {@link CockfightService} qui tient les arènes, leurs organisateurs et les combats.
 */
public class ArenaBlock extends CasinoBlock {
    public static final BooleanProperty OPEN = BooleanProperty.create("open");
    /** Morceaux de l'arène des coqs (5 x 4 = 20) : propriété à part, les autres tables du casino gardent 0 à 5. */
    public static final IntegerProperty PART_A = IntegerProperty.create("part", 0, 19);

    public ArenaBlock() { this(Game.COCKFIGHT); }

    protected ArenaBlock(Game game) {
        super(game);
        registerDefaultState(defaultBlockState().setValue(OPEN, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(FACING, PART_A, OPEN);   // pas super : la propriété « part » de l'arène va jusqu'à 19
    }

    @Override
    public IntegerProperty part() { return PART_A; }

    /** Numéro du morceau d'un état d'arène, quelle que soit sa taille. */
    public static int partOf(BlockState s) {
        return s.hasProperty(PART_A) ? s.getValue(PART_A) : s.hasProperty(BigArenaBlock.PART_B) ? s.getValue(BigArenaBlock.PART_B) : -1;
    }

    /** Arène des animaux des joueurs (la grande) ou des coqs. */
    public boolean animals() { return game == Game.ANIMALS; }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack stack) {
        super.setPlacedBy(level, pos, state, by, stack);
        if (level instanceof ServerLevel sl) CockfightService.register(sl, pos, this, by instanceof ServerPlayer sp ? sp : null);
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
        if (!ms.is(this) || ms.getValue(part()) != 0) return;
        Direction f = ms.getValue(FACING);
        for (int idx = 0; idx < game.parts(); idx++) {
            BlockPos q = partPos(master, f, idx);
            BlockState o = level.getBlockState(q);
            if (o.is(this) && o.getValue(OPEN) != open) level.setBlock(q, o.setValue(OPEN, open), 3);
        }
    }
}
