package com.mixclient.launcher.auth;

import net.lenni0451.commons.httpclient.exceptions.HttpRequestException;
import net.raphimc.minecraftauth.java.exception.MinecraftProfileNotFoundException;
import net.raphimc.minecraftauth.util.http.exception.ApiHttpRequestException;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 * Turns whatever MinecraftAuth throws into a message a player can act on.
 *
 * <p>Microsoft returns machine-readable {@code error} codes for the device-code flow
 * ({@code authorization_declined}, {@code bad_verification_code}, {@code expired_token}) and
 * numeric XSTS codes for Xbox Live problems, so those are mapped to plain English here.</p>
 */
public final class AuthErrors {

    private AuthErrors() {
    }

    public static AuthException map(Throwable error) {
        if (error instanceof AuthException authException) {
            return authException;
        }

        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof InterruptedException) {
                return new AuthException(AuthException.Kind.CANCELLED, "Sign-in was cancelled.", error);
            }
            if (t instanceof TimeoutException) {
                return new AuthException(AuthException.Kind.CODE_EXPIRED,
                        "The sign-in code expired before it was used. Sign in again to get a fresh code.", error);
            }
            if (t instanceof MinecraftProfileNotFoundException) {
                return new AuthException(AuthException.Kind.NOT_OWNED,
                        "This Microsoft account doesn't own Minecraft: Java Edition.", error);
            }
            if (t instanceof ApiHttpRequestException apiError) {
                AuthException mapped = mapMicrosoftError(apiError, error);
                if (mapped != null) {
                    return mapped;
                }
            }
            if (t instanceof IOException) {
                return new AuthException(AuthException.Kind.NETWORK,
                        "Couldn't reach Microsoft or Mojang. Check your internet connection and try again.", error);
            }
            if (t instanceof HttpRequestException) {
                return new AuthException(AuthException.Kind.NETWORK,
                        "Microsoft or Mojang rejected the request. Try again in a moment.", error);
            }
        }

        String message = error.getMessage();
        return new AuthException(AuthException.Kind.OTHER,
                message == null || message.isBlank() ? "Sign-in failed for an unknown reason." : message, error);
    }

    private static AuthException mapMicrosoftError(ApiHttpRequestException apiError, Throwable root) {
        String code = apiError.getError() == null ? "" : apiError.getError().trim();
        String apiMessage = apiError.getErrorMessage();

        switch (code) {
            case "authorization_declined":
                return new AuthException(AuthException.Kind.CANCELLED,
                        "Sign-in was declined in the browser.", root);
            case "bad_verification_code":
                return new AuthException(AuthException.Kind.OTHER,
                        "That verification code wasn't accepted. Sign in again to get a new one.", root);
            case "expired_token":
            case "code_expired":
                return new AuthException(AuthException.Kind.CODE_EXPIRED,
                        "The sign-in code expired. Sign in again to get a fresh code.", root);
            // XSTS / Xbox Live error codes.
            case "2148916233":
                return new AuthException(AuthException.Kind.OTHER,
                        "This Microsoft account has no Xbox profile. Create one at xbox.com, then sign in again.", root);
            case "2148916235":
                return new AuthException(AuthException.Kind.OTHER,
                        "Xbox Live isn't available in this account's country or region.", root);
            case "2148916238":
                return new AuthException(AuthException.Kind.OTHER,
                        "This is a child account. An adult in the Microsoft family group has to add it first.", root);
            default:
                break;
        }

        if (!code.isEmpty()) {
            String detail = apiMessage == null || apiMessage.isBlank() ? "" : " (" + apiMessage + ")";
            return new AuthException(AuthException.Kind.OTHER,
                    "Microsoft rejected the sign-in: " + code + detail, root);
        }
        return null;
    }
}
