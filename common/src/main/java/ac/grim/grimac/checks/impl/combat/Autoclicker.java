package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PostPredictionCheck;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
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
    private boolean swungThisTick = false;

    private double cpsBuffer = 0;
    private double macroBuffer = 0;

    public Autoclicker(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING
                || event.getPacketType() == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT
                || event.getPacketType() == PacketType.Play.Client.USE_ITEM) {
            // Exempt mining (MaxiMine/MaxiFarm), block placing, and fishing rod / item interactions
            exemptDigTicks = 8;
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.ANIMATION) {
            WrapperPlayClientAnimation anim = new WrapperPlayClientAnimation(event);
            if (anim.getHand() == InteractionHand.MAIN_HAND
                    && exemptDigTicks == 0
                    && !player.packetOrderProcessor.isDigging()
                    && !player.packetOrderProcessor.isPlacing()
                    && !player.packetOrderProcessor.isUsing()
                    && !player.packetOrderProcessor.isDropping()) {
                swungThisTick = true;
                swingsInCurrentWindow++;
            }
        }
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (exemptDigTicks > 0) {
            exemptDigTicks--;
        }

        if (!player.isTickingReliablyFor(8)) {
            swungThisTick = false;
            swingsInCurrentWindow = 0;
            windowTicks = 0;
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
            // 1. Sustained impossible combat CPS (> 26 swings per 20 client ticks)
            if (swingsInCurrentWindow > 26) {
                if (++cpsBuffer >= 3.0) {
                    cpsBuffer = 3.0;
                    flag(V.write(verbose()).str("cps=" + swingsInCurrentWindow));
                }
            } else {
                cpsBuffer = Math.max(0, cpsBuffer - 0.5);
            }
            swingsInCurrentWindow = 0;
            windowTicks = 0;
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
        // Only check fast clicking (>= 8 CPS -> mean <= 2.5 ticks); skip 1.0 tick constant saturation (handled by CPS limit)
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

        // Hardware/software fixed-delay autoclickers produce near-zero tick variance (>= 95% identical tick spacing)
        if (stdDev < 0.12 && modeRatio >= 0.95) {
            intervalSamples.clear(); // Require 3 independent 40-click windows (120 robotic clicks) before flagging
            if (++macroBuffer >= 3.0) {
                macroBuffer = 3.0;
                flag(V.write(verbose()).str(String.format("macro std=%.3f mean=%.2ft", stdDev, mean)));
            }
        } else {
            macroBuffer = Math.max(0, macroBuffer - 0.35);
        }
    }
}
