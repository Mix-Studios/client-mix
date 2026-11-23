package com.mixclient.launcher.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mixclient.launcher.core.AppPaths;
import net.lenni0451.commons.httpclient.HttpClient;
import net.raphimc.minecraftauth.MinecraftAuth;
import net.raphimc.minecraftauth.java.JavaAuthManager;
import net.raphimc.minecraftauth.java.model.MinecraftProfile;
import net.raphimc.minecraftauth.java.model.MinecraftToken;
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;
import net.raphimc.minecraftauth.msa.service.impl.DeviceCodeMsaAuthService;
import net.raphimc.minecraftauth.msa.service.util.ParamMsaAuthServiceSupplier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Microsoft accounts and their persistence.
 *
 * <p>Uses {@code JavaAuthManager.create(...)} with no {@code msaApplicationConfig(...)} override:
 * MINECRAFTAUTH's default is the official Minecraft launcher client id
 * ({@value #DEFAULT_CLIENT_ID}) with the {@code MBI_SSL} title scope, so no Azure/Entra app
 * registration is involved.</p>
 *
 * <p>Everything is written to {@code accounts.json} in {@link AppPaths#appDir()} via
 * {@link JavaAuthManager#toJson(JavaAuthManager)}, and restored on the next start via
 * {@link JavaAuthManager#fromJson(HttpClient, JsonObject)}.</p>
 */
public final class Accounts {

    /** The official Minecraft launcher application id shipped by MinecraftAuth as the default. */
    public static final String DEFAULT_CLIENT_ID = "00000000402b5328";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int FORMAT_VERSION = 1;

    private final HttpClient httpClient = MinecraftAuth.createHttpClient("MixClient/1.0");
    private final Path accountsFile = AppPaths.accountsFile();
    private final Object lock = new Object();
    private final List<Account> accounts = new ArrayList<>();

    private Account selected;
    private volatile Runnable changeListener = () -> {
    };

    /** Called after any change (sign-in, sign-out, refresh). May fire on a background thread. */
    public void setChangeListener(Runnable listener) {
        this.changeListener = listener == null ? () -> {
        } : listener;
    }

    public List<Account> all() {
        synchronized (lock) {
            return List.copyOf(accounts);
        }
    }

    public Account selected() {
        synchronized (lock) {
            return selected;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return accounts.isEmpty();
        }
    }

    /** Restores every saved session; unreadable entries are skipped rather than failing startup. */
    public void load() {
        synchronized (lock) {
            accounts.clear();
            selected = null;

            if (!Files.exists(accountsFile)) {
                return;
            }

            try {
                String json = Files.readString(accountsFile, StandardCharsets.UTF_8);
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                JsonArray saved = root.has("accounts") ? root.getAsJsonArray("accounts") : new JsonArray();

                for (JsonElement element : saved) {
                    JsonObject entry = element.getAsJsonObject();
                    JsonObject auth = entry.has("auth") && entry.get("auth").isJsonObject()
                            ? entry.getAsJsonObject("auth")
                            : null;
                    if (auth == null) {
                        continue;
                    }
                    try {
                        JavaAuthManager manager = JavaAuthManager.fromJson(httpClient, auth);
                        Account account = new Account(manager,
                                entry.has("name") ? entry.get("name").getAsString() : "",
                                entry.has("id") ? entry.get("id").getAsString() : "");
                        account.onTokenChange(this::save);
                        accounts.add(account);
                    } catch (RuntimeException ex) {
                        System.err.println("[auth] Skipping unreadable account entry: " + ex);
                    }
                }

                String selectedId = root.has("selected") ? root.get("selected").getAsString() : "";
                for (Account account : accounts) {
                    if (account.uuid().equals(selectedId)) {
                        selected = account;
                        break;
                    }
                }
                if (selected == null && !accounts.isEmpty()) {
                    selected = accounts.get(0);
                }
            } catch (IOException | RuntimeException ex) {
                System.err.println("[auth] Could not read " + accountsFile + ": " + ex);
            }
        }
    }

    /**
     * Runs the device-code flow on the calling thread (call it from a worker thread).
     *
     * @param prompt receives the verification URL and user code; called on this thread.
     */
    public Account login(Consumer<MsaDeviceCode> prompt) throws AuthException {
        JavaAuthManager manager;
        try {
            manager = JavaAuthManager.create(httpClient).login(
                    (ParamMsaAuthServiceSupplier<Consumer<MsaDeviceCode>>) (http, config, consumer) ->
                            new DeviceCodeMsaAuthService(http, config, consumer),
                    prompt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw AuthErrors.map(e);
        } catch (Exception e) {
            throw AuthErrors.map(e);
        }

        Account account;
        try {
            // Fetching the profile is what proves the account owns Minecraft: Java Edition.
            MinecraftProfile profile = manager.getMinecraftProfile().getUpToDate();
            account = new Account(manager, profile.getName(), profile.getId().toString().replace("-", ""));
        } catch (Exception e) {
            throw AuthErrors.map(e);
        }

        synchronized (lock) {
            Account existing = findByUuid(account.uuid());
            if (existing != null) {
                existing.detach();
                accounts.remove(existing);
            }
            account.onTokenChange(this::save);
            accounts.add(account);
            selected = account;
            save();
        }
        notifyChanged();
        return account;
    }

    public void select(Account account) {
        synchronized (lock) {
            if (!accounts.contains(account)) {
                return;
            }
            selected = account;
            save();
        }
        notifyChanged();
    }

    public void signOut(Account account) {
        synchronized (lock) {
            account.detach();
            accounts.remove(account);
            if (selected == account) {
                selected = accounts.isEmpty() ? null : accounts.get(0);
            }
            save();
        }
        notifyChanged();
    }

    /**
     * Ensures the tokens needed to play are valid, refreshing them if they expired.
     * Called right before the game process starts.
     */
    public PlayerSession prepareLaunch(Account account) throws AuthException {
        if (account == null) {
            throw new AuthException(AuthException.Kind.NOT_OWNED, "Sign in with a Microsoft account first.");
        }
        try {
            account.manager().getMinecraftToken().refreshIfExpired();
            account.manager().getMinecraftProfile().refreshIfExpired();

            MinecraftToken token = account.manager().getMinecraftToken().getUpToDate();
            MinecraftProfile profile = account.manager().getMinecraftProfile().getUpToDate();
            account.update(profile);

            String xuid = "";
            try {
                xuid = account.manager().getXboxUserProfile().getUpToDate().getId();
            } catch (Exception ignored) {
                // Xuid is optional on the command line.
            }

            save();
            return new PlayerSession(profile.getName(), profile.getId().toString().replace("-", ""),
                    token.getToken(), xuid == null ? "" : xuid);
        } catch (Exception e) {
            throw AuthErrors.map(e);
        }
    }

    /** Writes every account and the current selection to {@code accounts.json}. */
    public void save() {
        synchronized (lock) {
            JsonObject root = new JsonObject();
            root.addProperty("version", FORMAT_VERSION);
            root.addProperty("selected", selected == null ? "" : selected.uuid());

            JsonArray array = new JsonArray();
            for (Account account : accounts) {
                JsonObject entry = new JsonObject();
                entry.addProperty("id", account.uuid());
                entry.addProperty("name", account.name());
                entry.add("auth", JavaAuthManager.toJson(account.manager()));
                array.add(entry);
            }
            root.add("accounts", array);

            try {
                AppPaths.ensureDirectories();
                Path temp = accountsFile.resolveSibling(accountsFile.getFileName() + ".tmp");
                Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
                restrictPermissions(temp);
                Files.move(temp, accountsFile, StandardCopyOption.REPLACE_EXISTING);
                restrictPermissions(accountsFile);
            } catch (IOException e) {
                System.err.println("[auth] Could not write " + accountsFile + ": " + e);
            }
        }
    }

    private Account findByUuid(String uuid) {
        for (Account account : accounts) {
            if (account.uuid().equalsIgnoreCase(uuid)) {
                return account;
            }
        }
        return null;
    }

    private void notifyChanged() {
        Runnable listener = changeListener;
        if (listener != null) {
            listener.run();
        }
    }

    /** Refresh tokens are passwords: keep the file owner-only where the OS supports it. */
    private static void restrictPermissions(Path path) {
        try {
            Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows and some network volumes have no POSIX permissions.
        }
    }

    /** Stable id helper for UI selection. */
    public static String idOf(Account account) {
        return account == null ? "" : account.uuid() + "/" + account.name();
    }

    /** Exposed for tooling/diagnostics; never log the value. */
    public static UUID randomDeviceId() {
        return UUID.randomUUID();
    }
}
