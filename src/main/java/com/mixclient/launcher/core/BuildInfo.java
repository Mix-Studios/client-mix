package com.mixclient.launcher.core;

/** Version of the running launcher, taken from the jar manifest so one release is one number. */
public final class BuildInfo {

    private BuildInfo() {
    }

    public static final String VERSION = read();

    private static String read() {
        String version = BuildInfo.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "1.0.0" : version;
    }
}
