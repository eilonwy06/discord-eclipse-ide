package com.eilonwy.discord.eclipse.presence;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * File name to icon name, using discord-vscode's {@code languages.json} unchanged. Matching mirrors
 * its {@code resolveFileIcon}: keys are tried in file order, each matching either as a file-name
 * suffix or, when written as {@code /regex/flags}, as a JavaScript-style regular expression. The
 * first hit wins and nothing matching gives {@code "text"}.
 *
 * <p>discord-vscode falls back on VS Code's language id when no key matches. Eclipse has no such
 * id, so that table is not used; the 400-odd file-name keys cover the common languages on their own.
 */
public final class Languages {

    public static final String FALLBACK = "text";

    /** discord-vscode only treats a key as a regex when it has flags, e.g. {@code /\.c$/i}. */
    private static final Pattern REGEX_KEY = Pattern.compile("^/(.*)/([gimy]+)$");

    private record Rule(String key, Pattern regex, String image) {
    }

    private final List<Rule> rules;
    private final List<String> rejected;

    private Languages(List<Rule> rules, List<String> rejected) {
        this.rules = rules;
        this.rejected = rejected;
    }

    public static Languages load(InputStream json) throws IOException {
        List<Rule> rules = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        try (Reader r = new InputStreamReader(json, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("KNOWN_EXTENSIONS").entrySet()) {
                JsonElement v = e.getValue();
                String image = v.isJsonObject() ? v.getAsJsonObject().get("image").getAsString() : v.getAsString();
                Pattern regex = null;
                Matcher m = REGEX_KEY.matcher(e.getKey());
                if (m.matches()) {
                    int flags = m.group(2).contains("i") ? Pattern.CASE_INSENSITIVE : 0;
                    try {
                        regex = Pattern.compile(m.group(1), flags);
                    } catch (PatternSyntaxException ex) {
                        try {
                            // Without the u flag JavaScript reads an unknown escape such as \p as
                            // the bare letter; Java reads \p as a Unicode class and refuses it.
                            regex = Pattern.compile(m.group(1).replaceAll("\\\\([pP])(?!\\{)", "$1"), flags);
                        } catch (PatternSyntaxException again) {
                            // Still usable as a literal suffix.
                            rejected.add(e.getKey());
                        }
                    }
                }
                rules.add(new Rule(e.getKey(), regex, image));
            }
        } catch (RuntimeException e) {
            throw new IOException("Malformed languages.json", e);
        }
        return new Languages(List.copyOf(rules), List.copyOf(rejected));
    }

    public static Languages empty() {
        return new Languages(List.of(), List.of());
    }

    /** Icon name for a file, e.g. {@code "java"} for {@code Foo.java}. */
    public String iconFor(String fileName) {
        if (fileName == null) {
            return FALLBACK;
        }
        for (Rule rule : rules) {
            if (fileName.endsWith(rule.key()) || (rule.regex() != null && rule.regex().matcher(fileName).find())) {
                return rule.image();
            }
        }
        return FALLBACK;
    }

    public int size() {
        return rules.size();
    }

    /** Regex keys Java could not compile; they still match as plain suffixes. */
    public List<String> rejectedPatterns() {
        return rejected;
    }
}
