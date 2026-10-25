package com.eilonwy.discord.eclipse.presence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Branch and remote of the Git repository holding a path, read straight from {@code .git} so the
 * plugin needs neither EGit nor a git executable. discord-vscode gets the same two facts from VS
 * Code's Git extension.
 *
 * @param branch    the checked-out branch, or a short commit id when HEAD is detached
 * @param remoteUrl {@code origin}'s URL, else the first remote's; {@code null} when there is none
 */
public record GitInfo(String branch, String remoteUrl) {

    private static final Pattern SECTION = Pattern.compile("^\\s*\\[\\s*remote\\s+\"([^\"]+)\"\\s*]\\s*$");
    private static final Pattern ANY_SECTION = Pattern.compile("^\\s*\\[.*]\\s*$");
    private static final Pattern URL = Pattern.compile("^\\s*url\\s*=\\s*(.+?)\\s*$");
    private static final Pattern SCP_LIKE = Pattern.compile("^(?:[^@/]+@)?([^:/]+):(?!//)(.+)$");

    /** Walks up from {@code start} to the nearest repository; {@code null} if there is none. */
    public static GitInfo find(Path start) {
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            Path dotGit = dir.resolve(".git");
            try {
                Path gitDir;
                if (Files.isDirectory(dotGit)) {
                    gitDir = dotGit;
                } else if (Files.isRegularFile(dotGit)) {
                    // Worktrees and submodules: ".git" is a file reading "gitdir: <path>".
                    String s = Files.readString(dotGit, StandardCharsets.UTF_8).trim();
                    if (!s.startsWith("gitdir:")) {
                        continue;
                    }
                    gitDir = dir.resolve(s.substring("gitdir:".length()).trim()).normalize();
                } else {
                    continue;
                }
                Path commonDir = gitDir;
                Path commonFile = gitDir.resolve("commondir");
                if (Files.isRegularFile(commonFile)) {
                    commonDir = gitDir.resolve(Files.readString(commonFile, StandardCharsets.UTF_8).trim()).normalize();
                }
                return new GitInfo(readBranch(gitDir.resolve("HEAD")), readRemote(commonDir.resolve("config")));
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    private static String readBranch(Path head) throws IOException {
        if (!Files.isRegularFile(head)) {
            return null;
        }
        String s = Files.readString(head, StandardCharsets.UTF_8).trim();
        if (s.startsWith("ref:")) {
            String ref = s.substring(4).trim();
            return ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : ref;
        }
        return s.length() >= 7 ? s.substring(0, 7) : s;
    }

    private static String readRemote(Path config) throws IOException {
        if (!Files.isRegularFile(config)) {
            return null;
        }
        List<String> lines = Files.readAllLines(config, StandardCharsets.UTF_8);
        String section = null;
        String first = null;
        for (String line : lines) {
            Matcher sec = SECTION.matcher(line);
            if (sec.matches()) {
                section = sec.group(1);
                continue;
            }
            if (ANY_SECTION.matcher(line).matches()) {
                section = null;
                continue;
            }
            Matcher url = URL.matcher(line);
            if (section != null && url.matches()) {
                if ("origin".equals(section)) {
                    return url.group(1);
                }
                if (first == null) {
                    first = url.group(1);
                }
            }
        }
        return first;
    }

    /** Last path segment of the remote without {@code .git}, e.g. {@code discord-vscode}. */
    public String repoName() {
        String url = browseUrl();
        String s = url != null ? url : remoteUrl;
        if (s == null) {
            return null;
        }
        s = stripSuffixes(s);
        int slash = Math.max(Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\')), s.lastIndexOf(':'));
        return slash >= 0 ? s.substring(slash + 1) : s;
    }

    /**
     * The remote as a web URL for the "View Repository" button, the way discord-vscode builds it:
     * SSH remotes become https, and any credentials in an https remote are stripped so a token
     * never ends up on a public profile. {@code null} when the remote is not a host URL at all,
     * e.g. a path on disk.
     */
    public String browseUrl() {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            return null;
        }
        String r = remoteUrl.trim();
        String out;
        if (r.startsWith("ssh://")) {
            String rest = r.substring("ssh://".length());
            int at = rest.indexOf('@');
            int slash = rest.indexOf('/');
            if (at >= 0 && (slash < 0 || at < slash)) {
                rest = rest.substring(at + 1);
                slash = rest.indexOf('/');
            }
            if (slash < 0) {
                return null;
            }
            String host = rest.substring(0, slash);
            int colon = host.indexOf(':');
            if (colon >= 0) {
                host = host.substring(0, colon); // an SSH port means nothing to a browser
            }
            out = "https://" + host + rest.substring(slash);
        } else if (r.startsWith("https://") || r.startsWith("http://")) {
            int scheme = r.indexOf("://") + 3;
            int slash = r.indexOf('/', scheme);
            int at = r.lastIndexOf('@', slash < 0 ? r.length() : slash);
            out = at >= scheme ? r.substring(0, scheme) + r.substring(at + 1) : r;
        } else {
            Matcher m = SCP_LIKE.matcher(r);
            if (!m.matches() || r.startsWith("/") || r.matches("^[A-Za-z]:[\\\\/].*")) {
                return null;
            }
            out = "https://" + m.group(1) + "/" + m.group(2);
        }
        return stripSuffixes(out);
    }

    private static String stripSuffixes(String s) {
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.endsWith(".git") ? s.substring(0, s.length() - 4) : s;
    }
}
