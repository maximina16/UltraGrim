package ac.grim.grimac.checks.impl.timer;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketCheck;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;

import static com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying.isFlying;

@CheckData(name = "TickTimer", stableKey = "grim.timer.tick", description = "Did not send client tick end packet", setback = 8)
public class TickTimer extends Check implements PacketCheck {
    private static final Verbose V = Verbose.of("type=[end|flying], packets={uint}");

    private boolean receivedTickEnd = true;
    private int flyingPackets = 0;
    private double buffer = 0;

    public TickTimer(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!player.supportsEndTick()) return;
        if (isFlying(event.getPacketType()) && !player.packetStateData.lastPacketWasTeleport && !player.packetStateData.lastPacketWasOnePointSeventeenDuplicate) {
            WrapperPlayClientPlayerFlying flying = new WrapperPlayClientPlayerFlying(event);
            boolean activeTick = flying.hasPositionChanged()
                    || player.packetOrderProcessor.isDigging()
                    || player.packetOrderProcessor.isAttacking()
                    || player.packetOrderProcessor.isPlacing();

            if (!receivedTickEnd) {
                // Exempt stationary idle ticks (e.g. CraftPixel Launcher headless AFK client / tab-out)
                // which cannot gain any movement or combat advantage; Timer check still bounds packet rate.
                if (activeTick && ++buffer >= 6.0) {
                    buffer = 6.0;
                    if (flagWithSetback(V.write(verbose()).bool(false).uint(flyingPackets))) {
                        handleViolation();
                    }
                }
            } else {
                buffer = Math.max(0, buffer - 0.5);
            }
            receivedTickEnd = false;
            flyingPackets++;
        } else if (event.getPacketType() == PacketType.Play.Client.CLIENT_TICK_END) {
            receivedTickEnd = true;
            if (flyingPackets > 1) {
                if (++buffer >= 6.0) {
                    buffer = 6.0;
                    if (flagWithSetback(V.write(verbose()).bool(true).uint(flyingPackets))) {
                        handleViolation();
                    }
                }
            } else {
                buffer = Math.max(0, buffer - 0.5);
            }
            flyingPackets = 0;
        }
    }

    private void handleViolation() {
        // Although we don't cancel the packet, this should be counted as an invalid packet.
        player.onPacketCancel();
    }
}
