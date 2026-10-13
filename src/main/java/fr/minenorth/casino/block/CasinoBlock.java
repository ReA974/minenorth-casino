package fr.minenorth.casino.block;

import fr.minenorth.casino.CasinoService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Machine à sous, table de roulette, table de blackjack ou caisse : aucune donnée sur le bloc, tout vient de la config et du serveur.
 * La machine à sous (1x1x2) et la roulette (3x2) sont composées de plusieurs blocs identiques ; {@link #PART} numérote les morceaux,
 * le morceau 0 (celui posé par le joueur) est le seul à rendre l'objet.
 */
public class CasinoBlock extends Block {
    public enum Game {
        SLOT(1, 1, 2), ROULETTE(3, 2, 1), BLACKJACK(1, 1, 1), CASHIER(1, 1, 1);

        /** Largeur (vers la droite du joueur), profondeur (vers l'arrière) et hauteur, en blocs. */
        final int w, d, h;
        Game(int w, int d, int h) { this.w = w; this.d = d; this.h = h; }
        int parts() { return w * d * h; }
    }

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final IntegerProperty PART = IntegerProperty.create("part", 0, 5);
    private static final VoxelShape FULL = Block.box(0, 0, 0, 16, 16, 16);
    private static final VoxelShape TABLE = Block.box(0, 0, 0, 16, 14, 16);

    public final Game game;

    public CasinoBlock(Game game) {
        // Résistance énorme : une explosion ne doit pas laisser une table à moitié détruite.
        super(BlockBehaviour.Properties.of().strength(3.0f, 3600000.0f).sound(game == Game.ROULETTE || game == Game.BLACKJACK ? SoundType.WOOD : SoundType.METAL)
                .noOcclusion().pushReaction(PushReaction.BLOCK));
        this.game = game;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, 0));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING, PART); }

    @Override public VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) {
        return game == Game.ROULETTE || game == Game.BLACKJACK ? TABLE : FULL;
    }

    // ------------------------------------------------------------------ géométrie des morceaux

    /**
     * Position du morceau (i, j, k) pour un bloc principal en {@code master}. i vers la droite du joueur, j vers l'arrière, k vers le haut.
     * Repère « face au nord » (x- = droite du joueur), puis tourné d'autant de quarts de tour que l'orientation du bloc.
     */
    private static BlockPos partPos(BlockPos master, Direction f, int i, int j, int k) {
        int x = -i, z = j;
        for (int s = (f.get2DDataValue() + 2) % 4; s > 0; s--) { int nx = -z; z = x; x = nx; }
        return master.offset(x, k, z);
    }

    private BlockPos partPos(BlockPos master, Direction f, int idx) {
        int k = idx / (game.w * game.d), rem = idx % (game.w * game.d);
        return partPos(master, f, rem % game.w, rem / game.w, k);
    }

    private BlockPos masterOf(BlockPos pos, BlockState st) {
        BlockPos off = partPos(BlockPos.ZERO, st.getValue(FACING), st.getValue(PART));
        return pos.subtract(off);
    }

    // ------------------------------------------------------------------ pose et retrait

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Direction f = ctx.getHorizontalDirection().getOpposite();
        Level l = ctx.getLevel();
        for (int idx = 0; idx < game.parts(); idx++) {
            BlockPos q = partPos(ctx.getClickedPos(), f, idx);
            if (l.isOutsideBuildHeight(q) || (idx > 0 && !l.getBlockState(q).canBeReplaced())) return null;
        }
        return defaultBlockState().setValue(FACING, f).setValue(PART, 0);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack stack) {
        super.setPlacedBy(level, pos, state, by, stack);
        if (level.isClientSide) return;
        for (int idx = 1; idx < game.parts(); idx++) {
            level.setBlock(partPos(pos, state.getValue(FACING), idx), state.setValue(PART, idx), 3);
        }
    }

    /** Casser un morceau autre que le principal : l'objet tombe quand même (la table de loot ne le donne que pour le morceau 0). */
    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && !player.isCreative() && state.getValue(PART) != 0) {
            BlockPos master = masterOf(pos, state);
            BlockState ms = level.getBlockState(master);
            if (ms.is(this)) dropResources(ms, level, master, null, player, player.getMainHandItem());
        }
        super.playerWillDestroy(level, pos, state, player);
    }

    /** Quel que soit le moyen de retrait, les autres morceaux disparaissent avec celui-ci. */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!level.isClientSide && !state.is(newState.getBlock()) && game.parts() > 1) {
            Direction f = state.getValue(FACING);
            BlockPos master = masterOf(pos, state);
            for (int idx = 0; idx < game.parts(); idx++) {
                BlockPos q = partPos(master, f, idx);
                if (q.equals(pos)) continue;
                BlockState o = level.getBlockState(q);
                if (o.is(this) && o.getValue(FACING) == f && o.getValue(PART) == idx) level.setBlock(q, Blocks.AIR.defaultBlockState(), 35);
            }
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer sp) CasinoService.open(sp, game, pos);
        return InteractionResult.CONSUME;
    }
}
