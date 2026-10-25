package com.eilonwy.discord.eclipse.rpc;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Discord's local RPC transport: a named pipe on Windows, a Unix domain socket elsewhere, carrying
 * frames of {@code opcode:int32le, length:int32le, payload:utf-8 json}.
 *
 * <p>Not thread-safe, on purpose. On Windows the pipe is a synchronous handle, where a read blocked
 * on one thread also blocks every write on the same handle, so a background reader thread would
 * stall all updates. The only safe pattern is a single thread that writes a command and then reads
 * until its reply arrives, which is what {@link PresenceClient} does.
 *
 * <p>Pure Java with no Eclipse imports, so it can be exercised under plain {@code javac}/{@code java}.
 */
public final class DiscordIpc implements Closeable {

    public static final int OP_HANDSHAKE = 0;
    public static final int OP_FRAME = 1;
    public static final int OP_CLOSE = 2;
    public static final int OP_PING = 3;
    public static final int OP_PONG = 4;

    /** Discord's replies are a few kilobytes; anything near this means the stream is out of sync. */
    private static final int MAX_FRAME = 1 << 20;

    public record Frame(int op, String json) {
    }

    private interface Pipe extends Closeable {
        void readFully(byte[] b) throws IOException;

        void write(byte[] b) throws IOException;
    }

    private final Pipe pipe;
    private final String path;

    private DiscordIpc(Pipe pipe, String path) {
        this.pipe = pipe;
        this.path = path;
    }

    /** Connects to the first Discord client found, trying the same slots the official SDK does. */
    public static DiscordIpc connect() throws IOException {
        IOException last = null;
        for (String candidate : candidates()) {
            try {
                return new DiscordIpc(open(candidate), candidate);
            } catch (IOException e) {
                last = e;
            }
        }
        throw new IOException("No Discord client found. Is the Discord desktop app running?", last);
    }

    /** The pipe or socket path this connection uses. */
    public String path() {
        return path;
    }

    public void write(int op, String json) throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        // One buffer, one write: a frame split across two writes can be read half-delivered.
        ByteBuffer frame = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        frame.putInt(op).putInt(payload.length).put(payload);
        pipe.write(frame.array());
    }

    /** Blocks until a whole frame has arrived. */
    public Frame read() throws IOException {
        byte[] header = new byte[8];
        pipe.readFully(header);
        ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int op = h.getInt();
        int length = h.getInt();
        if (length < 0 || length > MAX_FRAME) {
            throw new IOException("Malformed Discord IPC frame (length " + length + ")");
        }
        byte[] payload = new byte[length];
        pipe.readFully(payload);
        return new Frame(op, new String(payload, StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        try {
            pipe.close();
        } catch (IOException ignored) {
            // nothing useful to do; the other end is gone either way
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private static List<String> candidates() {
        List<String> out = new ArrayList<>();
        if (isWindows()) {
            for (int i = 0; i < 10; i++) {
                out.add("\\\\.\\pipe\\discord-ipc-" + i);
            }
            return out;
        }
        List<String> roots = new ArrayList<>();
        for (String env : new String[] { "XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP" }) {
            String v = System.getenv(env);
            if (v != null && !v.isBlank()) {
                roots.add(v);
            }
        }
        roots.add("/tmp");
        // Flatpak and Snap builds of Discord put the socket under their own sandbox directories.
        String[] subdirs = { "", "app/com.discordapp.Discord", "snap.discord", ".flatpak/com.discordapp.Discord/xdg-run" };
        for (String root : roots) {
            for (String sub : subdirs) {
                for (int i = 0; i < 10; i++) {
                    Path p = Path.of(root, sub, "discord-ipc-" + i);
                    if (Files.exists(p) && !out.contains(p.toString())) {
                        out.add(p.toString());
                    }
                }
            }
        }
        return out;
    }

    private static Pipe open(String path) throws IOException {
        if (isWindows()) {
            RandomAccessFile raf = new RandomAccessFile(path, "rw");
            return new Pipe() {
                @Override
                public void readFully(byte[] b) throws IOException {
                    raf.readFully(b);
                }

                @Override
                public void write(byte[] b) throws IOException {
                    raf.write(b);
                }

                @Override
                public void close() throws IOException {
                    raf.close();
                }
            };
        }
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            channel.connect(UnixDomainSocketAddress.of(path));
        } catch (IOException e) {
            channel.close();
            throw e;
        }
        InputStream in = Channels.newInputStream(channel);
        OutputStream out = Channels.newOutputStream(channel);
        return new Pipe() {
            @Override
            public void readFully(byte[] b) throws IOException {
                int off = 0;
                while (off < b.length) {
                    int n = in.read(b, off, b.length - off);
                    if (n < 0) {
                        throw new EOFException("Discord closed the connection");
                    }
                    off += n;
                }
            }

            @Override
            public void write(byte[] b) throws IOException {
                out.write(b);
                out.flush();
            }

            @Override
            public void close() throws IOException {
                channel.close();
            }
        };
    }
}
