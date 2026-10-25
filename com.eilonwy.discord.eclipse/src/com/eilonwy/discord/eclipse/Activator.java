package com.eilonwy.discord.eclipse;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

public class Activator extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "com.eilonwy.discord.eclipse";

    private static Activator plugin;

    private PresenceService presence;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        PresenceService p;
        synchronized (this) {
            p = presence;
            presence = null;
        }
        if (p != null) {
            p.stop();
        }
        plugin = null;
        super.stop(context);
    }

    public static Activator getDefault() {
        return plugin;
    }

    /**
     * The Rich Presence service, started on first use: normally by {@link Startup}, or by the
     * status bar item if the workbench builds that first.
     */
    public synchronized PresenceService presence() {
        if (presence == null) {
            presence = new PresenceService(getPreferenceStore());
            presence.start();
        }
        return presence;
    }

    public static void log(int severity, String message) {
        Activator p = plugin;
        if (p != null) {
            p.getLog().log(new Status(severity, PLUGIN_ID, message));
        }
    }

    public static void logError(String message, Throwable t) {
        Activator p = plugin;
        if (p != null) {
            p.getLog().log(new Status(IStatus.ERROR, PLUGIN_ID, message, t));
        }
    }
}
