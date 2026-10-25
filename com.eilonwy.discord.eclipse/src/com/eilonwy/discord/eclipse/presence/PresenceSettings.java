package com.eilonwy.discord.eclipse.presence;

/**
 * Every user-tunable Rich Presence option. Names and defaults follow discord-vscode's
 * {@code discord.*} settings so its documentation carries over. The two deliberate differences are
 * that the lower line defaults to the Eclipse project rather than the workspace, because an Eclipse
 * workspace holds many projects, and that images are configurable, because Eclipse cannot reuse
 * discord-vscode's Discord application or its uploaded art.
 *
 * @param applicationId  the Discord application to show as, from the Developer Portal
 * @param idleTimeout    seconds the Eclipse window may stay unfocused before the presence is
 *                       cleared; 0 never clears it
 * @param excludePattern regular expression; when it matches the workspace or project location
 *                       nothing is shown
 * @param iconBaseUrl    prefix for language, idle and debug image names; blank sends the bare
 *                       names, as asset keys uploaded to your own Discord application
 * @param appImage       small image naming the IDE: an asset key or a full https URL, used as is
 */
public record PresenceSettings(
        boolean enabled,
        String applicationId,
        String detailsIdling,
        String detailsEditing,
        String detailsDebugging,
        String lowerDetailsEditing,
        String lowerDetailsDebugging,
        String lowerDetailsNoWorkspaceFound,
        String largeImageIdling,
        String largeImage,
        String smallImage,
        boolean swapBigAndSmallImage,
        boolean removeDetails,
        boolean removeLowerDetails,
        boolean removeTimestamp,
        boolean removeRemoteRepository,
        int idleTimeout,
        String excludePattern,
        String iconBaseUrl,
        String appImage) {

    /**
     * discord-vscode's icon set, at the last upstream commit this repository was forked from. It is
     * pinned to that commit so the URLs never move, and served from this fork so they keep working
     * whatever happens upstream. The icons are MIT-licensed; see THIRD-PARTY-NOTICES.md.
     */
    public static final String DEFAULT_ICON_BASE_URL =
            "https://raw.githubusercontent.com/eilonwy06/discord-eclipse-ide/6d5fb18969e1dba1e64f86d7fb758896aed1f1b3/assets/icons/";

    /**
     * The project's own Discord application, named "Eclipse IDE", with the Eclipse logo uploaded
     * as its {@code eclipse} art asset. Shipping it means Rich Presence works with no setup, the
     * way discord-vscode ships its own. An Application ID is a public identifier, not a secret.
     * Users can still enter their own in the preferences.
     */
    public static final String DEFAULT_APPLICATION_ID = "1563766492154175499";

    public static PresenceSettings defaults() {
        return new PresenceSettings(
                true,
                DEFAULT_APPLICATION_ID,
                "Idling",
                "Editing {file_name}",
                "Debugging {file_name}",
                "Project: {project}",
                "Debugging: {project}",
                "No project",
                "Idling",
                "Editing a {LANG} file",
                "{app_name}",
                false,
                false,
                false,
                false,
                false,
                0,
                "",
                DEFAULT_ICON_BASE_URL,
                "eclipse");
    }
}
