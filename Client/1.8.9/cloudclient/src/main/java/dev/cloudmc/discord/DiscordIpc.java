package dev.cloudmc.discord;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Talks to the local Discord client over its IPC socket from Java 8.
 *
 * <p>Discord listens on {@code discord-ipc-N}: a Unix domain socket on macOS and Linux, a named
 * pipe on Windows. Java 8 can open neither, so this uses JNA: libc's socket calls on Unix and
 * {@code CreateFile}/{@code ReadFile}/{@code WriteFile} on Windows. Messages are an 8 byte header
 * (opcode and length, little-endian) followed by a UTF-8 JSON payload.</p>
 *
 * <p>Nothing here touches Minecraft or Forge, so it can be exercised on its own.</p>
 */
public final class DiscordIpc implements Closeable {

    private static final int OP_HANDSHAKE = 0;
    private static final int OP_FRAME = 1;
    private static final int MAX_SOCKETS = 10;
    private static final int MAX_FRAME = 1 << 20;

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private final Transport transport;
    private final Thread reader;
    private volatile boolean open = true;

    private DiscordIpc(Transport transport) {
        this.transport = transport;
        this.reader = new Thread(new Runnable() {
            @Override
            public void run() {
                drain();
            }
        }, "MixClient Discord IPC");
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
            Transport transport = WINDOWS ? openPipe(i) : openSocket(i);
            if (transport == null) {
                continue;
            }

            DiscordIpc ipc = new DiscordIpc(transport);
            if (ipc.handshake(clientId)) {
                return ipc;
            }
            ipc.close();
        }
        return null;
    }

    private boolean handshake(String clientId) {
        try {
            write(OP_HANDSHAKE, "{\"v\":1,\"client_id\":\"" + clientId + "\"}");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Updates the activity shown on the profile. */
    public void setActivity(String activityJson) {
        String payload = "{\"cmd\":\"SET_ACTIVITY\",\"args\":{\"pid\":" + pid()
                + ",\"activity\":" + activityJson + "},\"nonce\":\"" + UUID.randomUUID() + "\"}";
        try {
            write(OP_FRAME, payload);
        } catch (IOException e) {
            close();
        }
    }

    /** Takes the activity back down, used on shutdown. */
    public void clearActivity() {
        String payload = "{\"cmd\":\"SET_ACTIVITY\",\"args\":{\"pid\":" + pid()
                + "},\"nonce\":\"" + UUID.randomUUID() + "\"}";
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
        transport.write(buffer.array());
    }

    /**
     * Discord sends READY and error frames back; reading them keeps the pipe from filling up and
     * tells us when the client goes away.
     */
    private void drain() {
        byte[] header = new byte[8];
        try {
            while (open) {
                if (!readFully(header, 8)) {
                    break;
                }

                ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                buffer.getInt();
                int length = buffer.getInt();
                if (length < 0 || length > MAX_FRAME) {
                    break;
                }

                if (!readFully(new byte[length], length)) {
                    break;
                }
            }
        } catch (IOException e) {
            // Discord closed the pipe; the next reconnect attempt handles it.
        } finally {
            open = false;
        }
    }

    private boolean readFully(byte[] buffer, int length) throws IOException {
        int read = 0;
        while (read < length) {
            int count = transport.read(buffer, read, length - read);
            if (count <= 0) {
                return false;
            }
            read += count;
        }
        return true;
    }

    @Override
    public void close() {
        open = false;
        transport.close();
    }

    private static long pid() {
        String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        try {
            return Long.parseLong(at > 0 ? name.substring(0, at) : name);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    // -----------------------------------------------------------------------
    // Windows: named pipe through Kernel32.
    // -----------------------------------------------------------------------

    private static final int GENERIC_READ = 0x80000000;
    private static final int GENERIC_WRITE = 0x40000000;
    private static final int OPEN_EXISTING = 3;

    private static Transport openPipe(int index) {
        try {
            WinNT.HANDLE handle = Kernel32.INSTANCE.CreateFile(
                    "\\\\.\\pipe\\discord-ipc-" + index,
                    GENERIC_READ | GENERIC_WRITE,
                    0,
                    null,
                    OPEN_EXISTING,
                    0,
                    null);

            if (handle == null || WinNT.INVALID_HANDLE_VALUE.equals(handle)) {
                return null;
            }
            return new WindowsPipe(handle);
        } catch (Throwable t) {
            // Discord is not running, or JNA could not load: nothing to do.
            return null;
        }
    }

    private static final class WindowsPipe implements Transport {

        private final WinNT.HANDLE handle;

        WindowsPipe(WinNT.HANDLE handle) {
            this.handle = handle;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            byte[] chunk = new byte[length];
            IntByReference read = new IntByReference();
            if (!Kernel32.INSTANCE.ReadFile(handle, chunk, length, read, null)) {
                return -1;
            }
            if (read.getValue() > 0) {
                System.arraycopy(chunk, 0, buffer, offset, read.getValue());
            }
            return read.getValue();
        }

        @Override
        public void write(byte[] data) throws IOException {
            IntByReference written = new IntByReference();
            if (!Kernel32.INSTANCE.WriteFile(handle, data, data.length, written, null)
                    || written.getValue() != data.length) {
                throw new IOException("Discord pipe write failed");
            }
        }

        @Override
        public void close() {
            Kernel32.INSTANCE.CloseHandle(handle);
        }
    }

    // -----------------------------------------------------------------------
    // macOS / Linux: AF_UNIX socket through libc.
    // -----------------------------------------------------------------------

    private interface LibC extends Library {
        LibC INSTANCE = Native.load("c", LibC.class);

        int socket(int domain, int type, int protocol);

        int connect(int socket, Memory address, int length);

        int read(int socket, byte[] buffer, int count);

        long write(int socket, byte[] buffer, int count);

        int close(int socket);
    }

    private static Transport openSocket(int index) {
        for (Path candidate : socketPaths(index)) {
            try {
                Transport transport = UnixSocket.connect(candidate);
                if (transport != null) {
                    return transport;
                }
            } catch (Throwable t) {
                // This candidate is not usable; try the next one.
            }
        }
        return null;
    }

    /**
     * Every place a Discord client can put its socket: the runtime dir, TMPDIR (where macOS puts
     * it), /tmp, and the sandboxed paths used by the Snap and Flatpak builds.
     */
    private static List<Path> socketPaths(int index) {
        String socket = "discord-ipc-" + index;
        List<Path> paths = new ArrayList<Path>(6);

        String runtimeDir = System.getenv("XDG_RUNTIME_DIR");
        if (runtimeDir != null && !runtimeDir.trim().isEmpty()) {
            paths.add(Paths.get(runtimeDir, socket));
            paths.add(Paths.get(runtimeDir, "app", "com.discordapp.Discord", socket));
            paths.add(Paths.get(runtimeDir, "snap.discord", socket));
        }

        String tmpDir = System.getenv("TMPDIR");
        if (tmpDir != null && !tmpDir.trim().isEmpty()) {
            paths.add(Paths.get(tmpDir, socket));
        }

        paths.add(Paths.get("/tmp", socket));
        return paths;
    }

    private static final class UnixSocket implements Transport {

        private static final int AF_UNIX = 1;
        private static final int SOCK_STREAM = 1;
        private static final int MAX_PATH = 104;
        private static final boolean MAC =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

        private final int fd;

        private UnixSocket(int fd) {
            this.fd = fd;
        }

        static Transport connect(Path path) {
            int fd = LibC.INSTANCE.socket(AF_UNIX, SOCK_STREAM, 0);
            if (fd < 0) {
                return null;
            }

            UnixSocket socket = new UnixSocket(fd);
            if (!socket.connectTo(path)) {
                socket.close();
                return null;
            }
            return socket;
        }

        private boolean connectTo(Path path) {
            byte[] bytes = path.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length == 0 || bytes.length >= MAX_PATH) {
                return false;
            }

            // macOS has sockaddr_un { uint8_t sun_len; uint8_t sun_family; char sun_path[104]; },
            // Linux has { unsigned short sun_family; char sun_path[108]; }.
            Memory address = new Memory(110);
            address.clear();

            int pathOffset;
            if (MAC) {
                address.setByte(0, (byte) (3 + bytes.length));
                address.setByte(1, (byte) AF_UNIX);
                pathOffset = 2;
            } else {
                address.setShort(0, (short) AF_UNIX);
                pathOffset = 2;
            }

            address.write(pathOffset, bytes, 0, bytes.length);
            return LibC.INSTANCE.connect(fd, address, pathOffset + bytes.length + 1) == 0;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            byte[] chunk = new byte[length];
            int count = LibC.INSTANCE.read(fd, chunk, length);
            if (count > 0) {
                System.arraycopy(chunk, 0, buffer, offset, count);
            }
            return count;
        }

        @Override
        public void write(byte[] data) throws IOException {
            int written = 0;
            while (written < data.length) {
                byte[] remaining = new byte[data.length - written];
                System.arraycopy(data, written, remaining, 0, remaining.length);

                long count = LibC.INSTANCE.write(fd, remaining, remaining.length);
                if (count <= 0) {
                    throw new IOException("Discord socket write failed");
                }
                written += (int) count;
            }
        }

        @Override
        public void close() {
            LibC.INSTANCE.close(fd);
        }
    }

    private interface Transport {
        int read(byte[] buffer, int offset, int length) throws IOException;

        void write(byte[] data) throws IOException;

        void close();
    }
}
