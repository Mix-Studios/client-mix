package dev.mixclient;

import dev.mixclient.config.ClientConfig;
import dev.mixclient.discord.DiscordPresence;
import dev.mixclient.performance.Performance;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

/** Client entrypoint: Discord presence and the performance options. */
public final class MixClientMod implements ClientModInitializer {

    public static final String MOD_ID = "mixclient";
    public static final String NAME = "MixClient";
    public static final String VERSION = "1.0.0";

    @Override
    public void onInitializeClient() {
        ClientConfig config = ClientConfig.load();

        DiscordPresence presence = new DiscordPresence(config);
        ClientTickEvents.END_CLIENT_TICK.register(presence::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> presence.close());

        if (config.performanceTweaks) {
            ClientLifecycleEvents.CLIENT_STARTED.register(Performance::apply);
        }
    }
}
