package com.eilonwy.discord.eclipse.ui;

import java.lang.reflect.Method;

import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;

/**
 * Makes an Edge (WebView2) {@link Browser} accept typing reliably on Windows.
 *
 * <p>SWT's window procedure only forwards key messages while the Browser holds SWT focus, so inside
 * a workbench view WebView2 intermittently refuses keyboard input. The cure is to give Win32 focus
 * straight to the deepest child window (WebView2's own render window) and post a harmless Shift
 * press and release so it starts taking keys. This class does that in pure Java through SWT's
 * internal {@code OS} bindings, so the plugin needs no native library.
 *
 * <p>Reflection keeps the class loadable on every platform. Elsewhere, or if a future SWT moves the
 * internals, {@link #activate} still gives SWT focus and skips the rest.
 */
final class WebViewInput {

    private static final boolean WINDOWS = "win32".equals(SWT.getPlatform());

    private static Method getWindow;
    private static Method setFocus;
    private static Method postMessage;
    private static int gwChild;
    private static int wmKeyDown;
    private static int wmKeyUp;
    private static int vkShift;
    private static boolean resolved;
    private static boolean available;

    private WebViewInput() {
    }

    static void activate(Browser browser) {
        if (browser == null || browser.isDisposed()) {
            return;
        }
        browser.forceFocus();
        if (!WINDOWS || !resolve()) {
            return;
        }
        try {
            long target = handle(browser);
            if (target == 0) {
                return;
            }
            while (true) {
                long child = (long) getWindow.invoke(null, target, gwChild);
                if (child == 0) {
                    break;
                }
                target = child;
            }
            setFocus.invoke(null, target);
            // lParam for VK_SHIFT: scan code 0x2A, repeat count 1; key-up also sets the
            // previous-state and transition bits.
            long down = (0x2AL << 16) | 1;
            long up = down | (3L << 30);
            postMessage.invoke(null, target, wmKeyDown, (long) vkShift, down);
            postMessage.invoke(null, target, wmKeyUp, (long) vkShift, up);
        } catch (ReflectiveOperationException | RuntimeException e) {
            available = false; // stop trying; SWT focus alone still works most of the time
        }
    }

    private static synchronized boolean resolve() {
        if (resolved) {
            return available;
        }
        resolved = true;
        try {
            // Display lives in the platform fragment, so its loader can see the win32 internals.
            Class<?> os = Class.forName("org.eclipse.swt.internal.win32.OS", true, Display.class.getClassLoader());
            getWindow = os.getMethod("GetWindow", long.class, int.class);
            setFocus = os.getMethod("SetFocus", long.class);
            postMessage = os.getMethod("PostMessage", long.class, int.class, long.class, long.class);
            gwChild = os.getField("GW_CHILD").getInt(null);
            wmKeyDown = os.getField("WM_KEYDOWN").getInt(null);
            wmKeyUp = os.getField("WM_KEYUP").getInt(null);
            vkShift = os.getField("VK_SHIFT").getInt(null);
            available = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            available = false;
        }
        return available;
    }

    private static long handle(Control control) throws ReflectiveOperationException {
        Object v = Control.class.getField("handle").get(control);
        if (v instanceof Long l) {
            return l;
        }
        if (v instanceof Integer i) {
            return i;
        }
        return 0;
    }
}
