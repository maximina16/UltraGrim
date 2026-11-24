package ac.grim.grimac.checks.impl.breaking;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.BlockBreakCheck;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockBreak;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.defaulttags.BlockTags;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;

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
    private final ArrayDeque<Sample> interactWindow = new ArrayDeque<>(24);

    private Vector3i lastBreakPos;
    private double lastBreakDirX, lastBreakDirY, lastBreakDirZ;
    private boolean hasLastBreakDir;

    private Vector3i lastInteractPos;
    private double lastInteractDirX, lastInteractDirY, lastInteractDirZ;
    private boolean hasLastInteractDir;

    private double rateBuffer = 0;
    private double snapBuffer = 0;
    private double wallBuffer = 0;
    private double interactAuraBuffer = 0;

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
        // Only flags when all 6 adjacent neighbors are untouched full opaque solid blocks and player is outside them.
        if (player.gamemode != GameMode.CREATIVE && isCompletelyBuriedByOpaqueBlocks(pos)) {
            if (shouldModifyPackets()) {
                blockBreak.cancel();
            }
            if (++wallBuffer >= 2.0) {
                wallBuffer = 2.0;
                flag(V.write(verbose()).str("buried pos=" + pos.x + "," + pos.y + "," + pos.z));
            }
            return;
        } else {
            wallBuffer = Math.max(0, wallBuffer - 0.25);
        }

        // Ignore repeated START_DIGGING on the exact same coordinate
        if (pos.equals(lastBreakPos)) {
            return;
        }
        lastBreakPos = pos;

        while (!breakWindow.isEmpty() && now - breakWindow.peekFirst().timeMs > 1400L) {
            breakWindow.pollFirst();
        }

        // Compute normalized vector from eye center to block center
        double eyeY = player.y + 1.62;
        double dx = (pos.x + 0.5) - player.x;
        double dy = (pos.y + 0.5) - eyeY;
        double dz = (pos.z + 0.5) - player.z;
        double horizSq = dx * dx + dz * dz;
        double distSq = horizSq + dy * dy;

        boolean largeJump = false;
        int quadrant = -1;
        if (distSq >= 1.35) {
            double invDist = 1.0 / Math.sqrt(distSq);
            double ndx = dx * invDist;
            double ndy = dy * invDist;
            double ndz = dz * invDist;
            if (horizSq >= 0.45) {
                quadrant = (dx >= 0 ? 1 : 0) | (dz >= 0 ? 2 : 0);
            }
            if (hasLastBreakDir) {
                double dot = ndx * lastBreakDirX + ndy * lastBreakDirY + ndz * lastBreakDirZ;
                // dot < 0.342 corresponds to > 70 degrees 3D angular snap between consecutive broken blocks
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

        // 2. Packet Rate Cap (>25 distinct blocks in 1.0s while ticking reliably; vanilla max is 20 ticks/sec)
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

            if (lastSecondCount > 25) {
                if (shouldModifyPackets()) {
                    blockBreak.cancel();
                }
                if (++rateBuffer >= 2.0) {
                    rateBuffer = 2.0;
                    flag(V.write(verbose()).str("rate=" + lastSecondCount + "/s"));
                }
                return;
            } else {
                rateBuffer = Math.max(0, rateBuffer - 0.25);
            }

            // 3. 360° Silent-Snap Nuker (Crop-Aura / Ore-Aura / Foraging-Aura)
            // >= 8 distinct >70° snaps within 1.4s spanning >= 3 horizontal quadrants around the player
            int quadrantsHit = Integer.bitCount(quadrantMask);
            if (jumpCount >= 8 && quadrantsHit >= 3) {
                if (shouldModifyPackets()) {
                    blockBreak.cancel();
                }
                if (++snapBuffer >= 2.0) {
                    snapBuffer = 2.0;
                    flag(V.write(verbose()).str("aura snaps=" + jumpCount + " quads=" + quadrantsHit));
                }
            } else {
                snapBuffer = Math.max(0, snapBuffer - 0.15);
            }
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            return;
        }
        if (!player.cameraEntity.isSelf() || player.inVehicle() || player.gamemode == GameMode.SPECTATOR) {
            return;
        }

        WrapperPlayClientPlayerBlockPlacement place = new WrapperPlayClientPlayerBlockPlacement(event);
        if (place.getFace() == BlockFace.OTHER) {
            return;
        }

        Vector3i pos = place.getBlockPosition();
        if (pos.equals(lastInteractPos)) {
            return;
        }
        lastInteractPos = pos;

        long now = System.currentTimeMillis();
        while (!interactWindow.isEmpty() && now - interactWindow.peekFirst().timeMs > 1100L) {
            interactWindow.pollFirst();
        }

        double eyeY = player.y + 1.62;
        double dx = (pos.x + 0.5) - player.x;
        double dy = (pos.y + 0.5) - eyeY;
        double dz = (pos.z + 0.5) - player.z;
        double horizSq = dx * dx + dz * dz;
        double distSq = horizSq + dy * dy;

        if (distSq < 1.44) {
            return;
        }

        double invDist = 1.0 / Math.sqrt(distSq);
        double ndx = dx * invDist;
        double ndy = dy * invDist;
        double ndz = dz * invDist;
        int quadrant = horizSq >= 0.45 ? ((dx >= 0 ? 1 : 0) | (dz >= 0 ? 2 : 0)) : -1;
        boolean largeJump = false;
        if (hasLastInteractDir) {
            double dot = ndx * lastInteractDirX + ndy * lastInteractDirY + ndz * lastInteractDirZ;
            if (dot < 0.342) {
                largeJump = true;
            }
        }
        lastInteractDirX = ndx;
        lastInteractDirY = ndy;
        lastInteractDirZ = ndz;
        hasLastInteractDir = true;

        interactWindow.addLast(new Sample(now, pos.x, pos.y, pos.z, ndx, ndy, ndz, quadrant, largeJump));

        if (player.isTickingReliablyFor(5) && interactWindow.size() >= 10) {
            int jumpCount = 0;
            int quadrantMask = 0;
            for (Sample s : interactWindow) {
                if (s.largeJump) jumpCount++;
                if (s.quadrant >= 0) quadrantMask |= (1 << s.quadrant);
            }
            int quadrantsHit = Integer.bitCount(quadrantMask);
            if (jumpCount >= 7 && quadrantsHit >= 3) {
                if (shouldModifyPackets()) {
                    event.setCancelled(true);
                    player.onPacketCancel();
                }
                if (++interactAuraBuffer >= 2.0) {
                    interactAuraBuffer = 2.0;
                    flag(V.write(verbose()).str("interact-aura snaps=" + jumpCount + " quads=" + quadrantsHit));
                }
            } else {
                interactAuraBuffer = Math.max(0, interactAuraBuffer - 0.2);
            }
        }
    }

    private boolean isCompletelyBuriedByOpaqueBlocks(Vector3i target) {
        double eyeY = player.y + 1.62;
        double dx = (target.x + 0.5) - player.x;
        double dy = (target.y + 0.5) - eyeY;
        double dz = (target.z + 0.5) - player.z;
        // Exempt close blocks where player eye might clip or stand adjacent
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
        // Exclude Nexo custom block bases (NOTE_BLOCK, MUSHROOM_STEM, TRIPWIRE, CHORUS_PLANT) and partial/transparent blocks
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
