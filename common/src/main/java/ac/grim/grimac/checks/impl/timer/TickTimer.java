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

@CheckData(name = "TickTimer", stableKey = "grim.timer.tick", description = "Did not send client tick end packet", setback = -1)
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
                if (activeTick && ++buffer >= 16.0) {
                    buffer = 12.0;
                    flag(V.write(verbose()).bool(false).uint(flyingPackets));
                }
            } else {
                buffer = Math.max(0, buffer - 0.5);
            }
            receivedTickEnd = false;
            flyingPackets++;
        } else if (event.getPacketType() == PacketType.Play.Client.CLIENT_TICK_END) {
            receivedTickEnd = true;
            // Allow 2 batched flying packets per tick end on ViaVersion/1.21.4 lag recovery without flagging
            if (flyingPackets > 2) {
                if (++buffer >= 16.0) {
                    buffer = 12.0;
                    flag(V.write(verbose()).bool(true).uint(flyingPackets));
                }
            } else {
                buffer = Math.max(0, buffer - 0.5);
            }
            flyingPackets = 0;
        }
    }
}
