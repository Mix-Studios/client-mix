package com.mixclient.launcher.auth;

/** The credentials the game needs on its command line. */
public record PlayerSession(String username, String uuid, String accessToken, String xuid) {
}
