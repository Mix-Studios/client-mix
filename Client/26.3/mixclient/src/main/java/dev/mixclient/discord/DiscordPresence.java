package dev.mixclient.discord;

import dev.mixclient.MixClientMod;
import dev.mixclient.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.time.Instant;

/**
 * Keeps Discord showing what the player is doing in MixClient.
 *
 * <p>The activity is only rewritten when it changes, once every two seconds, so the game loop
 * pays almost nothing for it. The large image key is {@code mixclient}; uploading an image with
 * that name to the Discord application's Rich Presence assets makes it show up.</p>
 */
public final class DiscordPresence implements AutoCloseable {

    private static final int TICKS_BETWEEN_UPDATES = 40;
    private static final long RECONNECT_DELAY_MS = 15_000L;

    private final String appId;
    private final long startedAt = Instant.now().getEpochSecond();

    private DiscordIpc ipc;
    private long lastConnectAttempt;
    private String lastActivity;
    private int ticks;

    public DiscordPresence(ClientConfig config) {
        this.appId = config.discordAppId;
    }

    public boolean isEnabled() {
        return appId != null && !appId.isBlank();
    }

    public void tick(Minecraft client) {
        if (!isEnabled() || ++ticks % TICKS_BETWEEN_UPDATES != 0) {
            return;
        }

        ensureConnected();
        if (ipc == null) {
            return;
        }

        String activity = activityFor(client);
        if (!activity.equals(lastActivity)) {
            ipc.setActivity(activity);
            lastActivity = activity;
        }
    }

    private void ensureConnected() {
        if (ipc != null && ipc.isOpen()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastConnectAttempt < RECONNECT_DELAY_MS) {
            return;
        }

        lastConnectAttempt = now;
        ipc = DiscordIpc.connect(appId);
        lastActivity = null;
    }

    private String activityFor(Minecraft client) {
        String state;
        if (client.level == null) {
            state = "In the menus";
        } else if (client.isLocalServer()) {
            state = "Singleplayer";
        } else {
            state = "Playing on " + serverLabel(client);
        }

        return "{\"details\":\"MixClient " + MixClientMod.VERSION + "\""
                + ",\"state\":\"" + escape(state) + "\""
                + ",\"timestamps\":{\"start\":" + startedAt + "}"
                + ",\"assets\":{\"large_image\":\"mixclient\",\"large_text\":\"MixClient\"}"
                + ",\"instance\":false}";
    }

    private String serverLabel(Minecraft client) {
        ServerData server = client.getCurrentServer();
        if (server == null) {
            return "a server";
        }
        String address = server.ip;
        return address == null || address.isBlank() ? server.name : address;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public void close() {
        if (ipc != null) {
            ipc.clearActivity();
            ipc.close();
            ipc = null;
        }
    }
}
