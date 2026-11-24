package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PostPredictionCheck;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;

import java.util.ArrayDeque;

@CheckData(name = "Killaura", stableKey = "grim.combat.killaura", description = "Automated multi-target switch aura, silent snap-back aim, or invalid combat state")
public class Killaura extends Check implements PostPredictionCheck {
    private static final Verbose V = Verbose.of("{str}");

    private record TargetHit(long timeMs, int entityId, double dirX, double dirY, double dirZ) {}

    private final ArrayDeque<TargetHit> recentTargets = new ArrayDeque<>(12);

    private boolean attackedThisTick = false;
    private int attackedEntityThisTick = -1;

    // Silent-Aim Snap-Back tracking: preYaw -> big snap (>48°) + attack -> immediate snap back within 2.0° of preYaw
    private boolean pendingSnapBack = false;
    private float preAttackYaw = 0f;
    private float attackYaw = 0f;

    private double switchBuffer = 0;
    private double snapBackBuffer = 0;
    private double invAttackBuffer = 0;

    public Killaura(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!player.cameraEntity.isSelf() || player.inVehicle()) {
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
            if (packet.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                handleAttack(event, packet.getEntityId());
            }
        } else if (event.getPacketType() == PacketType.Play.Client.ATTACK) {
            WrapperPlayClientAttack packet = new WrapperPlayClientAttack(event);
            handleAttack(event, packet.getEntityId());
        }
    }

    private void handleAttack(PacketReceiveEvent event, int entityId) {
        // 1. Attacking while actively clicking inside an inventory window in the same tick (AutoAttack / InvAura)
        if (player.packetOrderProcessor.isClickingInInventory()) {
            if (shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
            if (++invAttackBuffer >= 2.0) {
                invAttackBuffer = 2.0;
                flag(V.write(verbose()).str("inventory-attack entity=" + entityId));
            }
            return;
        } else {
            invAttackBuffer = Math.max(0, invAttackBuffer - 0.2);
        }

        attackedThisTick = true;
        attackedEntityThisTick = entityId;

        // 2. High-Angle Multi-Target SwitchAura (>= 4 distinct entities with >65° target switches within 350ms)
        // Server-side AoE skills (MaxiArcane, MaxiClass, MMOItems, Sweeping Edge) only send 1 client attack packet,
        // so legitimate AoE skills never trigger this check.
        PacketEntity target = player.compensatedEntities.entityMap.get(entityId);
        if (target == null || target.isDead || !player.isTickingReliablyFor(4)) {
            return;
        }

        var box = target.getPossibleCollisionBoxes();
        double tx = (box.minX + box.maxX) * 0.5 - player.x;
        double ty = (box.minY + box.maxY) * 0.5 - (player.y + 1.62);
        double tz = (box.minZ + box.maxZ) * 0.5 - player.z;
        double distSq = tx * tx + ty * ty + tz * tz;
        if (distSq < 0.64) {
            return; // Stacked/overlapping mob spawner pile right inside player — skip angular switch check
        }

        double inv = 1.0 / Math.sqrt(distSq);
        double ndx = tx * inv;
        double ndy = ty * inv;
        double ndz = tz * inv;

        long now = System.currentTimeMillis();
        while (!recentTargets.isEmpty() && now - recentTargets.peekFirst().timeMs > 380L) {
            recentTargets.pollFirst();
        }

        if (recentTargets.isEmpty() || recentTargets.peekLast().entityId != entityId) {
            recentTargets.addLast(new TargetHit(now, entityId, ndx, ndy, ndz));
        }

        if (recentTargets.size() >= 4) {
            int wideSwitches = 0;
            TargetHit prev = null;
            java.util.HashSet<Integer> uniqueIds = new java.util.HashSet<>(8);
            for (TargetHit hit : recentTargets) {
                uniqueIds.add(hit.entityId);
                if (prev != null && prev.entityId != hit.entityId) {
                    double dot = prev.dirX * hit.dirX + prev.dirY * hit.dirY + prev.dirZ * hit.dirZ;
                    if (dot < 0.422) { // > 65° switch between distinct entities
                        wideSwitches++;
                    }
                }
                prev = hit;
            }

            if (uniqueIds.size() >= 4 && wideSwitches >= 3) {
                if (shouldModifyPackets()) {
                    event.setCancelled(true);
                    player.onPacketCancel();
                }
                if (++switchBuffer >= 2.0) {
                    switchBuffer = 2.0;
                    flag(V.write(verbose()).str("switch-aura targets=" + uniqueIds.size() + " switches=" + wideSwitches));
                }
                recentTargets.clear();
            } else {
                switchBuffer = Math.max(0, switchBuffer - 0.15);
            }
        }
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (!player.cameraEntity.isSelf() || player.inVehicle() || player.packetStateData.lastPacketWasTeleport) {
            pendingSnapBack = false;
            attackedThisTick = false;
            return;
        }

        float deltaYaw = Math.abs(wrapDegrees(player.yaw - player.lastYaw));

        // Check if previous tick was a large snap-attack and this tick immediately snapped right back to preAttackYaw
        if (pendingSnapBack) {
            pendingSnapBack = false;
            float returnDelta = Math.abs(wrapDegrees(player.yaw - attackYaw));
            float diffFromOriginal = Math.abs(wrapDegrees(player.yaw - preAttackYaw));
            if (returnDelta >= 48.0f && diffFromOriginal <= 2.0f && player.isTickingReliablyFor(4)) {
                if (++snapBackBuffer >= 3.0) {
                    snapBackBuffer = 3.0;
                    flag(V.write(verbose()).str(String.format("silent-snap delta=%.1f return=%.1f", returnDelta, diffFromOriginal)));
                }
            } else {
                snapBackBuffer = Math.max(0, snapBackBuffer - 0.25);
            }
        }

        if (attackedThisTick && attackedEntityThisTick != -1 && deltaYaw >= 48.0f) {
            pendingSnapBack = true;
            preAttackYaw = player.lastYaw;
            attackYaw = player.yaw;
        }

        attackedThisTick = false;
        attackedEntityThisTick = -1;
    }

    private static float wrapDegrees(float value) {
        float v = value % 360.0f;
        if (v >= 180.0f) v -= 360.0f;
        if (v < -180.0f) v += 360.0f;
        return v;
    }
}
