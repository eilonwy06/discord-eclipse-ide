package com.eilonwy.discord.eclipse.ui;

import java.lang.reflect.Field;

import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;

/**
 * Fixes the Discord view's zoom to the level set in the preferences and takes zooming out of the
 * user's hands: Ctrl+scroll, Ctrl+plus/minus and Ctrl+0 no longer change it.
 *
 * <p>On Windows the view is WebView2, and both halves are WebView2 controller settings:
 * {@code IsZoomControlEnabled = false} stops user zoom, and {@code ZoomFactor} sets the level. A
 * host-set zoom factor becomes the WebView's default and carries across navigations. SWT wraps the
 * first but not the second, so {@code put_ZoomFactor} is called through SWT's raw COM vtable call.
 * Its slot, 8, follows from the order of {@code ICoreWebView2Controller}: IUnknown takes 0–2, then
 * get/put_IsVisible 3–4, get/put_Bounds 5–6, get/put_ZoomFactor 7–8. SWT's own wrappers sit on the
 * same table at 4 (put_IsVisible), 6 (put_Bounds) and 12 (MoveFocus).
 *
 * <p>Note that {@link Browser#getWebBrowser()} returns {@code null} for Edge, which does not
 * override it, so the Edge object is read from the browser's {@code webBrowser} field instead.
 *
 * <p>Like {@link WebViewInput}, this uses SWT internals through reflection so the class loads on
 * every platform. On macOS and Linux, or if SWT moves the internals, it does nothing and the
 * engine's own zoom behaviour stays.
 */
final class WebViewZoom {

    /** Preference key: the Discord view's zoom, in percent. */
    static final String PREF = "discordViewZoom";

    /**
     * WebView2 is Chromium, which only renders page zoom between 25% and 500% and normalizes
     * anything outside that range into it. The preference accepts exactly that range.
     */
    static final int MIN = 25;
    static final int MAX = 500;
    static final int DEFAULT = 100;

    private static final boolean WIN32 = "win32".equals(SWT.getPlatform());
    private static final int PUT_ZOOM_FACTOR = 8;

    private WebViewZoom() {
    }

    static int clamp(int percent) {
        return Math.max(MIN, Math.min(MAX, percent));
    }

    /**
     * @return {@code true} once applied; {@code false} if the WebView isn't ready yet (its
     *         controller is created asynchronously) or this isn't WebView2, so callers can retry
     */
    static boolean apply(Browser browser, int percent) {
        if (!WIN32 || browser == null || browser.isDisposed()) {
            return false;
        }
        try {
            Object edge = field(Browser.class, "webBrowser").get(browser);
            if (edge == null || !edge.getClass().getName().endsWith(".Edge")) {
                return false;
            }
            Object settings = field(edge.getClass(), "settings").get(edge);
            Object controller = field(edge.getClass(), "controller").get(edge);
            if (settings == null || controller == null) {
                return false;
            }
            settings.getClass().getMethod("put_IsZoomControlEnabled", boolean.class).invoke(settings, false);

            long address = (long) controller.getClass().getMethod("getAddress").invoke(controller);
            Class<?> com = Class.forName("org.eclipse.swt.internal.ole.win32.COM", true, Browser.class.getClassLoader());
            int hr = (int) com.getMethod("VtblCall", int.class, long.class, double.class)
                    .invoke(null, PUT_ZOOM_FACTOR, address, clamp(percent) / 100.0);
            return hr == 0; // S_OK
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
