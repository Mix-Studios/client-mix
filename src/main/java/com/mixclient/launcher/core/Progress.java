package com.mixclient.launcher.core;

/** Progress callback for long install/launch operations. Always called off the UI thread. */
@FunctionalInterface
public interface Progress {
    /**
     * @param message  short status line for the user
     * @param fraction 0.0 - 1.0, or a negative value for an indeterminate step
     */
    void update(String message, double fraction);
}
