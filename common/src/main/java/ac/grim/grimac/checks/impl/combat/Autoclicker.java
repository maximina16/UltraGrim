package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PostPredictionCheck;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAnimation;

import java.util.ArrayDeque;

@CheckData(name = "Autoclicker", stableKey = "grim.combat.autoclicker", description = "Sustained impossible click speed or zero-variance click macro")
public class Autoclicker extends Check implements PostPredictionCheck {
    private static final Verbose V = Verbose.of("{str}");

    private final ArrayDeque<Integer> intervalSamples = new ArrayDeque<>(40);
    private int ticksSinceLastSwing = 0;
    private int exemptDigTicks = 0;
    private int swingsInCurrentWindow = 0;
    private int windowTicks = 0;
    private long windowStartMs = 0L;
    private boolean swungThisTick = false;

    private double cpsBuffer = 0;
    private double macroBuffer = 0;

    public Autoclicker(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Client.PLAYER_DIGGING
                || type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT
                || type == PacketType.Play.Client.USE_ITEM) {
            exemptDigTicks = 8;
            return;
        }

        if (type == PacketType.Play.Client.ANIMATION) {
            WrapperPlayClientAnimation anim = new WrapperPlayClientAnimation(event);
            if (anim.getHand() == InteractionHand.MAIN_HAND
                    && exemptDigTicks == 0
                    && !player.packetOrderProcessor.isDigging()
                    && !player.packetOrderProcessor.isPlacing()
                    && !player.packetOrderProcessor.isUsing()
                    && !player.packetOrderProcessor.isDropping()) {
                swungThisTick = true;
                if (windowStartMs == 0L) {
                    windowStartMs = System.currentTimeMillis();
                }
                swingsInCurrentWindow++;
            }
            return;
        }

        if (!player.cameraEntity.isSelf()) {
            return;
        }

        if (isTickPacket(type) || type == PacketType.Play.Client.CLIENT_TICK_END) {
            onClientTick();
        }
    }

    private void onClientTick() {
        if (exemptDigTicks > 0) {
            exemptDigTicks--;
        }

        if (!player.isTickingReliablyFor(8) || player.packetStateData.lastPacketWasTeleport) {
            swungThisTick = false;
            swingsInCurrentWindow = 0;
            windowTicks = 0;
            windowStartMs = 0L;
            return;
        }

        ticksSinceLastSwing++;
        windowTicks++;

        if (swungThisTick) {
            swungThisTick = false;
            if (ticksSinceLastSwing >= 1 && ticksSinceLastSwing <= 6) {
                if (intervalSamples.size() >= 40) {
                    intervalSamples.pollFirst();
                }
                intervalSamples.addLast(ticksSinceLastSwing);

                if (intervalSamples.size() == 40) {
                    checkRoboticIntervals();
                }
            } else if (ticksSinceLastSwing > 20) {
                intervalSamples.clear();
            }
            ticksSinceLastSwing = 0;
        }

        if (windowTicks >= 20) {
            long now = System.currentTimeMillis();
            long elapsedMs = windowStartMs > 0L ? (now - windowStartMs) : 0L;
            // Only evaluate when 20 client ticks genuinely span ~1 second (850..1450ms);
            // rejects TCP lag bursts (<850ms) and stationary/pausing accumulation (>1450ms).
            if (elapsedMs >= 850L && elapsedMs <= 1450L && swingsInCurrentWindow > 28) {
                if (++cpsBuffer >= 3.0) {
                    cpsBuffer = 3.0;
                    flag(V.write(verbose()).str("cps=" + swingsInCurrentWindow));
                }
            } else if (elapsedMs >= 850L) {
                cpsBuffer = Math.max(0, cpsBuffer - 0.5);
            }
            swingsInCurrentWindow = 0;
            windowTicks = 0;
            windowStartMs = now;
        }
    }

    private void checkRoboticIntervals() {
        double sum = 0;
        int[] counts = new int[7];
        int maxCount = 0;
        for (int dt : intervalSamples) {
            sum += dt;
            if (dt >= 1 && dt <= 6) {
                counts[dt]++;
                if (counts[dt] > maxCount) {
                    maxCount = counts[dt];
                }
            }
        }

        double mean = sum / 40.0;
        if (mean > 2.5 || mean <= 1.02) {
            macroBuffer = Math.max(0, macroBuffer - 0.25);
            return;
        }

        double varSum = 0;
        for (int dt : intervalSamples) {
            double d = dt - mean;
            varSum += d * d;
        }
        double stdDev = Math.sqrt(varSum / 40.0);
        double modeRatio = maxCount / 40.0;

        if (stdDev < 0.12 && modeRatio >= 0.95) {
            intervalSamples.clear();
            if (++macroBuffer >= 3.0) {
                macroBuffer = 3.0;
                flag(V.write(verbose()).str(String.format("macro std=%.3f mean=%.2ft", stdDev, mean)));
            }
        } else {
            macroBuffer = Math.max(0, macroBuffer - 0.35);
        }
    }
}
