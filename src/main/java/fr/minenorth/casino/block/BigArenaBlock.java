package fr.minenorth.casino.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/** Grande arène (7 x 6 blocs) des combats d'animaux des joueurs : chiens, chats, animaux de compagnie… La place qu'il leur faut. */
public class BigArenaBlock extends ArenaBlock {
    /** Morceaux de la grande arène (7 x 6 = 42). */
    public static final IntegerProperty PART_B = IntegerProperty.create("part", 0, 41);

    public BigArenaBlock() { super(Game.ANIMALS); }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING, PART_B, OPEN); }

    @Override
    public IntegerProperty part() { return PART_B; }
}
