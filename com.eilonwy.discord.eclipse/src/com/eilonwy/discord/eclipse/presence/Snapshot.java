package com.eilonwy.discord.eclipse.presence;

/**
 * What the workbench looks like at one moment, captured on the UI thread from the Eclipse model
 * only. Anything that needs the disk (file size, Git) is resolved later, off the UI thread, by
 * {@link ActivityBuilder}.
 *
 * @param editor the active editor, or {@code null} when none is open (discord-vscode's "idling")
 */
public record Snapshot(
        String appName,
        String workspaceName,
        String workspacePath,
        boolean debugging,
        Editor editor) {

    /**
     * @param filePath           absolute path on disk, or {@code null} for inputs that are not files
     *                           (a class file from a JAR, a compare editor)
     * @param projectName        {@code null} when the file is outside every workspace project
     * @param projectRelativeDir the file's folder relative to its project, "" at the project root
     * @param totalLines         -1 when the editor is not a text editor
     * @param line               1-based caret line, -1 when unknown
     * @param column             1-based caret column, -1 when unknown
     * @param errors             error markers on the file
     */
    public record Editor(
            String fileName,
            String filePath,
            String projectName,
            String projectPath,
            String projectRelativeDir,
            int totalLines,
            int line,
            int column,
            int errors) {
    }
}
