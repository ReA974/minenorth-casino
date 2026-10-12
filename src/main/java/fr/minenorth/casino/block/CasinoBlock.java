package fr.minenorth.casino.block;

import fr.minenorth.casino.CasinoService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/** Machine à sous, table de roulette ou table de blackjack : aucune donnée sur le bloc, tout vient de la config et du serveur. */
public class CasinoBlock extends Block {
    public enum Game { SLOT, ROULETTE, BLACKJACK }

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final VoxelShape FULL = Block.box(0, 0, 0, 16, 16, 16);
    private static final VoxelShape TABLE = Block.box(0, 0, 0, 16, 14, 16);

    public final Game game;

    public CasinoBlock(Game game) {
        super(BlockBehaviour.Properties.of().strength(3.0f, 6.0f).sound(game == Game.SLOT ? SoundType.METAL : SoundType.WOOD)
                .noOcclusion().pushReaction(PushReaction.BLOCK));
        this.game = game;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING); }

    @Override public VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return game == Game.SLOT ? FULL : TABLE; }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer sp) CasinoService.open(sp, game, pos);
        return InteractionResult.CONSUME;
    }
}
