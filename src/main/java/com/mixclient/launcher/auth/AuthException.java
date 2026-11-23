package com.mixclient.launcher.auth;

/** A sign-in / token failure with a message that is safe and useful to show in the UI. */
public class AuthException extends Exception {

    public enum Kind {
        /** The user closed the sign-in dialog or the login thread was interrupted. */
        CANCELLED,
        /** The device code expired (or the login timed out) before it was used. */
        CODE_EXPIRED,
        /** The account is valid but does not own Minecraft: Java Edition. */
        NOT_OWNED,
        /** Microsoft/Mojang could not be reached. */
        NETWORK,
        OTHER
    }

    private final Kind kind;

    public AuthException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public AuthException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public boolean isCancelled() {
        return kind == Kind.CANCELLED;
    }
}
