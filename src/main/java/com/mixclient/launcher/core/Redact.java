package com.mixclient.launcher.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Keeps credentials out of everything the launcher shows or writes.
 *
 * <p>The launch command carries the Minecraft access token, the legacy session id and the Xbox
 * user id, and the game itself logs {@code (Session ID is token:...)} to its output, which the
 * launcher copies into its log panel. Those lines are what players paste into support threads, so
 * they are masked before they reach the panel or {@code launcher.log}.</p>
 */
public final class Redact {

    public static final String MASK = "<redacted>";

    /** Command-line options whose next argument is a credential. */
    private static final Set<String> FLAGS = Set.of(
            "--accessToken", "--session", "--xuid",
            "--auth_access_token", "--auth_session", "--auth_xuid");

    /** Credential options inside a quoted command line, e.g. {@code --xuid 2535}. */
    private static final Pattern FLAG_VALUE = Pattern.compile(
            "(?i)(--(?:accessToken|session|xuid|auth_access_token|auth_session|auth_xuid)(?:=|\\s+))"
                    + "([^\\s\"',)]+)");

    /** {@code token:<access token>:<uuid>} is the session line Minecraft prints. */
    private static final Pattern SESSION_TOKEN = Pattern.compile("(token:)[^\\s:\"',)]+");

    /** A Microsoft access token is a JWT; catch copies that are not next to a known name. */
    private static final Pattern JWT =
            Pattern.compile("eyJ[A-Za-z0-9_-]{8,}(?:\\.[A-Za-z0-9_-]{8,}){1,2}");

    /** {@code accessToken}, {@code access_token} and {@code xuid} in JSON, properties or URLs. */
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)(\"?(?:access_?token|xuid)\"?\\s*[=:]\\s*\"?)([^\\s\"',}&]+)");

    private Redact() {
    }

    /** Masks credential values in one line of free text; null stays null. */
    public static String line(String text) {
        if (text == null) {
            return null;
        }
        String result = FLAG_VALUE.matcher(text).replaceAll("$1" + MASK);
        result = SESSION_TOKEN.matcher(result).replaceAll("$1" + MASK);
        result = KEY_VALUE.matcher(result).replaceAll("$1" + MASK);
        result = JWT.matcher(result).replaceAll(MASK);
        return result;
    }

    /**
     * Masks the values of credential options, so a logged command line can be pasted into a bug
     * report without leaking the token.
     */
    public static List<String> command(List<String> command) {
        List<String> result = new ArrayList<>(command.size());
        for (int i = 0; i < command.size(); i++) {
            String argument = command.get(i);
            if (FLAGS.contains(argument) && i + 1 < command.size()) {
                result.add(argument);
                result.add(MASK);
                i++;
                continue;
            }
            int equals = argument.startsWith("--") ? argument.indexOf('=') : -1;
            if (equals > 0 && FLAGS.contains(argument.substring(0, equals))) {
                result.add(argument.substring(0, equals) + "=" + MASK);
            } else {
                result.add(argument);
            }
        }
        return result;
    }
}
