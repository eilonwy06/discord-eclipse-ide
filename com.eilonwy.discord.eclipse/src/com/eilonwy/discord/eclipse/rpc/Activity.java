package com.eilonwy.discord.eclipse.rpc;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * One Rich Presence activity, as sent in {@code SET_ACTIVITY}. Null fields are left out of the
 * payload. {@link #toJson()} enforces Discord's field limits, because Discord rejects the whole
 * update when any single field breaks them.
 */
public record Activity(
        String details,
        String state,
        Long startTimestamp,
        String largeImage,
        String largeText,
        String smallImage,
        String smallText,
        List<Button> buttons) {

    public record Button(String label, String url) {
    }

    /**
     * Pads text below Discord's 2-character minimum, as discord-vscode does. Two zero-width spaces
     * render as nothing but still count as content.
     */
    public static final String FAKE_EMPTY = "​​";

    private static final int MAX_TEXT = 128;
    private static final int MAX_BUTTON_LABEL = 32;
    private static final int MAX_BUTTON_URL = 512;
    private static final int MAX_BUTTONS = 2;

    public JsonObject toJson() {
        JsonObject a = new JsonObject();
        a.addProperty("type", 0); // "Playing", which is what discord-vscode sends
        putText(a, "details", details);
        putText(a, "state", state);
        if (startTimestamp != null) {
            JsonObject ts = new JsonObject();
            ts.addProperty("start", startTimestamp);
            a.add("timestamps", ts);
        }

        JsonObject assets = new JsonObject();
        putImage(assets, "large_image", largeImage);
        putText(assets, "large_text", largeText);
        putImage(assets, "small_image", smallImage);
        putText(assets, "small_text", smallText);
        if (assets.size() > 0) {
            a.add("assets", assets);
        }

        if (buttons != null && !buttons.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (Button b : buttons) {
                if (arr.size() == MAX_BUTTONS) {
                    break;
                }
                if (b.label() == null || b.label().isBlank() || b.url() == null
                        || b.url().length() > MAX_BUTTON_URL
                        || !(b.url().startsWith("https://") || b.url().startsWith("http://"))) {
                    continue;
                }
                JsonObject o = new JsonObject();
                o.addProperty("label", fit(b.label(), MAX_BUTTON_LABEL));
                o.addProperty("url", b.url());
                arr.add(o);
            }
            if (arr.size() > 0) {
                a.add("buttons", arr);
            }
        }
        return a;
    }

    private static void putText(JsonObject o, String key, String value) {
        if (value != null && !value.isEmpty()) {
            o.addProperty(key, fit(value, MAX_TEXT));
        }
    }

    private static void putImage(JsonObject o, String key, String value) {
        if (value != null && !value.isBlank()) {
            o.addProperty(key, value);
        }
    }

    /** Pads to at least 2 characters and truncates to {@code max}, never splitting a surrogate pair. */
    static String fit(String s, int max) {
        if (s.length() < 2) {
            return s + FAKE_EMPTY.substring(0, 2 - s.length());
        }
        if (s.length() <= max) {
            return s;
        }
        int end = max - 1;
        if (Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end) + "…";
    }
}
