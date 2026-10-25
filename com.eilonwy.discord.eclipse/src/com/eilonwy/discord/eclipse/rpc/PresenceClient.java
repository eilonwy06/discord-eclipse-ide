package com.eilonwy.discord.eclipse.rpc;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Keeps one Discord IPC connection alive and pushes the latest activity to it.
 *
 * <p>All pipe I/O happens on one worker thread, in strict write-then-read-the-reply order (see
 * {@link DiscordIpc} for why). Callers on any thread only set the desired state. Updates are
 * coalesced, latest wins, and sent at most once per {@link #MIN_SEND_INTERVAL_MS}, because Discord
 * rate-limits {@code SET_ACTIVITY} to roughly five per 20 seconds and switching editors easily
 * produces more than that.
 *
 * <p>Status changes are reported to the listener on the worker thread.
 */
public final class PresenceClient {

    public enum State {
        /** Rich Presence is switched off in the preferences. */
        DISABLED,
        /** Switched on, but there is no Application ID to connect with. */
        NO_APPLICATION_ID,
        CONNECTING,
        CONNECTED,
        DISCONNECTED
    }

    /**
     * @param user    the Discord user, once connected
     * @param message why the connection failed, or why Discord rejected the last update
     */
    public record Status(State state, String user, String message) {
    }

    private static final long MIN_SEND_INTERVAL_MS = 4_000;
    private static final long RETRY_INTERVAL_MS = 15_000;

    private final Consumer<Status> listener;
    private final Object lock = new Object();

    // Guarded by lock. version bumps on every change so the worker never misses a wake-up.
    private String clientId = "";
    private boolean enabled;
    private Activity desired;
    private boolean dirty;
    private boolean reconnectRequested;
    private boolean running;
    private long version;
    private Thread worker;

    // Worker thread only.
    private DiscordIpc ipc;
    private String connectedId;
    private String connectedUser;
    /** An ID Discord refused at handshake; not retried until it changes or the user reconnects. */
    private String rejectedId;
    private long lastSend;
    private long nextRetry;

    private volatile Status status = new Status(State.DISABLED, null, null);

    public PresenceClient(Consumer<Status> listener) {
        this.listener = listener;
    }

    public void start() {
        synchronized (lock) {
            if (running) {
                return;
            }
            running = true;
            worker = new Thread(this::run, "Discord Rich Presence");
            worker.setDaemon(true);
            worker.start();
        }
    }

    /**
     * Stops the worker and closes the connection; Discord clears the presence when the connection
     * drops. Waits briefly, but a worker stuck in a read on an unresponsive Discord is a daemon
     * thread and will not hold up shutdown.
     */
    public void stop() {
        Thread t;
        synchronized (lock) {
            running = false;
            version++;
            lock.notifyAll();
            t = worker;
            worker = null;
        }
        if (t != null) {
            try {
                t.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void configure(String applicationId, boolean on) {
        synchronized (lock) {
            clientId = applicationId == null ? "" : applicationId.trim();
            enabled = on;
            version++;
            lock.notifyAll();
        }
    }

    /** Sets what Discord should show; {@code null} clears the presence. */
    public void setActivity(Activity activity) {
        synchronized (lock) {
            if (Objects.equals(desired, activity)) {
                return;
            }
            desired = activity;
            dirty = true;
            version++;
            lock.notifyAll();
        }
    }

    /** Drops the connection and retries now, including an Application ID Discord refused before. */
    public void reconnect() {
        synchronized (lock) {
            reconnectRequested = true;
            version++;
            lock.notifyAll();
        }
    }

    public Status status() {
        return status;
    }

    private void run() {
        try {
            while (true) {
                long seen;
                String id;
                boolean on;
                boolean reconnect;
                boolean send;
                synchronized (lock) {
                    if (!running) {
                        return;
                    }
                    seen = version;
                    id = clientId;
                    on = enabled;
                    reconnect = reconnectRequested;
                    reconnectRequested = false;
                    send = dirty;
                }
                long now = System.currentTimeMillis();
                boolean wanted = on && !id.isEmpty();

                if (reconnect) {
                    disconnect();
                    nextRetry = 0;
                    rejectedId = null;
                }
                if (ipc != null && (!wanted || !id.equals(connectedId))) {
                    disconnect();
                    nextRetry = 0;
                }
                if (!wanted) {
                    publish(new Status(on ? State.NO_APPLICATION_ID : State.DISABLED, null, null));
                    await(seen, 0);
                    continue;
                }

                if (ipc == null) {
                    if (id.equals(rejectedId)) {
                        await(seen, 0);
                        continue;
                    }
                    if (now < nextRetry) {
                        await(seen, nextRetry - now);
                        continue;
                    }
                    connect(id);
                    if (ipc == null) {
                        nextRetry = System.currentTimeMillis() + RETRY_INTERVAL_MS;
                    } else {
                        // A fresh connection shows nothing until told otherwise.
                        synchronized (lock) {
                            dirty = true;
                        }
                    }
                    continue;
                }

                if (!send) {
                    await(seen, 0);
                    continue;
                }
                long wait = lastSend + MIN_SEND_INTERVAL_MS - now;
                if (wait > 0) {
                    await(seen, wait);
                    continue;
                }

                Activity toSend;
                synchronized (lock) {
                    toSend = desired;
                    dirty = false;
                }
                try {
                    sendActivity(toSend);
                    lastSend = System.currentTimeMillis();
                } catch (IOException | RuntimeException e) {
                    disconnect();
                    publish(new Status(State.DISCONNECTED, null, message(e)));
                    nextRetry = System.currentTimeMillis() + RETRY_INTERVAL_MS;
                    synchronized (lock) {
                        dirty = true;
                    }
                }
            }
        } finally {
            disconnect();
        }
    }

    private void await(long seen, long millis) {
        synchronized (lock) {
            try {
                if (version == seen && running) {
                    if (millis > 0) {
                        lock.wait(millis);
                    } else {
                        lock.wait();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            }
        }
    }

    private void connect(String id) {
        publish(new Status(State.CONNECTING, null, null));
        DiscordIpc c = null;
        try {
            c = DiscordIpc.connect();
            JsonObject handshake = new JsonObject();
            handshake.addProperty("v", 1);
            handshake.addProperty("client_id", id);
            c.write(DiscordIpc.OP_HANDSHAKE, handshake.toString());
            while (true) {
                DiscordIpc.Frame f = c.read();
                if (f.op() == DiscordIpc.OP_PING) {
                    c.write(DiscordIpc.OP_PONG, f.json());
                    continue;
                }
                JsonObject o = parse(f.json());
                if (f.op() == DiscordIpc.OP_CLOSE) {
                    // Discord closes the handshake for a bad Application ID (code 4000) or protocol
                    // version. Retrying the same ID every few seconds would only repeat the refusal.
                    c.close();
                    rejectedId = id;
                    publish(new Status(State.DISCONNECTED, null, describeClose(o)));
                    return;
                }
                if ("READY".equals(string(o, "evt"))) {
                    ipc = c;
                    connectedId = id;
                    connectedUser = userName(o);
                    publish(new Status(State.CONNECTED, connectedUser, null));
                    return;
                }
                throw new IOException("Unexpected reply to the Discord handshake: " + f.json());
            }
        } catch (IOException | RuntimeException e) {
            if (c != null) {
                c.close();
            }
            publish(new Status(State.DISCONNECTED, null, message(e)));
        }
    }

    private void sendActivity(Activity activity) throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("pid", ProcessHandle.current().pid());
        if (activity != null) {
            args.add("activity", activity.toJson());
        }
        String nonce = UUID.randomUUID().toString();
        JsonObject cmd = new JsonObject();
        cmd.addProperty("cmd", "SET_ACTIVITY");
        cmd.add("args", args);
        cmd.addProperty("nonce", nonce);
        ipc.write(DiscordIpc.OP_FRAME, cmd.toString());

        while (true) {
            DiscordIpc.Frame f = ipc.read();
            if (f.op() == DiscordIpc.OP_PING) {
                ipc.write(DiscordIpc.OP_PONG, f.json());
                continue;
            }
            JsonObject o = parse(f.json());
            if (f.op() == DiscordIpc.OP_CLOSE) {
                throw new IOException(describeClose(o));
            }
            if (f.op() == DiscordIpc.OP_FRAME && nonce.equals(string(o, "nonce"))) {
                if ("ERROR".equals(string(o, "evt"))) {
                    JsonObject data = o.has("data") && o.get("data").isJsonObject() ? o.getAsJsonObject("data") : new JsonObject();
                    publish(new Status(State.CONNECTED, connectedUser,
                            "Discord rejected the update: " + Objects.requireNonNullElse(string(data, "message"), f.json())));
                } else {
                    publish(new Status(State.CONNECTED, connectedUser, null));
                }
                return;
            }
        }
    }

    private void disconnect() {
        if (ipc != null) {
            ipc.close();
            ipc = null;
        }
        connectedId = null;
        connectedUser = null;
    }

    private void publish(Status s) {
        if (s.equals(status)) {
            return;
        }
        status = s;
        try {
            listener.accept(s);
        } catch (RuntimeException ignored) {
            // a broken listener must not take the connection down with it
        }
    }

    private static JsonObject parse(String json) {
        try {
            JsonElement e = JsonParser.parseString(json);
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static String userName(JsonObject ready) {
        JsonElement data = ready.get("data");
        if (data == null || !data.isJsonObject()) {
            return null;
        }
        JsonElement user = data.getAsJsonObject().get("user");
        if (user == null || !user.isJsonObject()) {
            return null;
        }
        String global = string(user.getAsJsonObject(), "global_name");
        return global != null && !global.isBlank() ? global : string(user.getAsJsonObject(), "username");
    }

    private static String describeClose(JsonObject o) {
        String msg = Objects.requireNonNullElse(string(o, "message"), "Discord closed the connection");
        String code = string(o, "code");
        return code == null ? msg : msg + " (code " + code + ")";
    }

    private static String message(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
