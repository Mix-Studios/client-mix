package dev.cloudmc.helpers;

import dev.cloudmc.Cloud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

/**
 * Scaled screen size for HUD math.
 *
 * <p>This used to build a new {@link ScaledResolution} on every call, and the HUD asks for the
 * size many times per frame while re-rendering every element. The object is only rebuilt when the
 * window or GUI scale actually changes, which keeps the per-frame allocation out of the render
 * loop without changing any of the values the HUD sees.</p>
 */
public class ResolutionHelper {

    private static ScaledResolution scaledResolution;
    private static int cachedWidth = -1;
    private static int cachedHeight = -1;
    private static int cachedScale = -1;

    private static ScaledResolution resolution() {
        Minecraft mc = Cloud.INSTANCE.mc;

        if (scaledResolution == null
                || mc.displayWidth != cachedWidth
                || mc.displayHeight != cachedHeight
                || mc.gameSettings.guiScale != cachedScale) {
            scaledResolution = new ScaledResolution(mc);
            cachedWidth = mc.displayWidth;
            cachedHeight = mc.displayHeight;
            cachedScale = mc.gameSettings.guiScale;
        }

        return scaledResolution;
    }

    public static int getHeight() {
        return resolution().getScaledHeight();
    }

    public static int getWidth() {
        return resolution().getScaledWidth();
    }

    public static int getFactor() {
        return resolution().getScaleFactor();
    }
}
