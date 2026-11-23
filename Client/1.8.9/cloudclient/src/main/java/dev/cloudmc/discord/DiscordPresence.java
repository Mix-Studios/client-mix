package dev.cloudmc.discord;

import dev.cloudmc.Cloud;
import dev.cloudmc.helpers.OSHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Keeps Discord showing what the player is doing in MixClient.
 *
 * <p>The activity is only rewritten when it changes, once every two seconds, so the game loop
 * pays almost nothing for it. Discord needs an application id before it will accept a presence:
 * set one in {@code .minecraft/mixclient/discord.txt} (a single line with the id) or in the
 * {@code MIXCLIENT_DISCORD_APP_ID} environment variable. Without an id the presence stays off.
 * Uploading a Rich Presence asset named {@code mixclient} in that Discord application makes the
 * logo show up next to the activity.</p>
 */
public class DiscordPresence {

    private static final int TICKS_BETWEEN_UPDATES = 40;
    private static final long RECONNECT_DELAY_MS = 15_000L;
    private static final String CONFIG_FILE = "discord.txt";

    private final String appId;
    private final long startedAt = System.currentTimeMillis() / 1000L;

    private DiscordIpc ipc;
    private long lastConnectAttempt;
    private String lastActivity;
    private int ticks;

    public DiscordPresence() {
        this.appId = resolveAppId();
    }

    private static String resolveAppId() {
        String fromEnv = System.getenv("MIXCLIENT_DISCORD_APP_ID");
        if (fromEnv != null && !fromEnv.trim().isEmpty()) {
            return fromEnv.trim();
        }

        try {
            File file = new File(OSHelper.getClientDirectory() + CONFIG_FILE);
            if (file.exists()) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        Files.newInputStream(file.toPath()), StandardCharsets.UTF_8));
                try {
                    String line = reader.readLine();
                    if (line != null && !line.trim().isEmpty()) {
                        return line.trim();
                    }
                } finally {
                    reader.close();
                }
            }
        } catch (Exception e) {
            System.out.println("[MixClient] Could not read " + CONFIG_FILE + ": " + e.getMessage());
        }

        return "";
    }

    public boolean isEnabled() {
        return !appId.isEmpty();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (!isEnabled() || event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++ticks % TICKS_BETWEEN_UPDATES != 0) {
            return;
        }

        ensureConnected();
        if (ipc == null) {
            return;
        }

        String activity = activityFor(Cloud.INSTANCE.mc);
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

    private String activityFor(Minecraft mc) {
        String state;
        if (mc.theWorld == null) {
            state = "In the menus";
        } else if (mc.isSingleplayer()) {
            state = "Singleplayer";
        } else {
            ServerData server = mc.getCurrentServerData();
            state = "Playing on " + (server != null && server.serverIP != null && !server.serverIP.isEmpty()
                    ? server.serverIP
                    : "a server");
        }

        return "{\"details\":\"MixClient " + Cloud.modVersion + "\""
                + ",\"state\":\"" + escape(state) + "\""
                + ",\"timestamps\":{\"start\":" + startedAt + "}"
                + ",\"assets\":{\"large_image\":\"mixclient\",\"large_text\":\"MixClient\"}"
                + ",\"instance\":false}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Takes the presence down when the game closes. */
    public void shutdown() {
        if (ipc != null) {
            ipc.clearActivity();
            ipc.close();
            ipc = null;
        }
    }
}
