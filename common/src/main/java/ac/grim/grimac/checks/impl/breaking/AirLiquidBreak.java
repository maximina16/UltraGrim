package ac.grim.grimac.checks.impl.breaking;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.impl.verbose.VerboseCodecs;
import ac.grim.grimac.checks.type.BlockBreakCheck;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockBreak;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3i;
import org.jetbrains.annotations.NotNull;

@CheckData(name = "AirLiquidBreak", stableKey = "grim.breaking.air_liquid_break", description = "Breaking a block that cannot be broken")
public class AirLiquidBreak extends Check implements BlockBreakCheck {
    private static final Verbose V = Verbose.of("block={block}, type={digging}");

    public final boolean noFireHitbox = player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_15_2);
    private int lastTick;
    private boolean didLastFlag;
    // Initialize to non-null values to prevent NPE when checking for blockType properties and if position equals old position
    private @NotNull Vector3i lastBreakLoc = new Vector3i();
    private @NotNull StateType lastBlockType = StateTypes.AIR;
    private long lastValidBreakMs = 0L;
    private @NotNull Vector3i lastValidBreakPos = new Vector3i();
    private double airBuffer = 0;
    private double liquidBuffer = 0;

    public AirLiquidBreak(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onBlockBreak(BlockBreak blockBreak) {
        if (blockBreak.action != DiggingAction.START_DIGGING && blockBreak.action != DiggingAction.FINISHED_DIGGING)
            return;

        final StateType block = blockBreak.block.getType();
        final long now = System.currentTimeMillis();

        int newTick = GrimAPI.INSTANCE.getTickManager().currentTick;
        if (lastTick == newTick
                && lastBreakLoc.equals(blockBreak.position)
                && !didLastFlag
                && lastBlockType.getHardness() == 0.0F
                && lastBlockType.getBlastResistance() == 0.0F
                && (block == StateTypes.WATER || block.isAir())
        ) return;
        lastTick = newTick;
        lastBreakLoc = blockBreak.position;
        lastBlockType = block;

        boolean isUnbreakableFinish = block.getHardness() == -1.0f && blockBreak.action == DiggingAction.FINISHED_DIGGING;
        boolean isLiquid = block == StateTypes.WATER || block == StateTypes.LAVA || block == StateTypes.BUBBLE_COLUMN;
        boolean isAirLike = block.isAir()
                || block == StateTypes.MOVING_PISTON
                || (block == StateTypes.FIRE && noFireHitbox)
                || (block == StateTypes.LIGHT && !(player.inventory.getHeldItem().is(ItemTypes.LIGHT) || player.inventory.getOffHand().is(ItemTypes.LIGHT)));

        if (isUnbreakableFinish) {
            if (shouldModifyPackets()) {
                blockBreak.cancel();
            }
            if (++liquidBuffer >= 2.0) {
                liquidBuffer = 2.0;
                didLastFlag = flag(V.write(verbose())
                        .sint(VerboseCodecs.block(block, player.getClientVersion()))
                        .uint(VerboseCodecs.enumId(blockBreak.action)));
            }
            return;
        }

        if (isLiquid) {
            // Exempt underwater kelp/seagrass double-start within 400ms of valid plant break
            if (now - lastValidBreakMs < 400L && distanceSq(blockBreak.position, lastValidBreakPos) <= 9) {
                didLastFlag = false;
                return;
            }
            if (shouldModifyPackets()) {
                blockBreak.cancel();
            }
            if (++liquidBuffer >= 4.0) {
                liquidBuffer = 4.0;
                didLastFlag = flag(V.write(verbose())
                        .sint(VerboseCodecs.block(block, player.getClientVersion()))
                        .uint(VerboseCodecs.enumId(blockBreak.action)));
            }
            return;
        }

        if (isAirLike) {
            // CraftPixel server-side AoE breaks (MaxiTools VeinMine/Timber/AreaDig, MaxiArcane 3x3/5x5/7x7 crop wave,
            // MaxiSkills Treecapitator/Terraform, MaxiFarm ARM_SWING crop harvest) turn blocks into AIR server-side
            // while the client is still swinging/holding left-click. Exempt AIR digs right after nearby valid breaks.
            if (now - lastValidBreakMs <= 1800L && distanceSq(blockBreak.position, lastValidBreakPos) <= 81) {
                didLastFlag = false;
                return;
            }
            if (++airBuffer >= 10.0) {
                airBuffer = 10.0;
                if (flag(V.write(verbose())
                        .sint(VerboseCodecs.block(block, player.getClientVersion()))
                        .uint(VerboseCodecs.enumId(blockBreak.action))) && shouldModifyPackets()) {
                    didLastFlag = true;
                    blockBreak.cancel();
                }
            }
            return;
        }

        // Valid solid/plant block break
        lastValidBreakMs = now;
        lastValidBreakPos = blockBreak.position;
        airBuffer = Math.max(0, airBuffer - 1.5);
        liquidBuffer = Math.max(0, liquidBuffer - 0.5);
        didLastFlag = false;
    }

    private static int distanceSq(Vector3i a, Vector3i b) {
        int dx = a.x - b.x;
        int dy = a.y - b.y;
        int dz = a.z - b.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
