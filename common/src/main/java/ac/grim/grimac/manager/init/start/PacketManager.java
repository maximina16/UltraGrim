package ac.grim.grimac.manager.init.start;

import ac.grim.grimac.events.packets.*;
import ac.grim.grimac.events.packets.worldreader.BasePacketWorldReader;
import ac.grim.grimac.events.packets.worldreader.PacketWorldReaderEight;
import ac.grim.grimac.events.packets.worldreader.PacketWorldReaderEighteen;
import ac.grim.grimac.utils.anticheat.LogUtil;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.manager.server.ServerVersion;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class PacketManager implements StartableInitable {
    private static final List<PacketListenerCommon> REGISTERED_LISTENERS = new CopyOnWriteArrayList<>();

    private static void register(PacketListenerCommon listener) {
        PacketEvents.getAPI().getEventManager().registerListener(listener);
        REGISTERED_LISTENERS.add(listener);
    }

    public static void unregisterAllListeners() {
        for (PacketListenerCommon listener : REGISTERED_LISTENERS) {
            try {
                PacketEvents.getAPI().getEventManager().unregisterListener(listener);
            } catch (Throwable ignored) {
            }
        }
        REGISTERED_LISTENERS.clear();
    }

    @Override
    public void start() {
        unregisterAllListeners();
        LogUtil.info("Registering packets...");

        register(new PacketPlayerJoinQuit());
        register(new PacketPingListener());
        register(new PacketPlayerWindow());
        register(new PacketPlayerDigging());
        register(new PacketPlayerAttack());
        register(new PacketEntityAction());
        register(new PacketBlockAction());
        register(new PacketSelfMetadataListener());
        register(new BedStateTracker());
        register(new PacketServerTeleport());
        register(new PacketPlayerCooldown());
        register(new PacketPlayerRespawn());
        register(new PacketPlayerTick());
        register(new PreViaCheckManagerListener());
        register(new CheckManagerListener());
        register(new PacketPlayerSteer());
        register(new PacketPluginMessage());

        if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_13)) {
            register(new PacketServerTags());
        }

        if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_18)) {
            register(new PacketWorldReaderEighteen());
        } else if (PacketEvents.getAPI().getServerManager().getVersion().isOlderThanOrEquals(ServerVersion.V_1_8_8)) {
            register(new PacketWorldReaderEight());
        } else {
            register(new BasePacketWorldReader());
        }

        register(new ProxyAlertMessenger());
        register(new PacketHidePlayerInfo());

        PacketEvents.getAPI().init();
    }
}
