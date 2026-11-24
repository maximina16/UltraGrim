package ac.grim.grimac.checks.impl.breaking;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.BlockBreakCheck;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockBreak;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.defaulttags.BlockTags;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3i;

import java.util.ArrayDeque;

@CheckData(name = "Nuker", alternativeName = "Break", stableKey = "grim.breaking.nuker", description = "Automated multi-directional, excessive-rate, or occluded block breaking")
public class Nuker extends Check implements BlockBreakCheck {
    private static final Verbose V = Verbose.of("{str}");

    private static final int[][] CARDINAL_OFFSETS = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1}
    };

    private record Sample(long timeMs, int x, int y, int z, double dirX, double dirY, double dirZ, int quadrant, boolean largeJump) {}

    private final ArrayDeque<Sample> breakWindow = new ArrayDeque<>(32);

    private Vector3i lastBreakPos;
    private double lastBreakDirX, lastBreakDirY, lastBreakDirZ;
    private boolean hasLastBreakDir;

    private double rateBuffer = 0;
    private double snapBuffer = 0;
    private double wallBuffer = 0;

    public Nuker(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onBlockBreak(BlockBreak blockBreak) {
        if (!player.cameraEntity.isSelf() || player.inVehicle() || player.gamemode == GameMode.SPECTATOR) {
            return;
        }
        if (blockBreak.action != DiggingAction.START_DIGGING) {
            return;
        }

        Vector3i pos = blockBreak.position;
        long now = System.currentTimeMillis();

        // 1. Buried/Occluded Through-Wall Nuker (GhostHand / BedNuker / Xray-Nuker)
        if (player.gamemode != GameMode.CREATIVE && isCompletelyBuriedByOpaqueBlocks(pos)) {
            if (++wallBuffer >= 3.0) {
                wallBuffer = 3.0;
                if (flag(V.write(verbose()).str("buried pos=" + pos.x + "," + pos.y + "," + pos.z)) && shouldModifyPackets()) {
                    blockBreak.cancel();
                }
            }
            return;
        } else {
            wallBuffer = Math.max(0, wallBuffer - 0.3);
        }

        if (pos.equals(lastBreakPos)) {
            return;
        }
        lastBreakPos = pos;

        while (!breakWindow.isEmpty() && now - breakWindow.peekFirst().timeMs > 1400L) {
            breakWindow.pollFirst();
        }

        double eyeY = player.y + 1.62;
        double dx = (pos.x + 0.5) - player.x;
        double dy = (pos.y + 0.5) - eyeY;
        double dz = (pos.z + 0.5) - player.z;
        double horizSq = dx * dx + dz * dz;
        double distSq = horizSq + dy * dy;

        boolean largeJump = false;
        int quadrant = -1;
        if (distSq >= 1.5) {
            double invDist = 1.0 / Math.sqrt(distSq);
            double ndx = dx * invDist;
            double ndy = dy * invDist;
            double ndz = dz * invDist;
            if (horizSq >= 0.45) {
                quadrant = (dx >= 0 ? 1 : 0) | (dz >= 0 ? 2 : 0);
            }
            if (hasLastBreakDir) {
                double dot = ndx * lastBreakDirX + ndy * lastBreakDirY + ndz * lastBreakDirZ;
                if (dot < 0.342) {
                    largeJump = true;
                }
            }
            lastBreakDirX = ndx;
            lastBreakDirY = ndy;
            lastBreakDirZ = ndz;
            hasLastBreakDir = true;
            breakWindow.addLast(new Sample(now, pos.x, pos.y, pos.z, ndx, ndy, ndz, quadrant, largeJump));
        } else {
            breakWindow.addLast(new Sample(now, pos.x, pos.y, pos.z, 0, 0, 0, -1, false));
        }

        // 2. Packet Rate Cap (>26 distinct blocks in 1.0s while ticking reliably)
        if (player.isTickingReliablyFor(5)) {
            int lastSecondCount = 0;
            int jumpCount = 0;
            int quadrantMask = 0;
            for (Sample s : breakWindow) {
                if (now - s.timeMs <= 1000L) {
                    lastSecondCount++;
                }
                if (s.largeJump) {
                    jumpCount++;
                }
                if (s.quadrant >= 0) {
                    quadrantMask |= (1 << s.quadrant);
                }
            }

            if (lastSecondCount > 26) {
                if (++rateBuffer >= 2.0) {
                    rateBuffer = 2.0;
                    if (flag(V.write(verbose()).str("rate=" + lastSecondCount + "/s")) && shouldModifyPackets()) {
                        blockBreak.cancel();
                    }
                }
                return;
            } else {
                rateBuffer = Math.max(0, rateBuffer - 0.25);
            }

            // 3. 360° Silent-Snap Nuker (>= 9 >70° snaps within 1.4s spanning >= 3 quadrants)
            int quadrantsHit = Integer.bitCount(quadrantMask);
            if (jumpCount >= 9 && quadrantsHit >= 3) {
                if (++snapBuffer >= 2.0) {
                    snapBuffer = 2.0;
                    if (flag(V.write(verbose()).str("aura snaps=" + jumpCount + " quads=" + quadrantsHit)) && shouldModifyPackets()) {
                        blockBreak.cancel();
                    }
                }
            } else {
                snapBuffer = Math.max(0, snapBuffer - 0.2);
            }
        }
    }

    private boolean isCompletelyBuriedByOpaqueBlocks(Vector3i target) {
        double eyeY = player.y + 1.62;
        double dx = (target.x + 0.5) - player.x;
        double dy = (target.y + 0.5) - eyeY;
        double dz = (target.z + 0.5) - player.z;
        if (dx * dx + dy * dy + dz * dz < 4.5) {
            return false;
        }

        StateType targetType = player.compensatedWorld.getBlock(target).getType();
        if (!isStrictFullOpaqueBlock(targetType)) {
            return false;
        }

        for (int[] off : CARDINAL_OFFSETS) {
            int nx = target.x + off[0];
            int ny = target.y + off[1];
            int nz = target.z + off[2];
            WrappedBlockState neighbor = player.compensatedWorld.getBlock(nx, ny, nz);
            if (!isStrictFullOpaqueBlock(neighbor.getType())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isStrictFullOpaqueBlock(StateType type) {
        if (type == null || type.isAir() || !type.isSolid() || !type.isBlocking()) {
            return false;
        }
        if (type.getHardness() <= 0.0f) {
            return false;
        }
        if (type == StateTypes.NOTE_BLOCK
                || type == StateTypes.MUSHROOM_STEM
                || type == StateTypes.BROWN_MUSHROOM_BLOCK
                || type == StateTypes.RED_MUSHROOM_BLOCK
                || type == StateTypes.CHORUS_PLANT
                || type == StateTypes.CHORUS_FLOWER
                || type == StateTypes.SPAWNER
                || type == StateTypes.TRIAL_SPAWNER
                || type == StateTypes.VAULT
                || type == StateTypes.PISTON
                || type == StateTypes.STICKY_PISTON
                || type == StateTypes.MOVING_PISTON
                || type == StateTypes.PISTON_HEAD
                || type == StateTypes.SLIME_BLOCK
                || type == StateTypes.HONEY_BLOCK
                || type == StateTypes.SOUL_SAND
                || type == StateTypes.FARMLAND
                || type == StateTypes.DIRT_PATH
                || type == StateTypes.MUD
                || type == StateTypes.SCULK_SHRIEKER
                || type == StateTypes.SCULK_SENSOR
                || type == StateTypes.CALIBRATED_SCULK_SENSOR) {
            return false;
        }
        if (BlockTags.SLABS.contains(type)
                || BlockTags.STAIRS.contains(type)
                || BlockTags.FENCES.contains(type)
                || BlockTags.WALLS.contains(type)
                || BlockTags.DOORS.contains(type)
                || BlockTags.TRAPDOORS.contains(type)
                || BlockTags.FENCE_GATES.contains(type)
                || BlockTags.LEAVES.contains(type)
                || BlockTags.GLASS_BLOCKS.contains(type)
                || BlockTags.ICE.contains(type)
                || BlockTags.SHULKER_BOXES.contains(type)
                || BlockTags.BEDS.contains(type)
                || BlockTags.ANVIL.contains(type)
                || BlockTags.CAULDRONS.contains(type)
                || BlockTags.SIGNS.contains(type)
                || BlockTags.BANNERS.contains(type)) {
            return false;
        }
        return true;
    }
}
