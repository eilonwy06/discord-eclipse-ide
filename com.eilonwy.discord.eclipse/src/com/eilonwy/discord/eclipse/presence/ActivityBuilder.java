package com.eilonwy.discord.eclipse.presence;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.eilonwy.discord.eclipse.rpc.Activity;

/**
 * Turns a {@link Snapshot} into the {@link Activity} Discord shows, following discord-vscode's
 * {@code activity.ts}: the same templates, the same {@code {token}} names, the same image rules.
 *
 * <p>Where Eclipse differs from VS Code the tokens map as follows. {@code {workspace}} is the
 * Eclipse workspace name (Window &gt; Preferences &gt; General &gt; Workspace, else the workspace
 * folder name). {@code {workspace_folder}} and its alias {@code {project}} are the project holding
 * the file, the closest Eclipse has to a VS Code workspace folder.
 *
 * <p>Two of the reference's quirks are not copied. It replaces only the first occurrence of each
 * token; here every occurrence is replaced. And {@code {full_dir_name}} falls back to the file's
 * folder on disk when the file is outside every project, instead of staying as literal text.
 *
 * <p>Runs off the UI thread, since file size and Git need the disk.
 */
public final class ActivityBuilder {

    private static final String[] FILE_SIZES = { " bytes", "KB", "MB", "GB", "TB" };
    private static final String UNKNOWN = "Unknown";

    private ActivityBuilder() {
    }

    /**
     * @param start when this session started, carried across updates so Discord's elapsed timer
     *              keeps running instead of resetting on every editor switch
     * @return the activity, or {@code null} when the workspace or project is excluded
     */
    public static Activity build(Snapshot s, PresenceSettings p, Languages languages, long start) {
        if (excluded(s, p)) {
            return null;
        }
        Snapshot.Editor ed = s.editor();
        Context c = new Context(s, p, ed, ed == null ? null : gitFor(ed), languages);

        String largeImage = icon(p, "idle");
        String largeText = p.largeImageIdling();
        String smallImage = s.debugging() ? icon(p, "debugging") : p.appImage();
        String smallText = p.smallImage().replace("{app_name}", s.appName());

        String details = p.removeDetails() ? null : details(p.detailsIdling(), p.detailsEditing(), p.detailsDebugging(), c);
        String state = null;

        if (ed != null) {
            // The idle template is only for the top line; with no editor the reference sends no state.
            state = p.removeLowerDetails() ? null
                    : details(null, p.lowerDetailsEditing(), p.lowerDetailsDebugging(), c);
            largeImage = icon(p, c.lang);
            largeText = language(p.largeImage(), c.lang);
        }

        if (p.swapBigAndSmallImage()) {
            String img = largeImage;
            String txt = largeText;
            largeImage = smallImage;
            largeText = smallText;
            smallImage = img;
            smallText = txt;
        }

        List<Activity.Button> buttons = List.of();
        if (!p.removeRemoteRepository() && c.git != null && c.git.browseUrl() != null) {
            buttons = List.of(new Activity.Button("View Repository", c.git.browseUrl()));
        }

        return new Activity(
                blankToNull(details),
                blankToNull(state),
                p.removeTimestamp() ? null : start,
                largeImage,
                blankToNull(largeText),
                smallImage,
                blankToNull(smallText),
                buttons);
    }

    /**
     * Image names (language icons, {@code idle}, {@code debugging}) resolve against the icon base
     * URL. With no base URL the bare name is sent, for an application with its own uploaded art.
     */
    static String icon(PresenceSettings p, String name) {
        String base = p.iconBaseUrl() == null ? "" : p.iconBaseUrl().trim();
        if (base.isEmpty()) {
            return name;
        }
        return (base.endsWith("/") ? base : base + "/") + name + ".png";
    }

    private record Context(Snapshot s, PresenceSettings p, Snapshot.Editor ed, GitInfo git, String lang) {
        Context(Snapshot s, PresenceSettings p, Snapshot.Editor ed, GitInfo git, Languages languages) {
            this(s, p, ed, git, ed == null ? Languages.FALLBACK : languages.iconFor(ed.fileName()));
        }
    }

    private static String details(String idling, String editing, String debugging, Context c) {
        if (c.ed == null) {
            return idling == null ? null : idling.replace("{empty}", Activity.FAKE_EMPTY);
        }
        String raw = c.s.debugging() ? debugging : editing;
        return expand(raw, c);
    }

    private static String expand(String raw, Context c) {
        Snapshot.Editor ed = c.ed;
        Snapshot s = c.s;
        NumberFormat n = NumberFormat.getIntegerInstance();

        String noProject = c.p.lowerDetailsNoWorkspaceFound().replace("{empty}", Activity.FAKE_EMPTY);
        String folder = ed.projectName() != null ? ed.projectName() : noProject;
        String workspaceAndFolder = s.workspaceName() + (folder.equals(Activity.FAKE_EMPTY) ? "" : " - " + folder);

        String out = raw;
        if (out.contains("{total_lines}")) {
            out = out.replace("{total_lines}", ed.totalLines() < 0 ? "?" : n.format(ed.totalLines()));
        }
        if (out.contains("{current_line}")) {
            out = out.replace("{current_line}", ed.line() < 0 ? "?" : n.format(ed.line()));
        }
        if (out.contains("{current_column}")) {
            out = out.replace("{current_column}", ed.column() < 0 ? "?" : n.format(ed.column()));
        }
        if (out.contains("{current_errors}")) {
            out = out.replace("{current_errors}", n.format(ed.errors()));
        }
        if (out.contains("{file_size}")) {
            out = out.replace("{file_size}", fileSize(ed.filePath()));
        }
        if (out.contains("{git_branch}")) {
            out = out.replace("{git_branch}", c.git == null ? UNKNOWN : nonEmpty(c.git.branch()));
        }
        if (out.contains("{git_repo_name}")) {
            out = out.replace("{git_repo_name}", c.git == null ? UNKNOWN : nonEmpty(c.git.repoName()));
        }
        return out
                .replace("{empty}", Activity.FAKE_EMPTY)
                .replace("{app_name}", s.appName())
                .replace("{file_name}", ed.fileName())
                .replace("{dir_name}", dirName(ed))
                .replace("{full_dir_name}", fullDirName(ed))
                .replace("{workspace_and_folder}", workspaceAndFolder)
                .replace("{workspace_folder}", folder)
                .replace("{workspace}", s.workspaceName())
                .replace("{project}", folder)
                .replace("{lang}", c.lang.toLowerCase(Locale.ROOT))
                .replace("{Lang}", title(c.lang))
                .replace("{LANG}", c.lang.toUpperCase(Locale.ROOT));
    }

    private static String language(String template, String lang) {
        return template
                .replace("{lang}", lang.toLowerCase(Locale.ROOT))
                .replace("{Lang}", title(lang))
                .replace("{LANG}", lang.toUpperCase(Locale.ROOT));
    }

    private static String title(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        return lower.isEmpty() ? lower : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static String dirName(Snapshot.Editor ed) {
        if (ed.filePath() != null) {
            Path parent = Path.of(ed.filePath()).getParent();
            if (parent != null && parent.getFileName() != null) {
                return parent.getFileName().toString();
            }
        }
        String rel = ed.projectRelativeDir();
        if (rel != null && !rel.isEmpty()) {
            int sep = Math.max(rel.lastIndexOf('/'), rel.lastIndexOf('\\'));
            return rel.substring(sep + 1);
        }
        return ed.projectName() != null ? ed.projectName() : Activity.FAKE_EMPTY;
    }

    private static String fullDirName(Snapshot.Editor ed) {
        if (ed.projectName() != null) {
            String rel = ed.projectRelativeDir();
            return rel == null || rel.isEmpty() ? ed.projectName() : ed.projectName() + File.separator + rel;
        }
        if (ed.filePath() != null) {
            Path parent = Path.of(ed.filePath()).getParent();
            if (parent != null) {
                return parent.toString();
            }
        }
        return Activity.FAKE_EMPTY;
    }

    /** discord-vscode's formatting: decimal units, two decimals once past 1,000 bytes. */
    static String fileSize(String filePath) {
        if (filePath == null) {
            return "?";
        }
        long original;
        try {
            original = Files.size(Path.of(filePath));
        } catch (IOException | RuntimeException e) {
            return "?";
        }
        if (original <= 1_000) {
            return original + FILE_SIZES[0];
        }
        double size = original / 1_000.0;
        int division = 1;
        while (size > 1_000 && division < FILE_SIZES.length - 1) {
            size /= 1_000;
            division++;
        }
        return String.format(Locale.ROOT, "%.2f", size) + FILE_SIZES[division];
    }

    private static GitInfo gitFor(Snapshot.Editor ed) {
        if (ed.filePath() != null) {
            Path parent = Path.of(ed.filePath()).getParent();
            if (parent != null) {
                return GitInfo.find(parent);
            }
        }
        return ed.projectPath() != null ? GitInfo.find(Path.of(ed.projectPath())) : null;
    }

    private static boolean excluded(Snapshot s, PresenceSettings p) {
        String pattern = p.excludePattern();
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        Pattern re;
        try {
            re = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return false; // the preference page rejects these; a stale bad value just excludes nothing
        }
        if (s.workspacePath() != null && re.matcher(s.workspacePath()).find()) {
            return true;
        }
        Snapshot.Editor ed = s.editor();
        return ed != null && ed.projectPath() != null && re.matcher(ed.projectPath()).find();
    }

    private static String nonEmpty(String s) {
        return s == null || s.isEmpty() ? Activity.FAKE_EMPTY : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
