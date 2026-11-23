package dev.mixclient.discord;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ByteChannel;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Speaks the Discord IPC protocol straight to the local Discord client.
 *
 * <p>Discord exposes a local socket per running client at {@code discord-ipc-N}: a Unix domain
 * socket on macOS and Linux, a named pipe on Windows. Every message is an 8 byte header (opcode
 * and length, both little-endian ints) followed by a UTF-8 JSON payload. Doing it here keeps the
 * mod free of JNA or any other native dependency, because Java 25 can open both socket kinds
 * itself.</p>
 */
public final class DiscordIpc implements Closeable {

    private static final int OP_HANDSHAKE = 0;
    private static final int OP_FRAME = 1;
    private static final int MAX_SOCKETS = 10;

    private final ByteChannel channel;
    private final Thread reader;
    private volatile boolean open = true;

    private DiscordIpc(ByteChannel channel) {
        this.channel = channel;
        this.reader = new Thread(this::drain, "MixClient Discord IPC");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    /**
     * Connects to the first Discord client that answers, handshake included.
     *
     * @return a connected transport, or {@code null} when Discord is not running
     */
    public static DiscordIpc connect(String clientId) {
        for (int i = 0; i < MAX_SOCKETS; i++) {
            ByteChannel channel = open(i);
            if (channel == null) {
                continue;
            }

            DiscordIpc ipc = new DiscordIpc(channel);
            if (ipc.handshake(clientId)) {
                return ipc;
            }
            ipc.close();
        }
        return null;
    }

    private static ByteChannel open(int index) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                return FileChannel.open(
                        Path.of("\\\\.\\pipe\\discord-ipc-" + index),
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE);
            }

            for (Path candidate : socketPaths(index)) {
                SocketChannel channel = SocketChannel.open(java.net.StandardProtocolFamily.UNIX);
                try {
                    channel.connect(java.net.UnixDomainSocketAddress.of(candidate));
                    return channel;
                } catch (IOException | UnsupportedOperationException e) {
                    channel.close();
                }
            }
        } catch (IOException | UnsupportedOperationException e) {
            // Discord is not running, or this platform cannot open the socket: nothing to do.
        }
        return null;
    }

    /**
     * Every place a Discord client can put its socket: the runtime dir, TMPDIR (which is where
     * macOS puts it), /tmp, and the sandboxed paths used by the Snap and Flatpak builds.
     */
    private static List<Path> socketPaths(int index) {
        String socket = "discord-ipc-" + index;
        List<Path> paths = new ArrayList<>(6);

        String runtimeDir = System.getenv("XDG_RUNTIME_DIR");
        if (runtimeDir != null && !runtimeDir.isBlank()) {
            paths.add(Path.of(runtimeDir, socket));
            paths.add(Path.of(runtimeDir, "app", "com.discordapp.Discord", socket));
            paths.add(Path.of(runtimeDir, "snap.discord", socket));
        }

        String tmpDir = System.getenv("TMPDIR");
        if (tmpDir != null && !tmpDir.isBlank()) {
            paths.add(Path.of(tmpDir, socket));
        }

        paths.add(Path.of("/tmp", socket));
        return paths;
    }

    private boolean handshake(String clientId) {
        String payload = "{\"v\":1,\"client_id\":\"" + clientId + "\"}";
        try {
            write(OP_HANDSHAKE, payload);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Updates the activity Discord shows on the profile. */
    public void setActivity(String activityJson) {
        String payload = "{\"cmd\":\"SET_ACTIVITY\",\"args\":{\"pid\":" + ProcessHandle.current().pid()
                + ",\"activity\":" + activityJson + "},\"nonce\":\"" + java.util.UUID.randomUUID() + "\"}";
        try {
            write(OP_FRAME, payload);
        } catch (IOException e) {
            close();
        }
    }

    public void clearActivity() {
        String payload = "{\"cmd\":\"SET_ACTIVITY\",\"args\":{\"pid\":" + ProcessHandle.current().pid()
                + "},\"nonce\":\"" + java.util.UUID.randomUUID() + "\"}";
        try {
            write(OP_FRAME, payload);
        } catch (IOException e) {
            close();
        }
    }

    public boolean isOpen() {
        return open;
    }

    private synchronized void write(int op, String json) throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(op);
        buffer.putInt(payload.length);
        buffer.put(payload);
        buffer.flip();

        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    /**
     * Discord sends frames back (READY, errors); reading them keeps the pipe from filling up and
     * tells us when the client goes away.
     */
    private void drain() {
        ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        try {
            while (open) {
                header.clear();
                if (!readFully(header)) {
                    break;
                }
                header.flip();
                header.getInt();
                int length = header.getInt();
                if (length < 0 || length > 1 << 20) {
                    break;
                }

                ByteBuffer payload = ByteBuffer.allocate(length);
                if (!readFully(payload)) {
                    break;
                }
            }
        } catch (IOException e) {
            // Discord closed the pipe; the next reconnect attempt handles it.
        } finally {
            open = false;
        }
    }

    private boolean readFully(ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() {
        open = false;
        try {
            channel.close();
        } catch (IOException ignored) {
            // Already gone.
        }
    }
}
