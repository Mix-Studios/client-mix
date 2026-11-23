package com.mixclient.launcher.auth;

import net.raphimc.minecraftauth.java.JavaAuthManager;
import net.raphimc.minecraftauth.java.model.MinecraftProfile;
import net.raphimc.minecraftauth.util.holder.listener.BasicChangeListener;

/** One saved Microsoft account: the MinecraftAuth manager plus what the UI needs to show it. */
public final class Account {

    private final JavaAuthManager manager;
    private String name;
    private String uuid;
    private BasicChangeListener saveListener;

    Account(JavaAuthManager manager, String name, String uuid) {
        this.manager = manager;
        this.name = name == null ? "" : name;
        this.uuid = uuid == null ? "" : uuid;
        applyCachedProfile();
    }

    public JavaAuthManager manager() {
        return manager;
    }

    public String name() {
        return name == null || name.isBlank() ? "Unknown player" : name;
    }

    /** Undashed UUID, as used on the Minecraft command line. */
    public String uuid() {
        return uuid == null ? "" : uuid;
    }

    public String initial() {
        String n = name();
        return n.isEmpty() ? "?" : n.substring(0, 1).toUpperCase();
    }

    void applyCachedProfile() {
        MinecraftProfile cached = manager.getMinecraftProfile().getCached();
        if (cached != null) {
            name = cached.getName();
            uuid = cached.getId().toString().replace("-", "");
        }
    }

    /** Called with a fresh profile to keep the display name and uuid current. */
    void update(MinecraftProfile profile) {
        if (profile == null) {
            return;
        }
        name = profile.getName();
        uuid = profile.getId().toString().replace("-", "");
    }

    /**
     * Re-saves the account file whenever MinecraftAuth refreshes a token, so {@code accounts.json}
     * never holds an expired refresh token after a successful background refresh.
     */
    void onTokenChange(Runnable save) {
        detach();
        saveListener = save::run;
        manager.getChangeListeners().add(saveListener);
    }

    void detach() {
        if (saveListener != null) {
            manager.getChangeListeners().remove(saveListener);
            saveListener = null;
        }
    }

    boolean hasUsableToken() {
        return manager.getMinecraftToken().hasValue() && !manager.getMinecraftToken().isExpired();
    }
}
