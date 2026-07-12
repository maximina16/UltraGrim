package ac.grim.grimac.manager.init.stop;

import ac.grim.grimac.utils.common.BuildProperties;
import ac.grim.grimac.utils.anticheat.LogUtil;
import com.github.retrooper.packetevents.PacketEvents;

public class TerminatePacketEvents implements StoppableInitable {
    @Override
    public void stop() {
        if (!BuildProperties.shadesPacketEvents()) {
            return;
        }
        LogUtil.info("Terminating PacketEvents...");
        PacketEvents.getAPI().terminate();
    }
}
