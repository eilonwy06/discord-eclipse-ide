package com.eilonwy.discord.eclipse;

import org.eclipse.jface.preference.IPreferenceStore;

import com.eilonwy.discord.eclipse.presence.PresenceSettings;

/** Preference keys for {@link PresenceSettings}, named after discord-vscode's {@code discord.*} settings. */
public final class PresencePreferences {

    public static final String ENABLED = "enabled";
    public static final String APPLICATION_ID = "applicationId";
    public static final String DETAILS_IDLING = "detailsIdling";
    public static final String DETAILS_EDITING = "detailsEditing";
    public static final String DETAILS_DEBUGGING = "detailsDebugging";
    public static final String LOWER_DETAILS_EDITING = "lowerDetailsEditing";
    public static final String LOWER_DETAILS_DEBUGGING = "lowerDetailsDebugging";
    public static final String LOWER_DETAILS_NO_WORKSPACE_FOUND = "lowerDetailsNoWorkspaceFound";
    public static final String LARGE_IMAGE_IDLING = "largeImageIdling";
    public static final String LARGE_IMAGE = "largeImage";
    public static final String SMALL_IMAGE = "smallImage";
    public static final String SWAP_BIG_AND_SMALL_IMAGE = "swapBigAndSmallImage";
    public static final String REMOVE_DETAILS = "removeDetails";
    public static final String REMOVE_LOWER_DETAILS = "removeLowerDetails";
    public static final String REMOVE_TIMESTAMP = "removeTimestamp";
    public static final String REMOVE_REMOTE_REPOSITORY = "removeRemoteRepository";
    public static final String IDLE_TIMEOUT = "idleTimeout";
    public static final String EXCLUDE_PATTERN = "excludePattern";
    public static final String ICON_BASE_URL = "iconBaseUrl";
    public static final String APP_IMAGE = "appImage";

    private PresencePreferences() {
    }

    public static void setDefaults(IPreferenceStore store) {
        PresenceSettings d = PresenceSettings.defaults();
        store.setDefault(ENABLED, d.enabled());
        store.setDefault(APPLICATION_ID, d.applicationId());
        store.setDefault(DETAILS_IDLING, d.detailsIdling());
        store.setDefault(DETAILS_EDITING, d.detailsEditing());
        store.setDefault(DETAILS_DEBUGGING, d.detailsDebugging());
        store.setDefault(LOWER_DETAILS_EDITING, d.lowerDetailsEditing());
        store.setDefault(LOWER_DETAILS_DEBUGGING, d.lowerDetailsDebugging());
        store.setDefault(LOWER_DETAILS_NO_WORKSPACE_FOUND, d.lowerDetailsNoWorkspaceFound());
        store.setDefault(LARGE_IMAGE_IDLING, d.largeImageIdling());
        store.setDefault(LARGE_IMAGE, d.largeImage());
        store.setDefault(SMALL_IMAGE, d.smallImage());
        store.setDefault(SWAP_BIG_AND_SMALL_IMAGE, d.swapBigAndSmallImage());
        store.setDefault(REMOVE_DETAILS, d.removeDetails());
        store.setDefault(REMOVE_LOWER_DETAILS, d.removeLowerDetails());
        store.setDefault(REMOVE_TIMESTAMP, d.removeTimestamp());
        store.setDefault(REMOVE_REMOTE_REPOSITORY, d.removeRemoteRepository());
        store.setDefault(IDLE_TIMEOUT, d.idleTimeout());
        store.setDefault(EXCLUDE_PATTERN, d.excludePattern());
        store.setDefault(ICON_BASE_URL, d.iconBaseUrl());
        store.setDefault(APP_IMAGE, d.appImage());
    }

    public static PresenceSettings read(IPreferenceStore s) {
        return new PresenceSettings(
                s.getBoolean(ENABLED),
                s.getString(APPLICATION_ID).trim(),
                s.getString(DETAILS_IDLING),
                s.getString(DETAILS_EDITING),
                s.getString(DETAILS_DEBUGGING),
                s.getString(LOWER_DETAILS_EDITING),
                s.getString(LOWER_DETAILS_DEBUGGING),
                s.getString(LOWER_DETAILS_NO_WORKSPACE_FOUND),
                s.getString(LARGE_IMAGE_IDLING),
                s.getString(LARGE_IMAGE),
                s.getString(SMALL_IMAGE),
                s.getBoolean(SWAP_BIG_AND_SMALL_IMAGE),
                s.getBoolean(REMOVE_DETAILS),
                s.getBoolean(REMOVE_LOWER_DETAILS),
                s.getBoolean(REMOVE_TIMESTAMP),
                s.getBoolean(REMOVE_REMOTE_REPOSITORY),
                Math.max(0, s.getInt(IDLE_TIMEOUT)),
                s.getString(EXCLUDE_PATTERN),
                s.getString(ICON_BASE_URL),
                s.getString(APP_IMAGE).trim());
    }
}
