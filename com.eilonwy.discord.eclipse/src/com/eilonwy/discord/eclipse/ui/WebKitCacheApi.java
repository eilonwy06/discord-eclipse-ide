package com.eilonwy.discord.eclipse.ui;

import java.lang.reflect.Method;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;

/**
 * Turns on the Cache Storage API ({@code window.caches}) for SWT's browser on macOS.
 *
 * <p>There SWT ignores {@link SWT#EDGE} and uses WebKit's legacy {@code WebView}, which ships with
 * the Cache API off. Discord's WASM loader calls {@code caches.open} during startup, throws, and the
 * app never mounts, leaving a white page. Every SWT browser in the process shares WebKit's standard
 * preferences, so this enables it for all of them, and WebKit saves the setting in Eclipse's user
 * defaults. Both are harmless: it only exposes a standard web API.
 *
 * <p>Like {@link WebViewInput}, it uses SWT's internal Cocoa bindings through reflection so the
 * class loads on every platform. Elsewhere, or if SWT moves the internals, it does nothing.
 */
final class WebKitCacheApi {

    private static final boolean COCOA = "cocoa".equals(SWT.getPlatform());

    private static boolean done;

    private WebKitCacheApi() {
    }

    /** Call before the first page loads. */
    static synchronized void enable() {
        if (!COCOA || done) {
            return;
        }
        done = true;
        try {
            // Display lives in the platform fragment, so its loader can see the cocoa internals.
            ClassLoader loader = Display.class.getClassLoader();
            Class<?> os = Class.forName("org.eclipse.swt.internal.cocoa.OS", true, loader);
            Class<?> prefsClass = Class.forName("org.eclipse.swt.internal.cocoa.WebPreferences", true, loader);
            Method sel = os.getMethod("sel_registerName", String.class);
            Method msgSendLong = os.getMethod("objc_msgSend", long.class, long.class, long.class);
            Method msgSendBool = os.getMethod("objc_msgSend", long.class, long.class, boolean.class);

            Object standard = prefsClass.getMethod("standardPreferences").invoke(null);
            long prefs = prefsClass.getField("id").getLong(standard);
            long setter = (long) sel.invoke(null, "setCacheAPIEnabled:");
            long responds = (long) msgSendLong.invoke(null, prefs, (long) sel.invoke(null, "respondsToSelector:"), setter);
            if (prefs != 0 && responds != 0) {
                msgSendBool.invoke(null, prefs, setter, true);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // leave WebKit as it is; Discord stays blank but nothing else is affected
        }
    }
}
