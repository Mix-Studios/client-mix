package dev.mixclient.performance;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.server.level.ParticleStatus;

/**
 * The FPS-oriented defaults MixClient applies once the client is up.
 *
 * <p>Only settings that cost frames and are barely visible are touched: entity shadows do a
 * second render pass for every living entity, and the full particle set is the single biggest
 * per-frame draw on busy servers. Everything else stays exactly as the player configured it in
 * the vanilla options screen, and the tweaks can be turned off in {@code config/mixclient.json}.</p>
 */
public final class Performance {

    private Performance() {
    }

    public static void apply(Minecraft client) {
        Options options = client.options;

        options.entityShadows().set(false);
        options.particles().set(ParticleStatus.MINIMAL);

        System.out.println("[MixClient] performance tweaks applied");
    }
}
