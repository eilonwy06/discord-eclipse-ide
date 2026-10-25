package com.eilonwy.discord.eclipse.ui;

import java.net.URI;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.browser.LocationEvent;
import org.eclipse.swt.browser.LocationListener;
import org.eclipse.swt.browser.ProgressAdapter;
import org.eclipse.swt.browser.ProgressEvent;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;

/**
 * Discord's own web client in a workbench view.
 *
 * <p>This shows discord.com exactly as a browser would and adds nothing to the page: no injected
 * scripts and no automation of the signed-in account, which Discord's terms forbid. Its only rules
 * are about where navigation goes. Discord stays in the view; anything else opens in the system
 * browser, the way the desktop app treats outside links.
 *
 * <p>Before loading, the view checks that Discord can be reached. If it cannot, it shows its own
 * offline page instead of WebView2's generic error, and keeps checking until the connection is
 * back. Once Discord is loaded, dropped connections are Discord's to handle: its client shows its
 * own reconnecting state.
 *
 * <p>It needs Edge (WebView2). Without it there is no fallback, because the Internet Explorer engine
 * cannot run Discord. The sign-in is kept in SWT's WebView2 profile, which every Edge browser in this
 * Eclipse shares, so it is meant to survive a restart.
 */
public class DiscordView extends ViewPart {

    public static final String ID = "com.eilonwy.discord.eclipse.views.discord";

    static final String HOME = "https://discord.com/app";

    private static final int POPUP_TIMEOUT_MS = 10_000;
    private static final int OFFLINE_RETRY_MS = 10_000;

    private Browser browser;
    @SuppressWarnings("unused") // a strong reference keeps the function registered
    private BrowserFunction retryFunction;
    private boolean offline;
    private boolean checking;
    private final Runnable retry = () -> {
        if (offline) {
            load();
        }
    };

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new FillLayout());
        try {
            browser = new Browser(parent, SWT.EDGE);
        } catch (SWTError e) {
            showUnavailable(parent, e);
            return;
        }

        browser.addLocationListener(LocationListener.changingAdapter(this::keepDiscordInside));
        browser.addOpenWindowListener(event -> {
            Browser popup = capturePopup();
            if (popup != null) {
                event.browser = popup;
            } else {
                event.required = true; // no hidden browser to catch it in: block rather than float a bare WebView2 window
            }
        });
        browser.addProgressListener(new ProgressAdapter() {
            @Override
            public void completed(ProgressEvent event) {
                // WebView2 creates its windows asynchronously; retry until the render window exists.
                Display d = browser.getDisplay();
                for (int ms : new int[] { 50, 200, 500, 1000, 1500 }) {
                    d.timerExec(ms, () -> WebViewInput.activate(browser));
                }
            }
        });
        retryFunction = new BrowserFunction(browser, "discordRetry") {
            @Override
            public Object function(Object[] arguments) {
                // Leave the page's own callback before navigating away from it.
                browser.getDisplay().asyncExec(DiscordView.this::load);
                return null;
            }
        };

        load();
    }

    /** Loads Discord if it can be reached, otherwise shows the offline page and retries. */
    private void load() {
        if (browser == null || browser.isDisposed() || checking) {
            return;
        }
        checking = true;
        Display d = browser.getDisplay();
        CompletableFuture.supplyAsync(() -> Connectivity.reachable(Connectivity.DISCORD))
                .thenAccept(online -> d.asyncExec(() -> {
                    checking = false;
                    if (browser == null || browser.isDisposed()) {
                        return;
                    }
                    d.timerExec(-1, retry);
                    if (online) {
                        offline = false;
                        browser.setUrl(HOME);
                    } else {
                        offline = true;
                        browser.setText(OfflinePage.render(isDark()));
                        d.timerExec(OFFLINE_RETRY_MS, retry);
                    }
                }));
    }

    /** Follows the workbench theme rather than the OS, from the live view background. */
    private boolean isDark() {
        RGB bg = browser.getParent().getBackground().getRGB();
        return (bg.red * 299 + bg.green * 587 + bg.blue * 114) / 1000 < 128;
    }

    private void showUnavailable(Composite parent, SWTError e) {
        Composite box = new Composite(parent, SWT.NONE);
        box.setLayout(new GridLayout(1, false));
        Label l = new Label(box, SWT.WRAP);
        l.setText("Discord needs the Microsoft Edge WebView2 runtime, which SWT could not start here:\n"
                + e.getMessage()
                + "\n\nInstall WebView2 from Microsoft, or use Discord in your browser at " + HOME + ".");
        l.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
    }

    private void keepDiscordInside(LocationEvent event) {
        // Frames are left alone: the sign-in captcha and embeds load in iframes from other hosts.
        if (!event.top || isInside(event.location)) {
            return;
        }
        event.doit = false;
        openExternal(event.location);
    }

    /**
     * Discord opens outside links with {@code window.open} or {@code target=_blank}, which arrives
     * as a new-window request that carries no URL. A hidden browser takes the new window, its first
     * real navigation reveals the URL, and the hidden browser is thrown away.
     */
    private Browser capturePopup() {
        Shell shell = new Shell(browser.getShell(), SWT.NO_TRIM);
        Browser popup;
        try {
            popup = new Browser(shell, SWT.EDGE);
        } catch (SWTError e) {
            shell.dispose();
            return null;
        }
        Display d = shell.getDisplay();
        popup.addLocationListener(LocationListener.changingAdapter(e -> {
            String url = e.location;
            if (url == null || url.isEmpty() || url.startsWith("about:")) {
                return;
            }
            e.doit = false;
            d.asyncExec(() -> {
                if (isInside(url) && browser != null && !browser.isDisposed()) {
                    browser.setUrl(url);
                } else {
                    openExternal(url);
                }
                if (!shell.isDisposed()) {
                    shell.dispose();
                }
            });
        }));
        d.timerExec(POPUP_TIMEOUT_MS, () -> {
            if (!shell.isDisposed()) {
                shell.dispose();
            }
        });
        return popup;
    }

    static boolean isInside(String location) {
        if (location == null || location.isEmpty()) {
            return true;
        }
        String lower = location.toLowerCase(Locale.ROOT);
        if (lower.startsWith("about:") || lower.startsWith("data:") || lower.startsWith("blob:")
                || lower.startsWith("javascript:")) {
            return true;
        }
        String host;
        try {
            URI uri = new URI(location);
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            host = uri.getHost();
        } catch (Exception e) {
            return false;
        }
        if (host == null) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        for (String d : new String[] { "discord.com", "discordapp.com", "discord.gg", "discord.new", "discordapp.net" }) {
            if (host.equals(d) || host.endsWith("." + d)) {
                return true;
            }
        }
        return false;
    }

    static void openExternal(String url) {
        try {
            PlatformUI.getWorkbench().getBrowserSupport().getExternalBrowser().openURL(URI.create(url).toURL());
        } catch (Exception e) {
            Program.launch(url);
        }
    }

    @Override
    public void setFocus() {
        if (browser != null && !browser.isDisposed()) {
            WebViewInput.activate(browser);
        }
    }

    @Override
    public void dispose() {
        if (browser != null && !browser.isDisposed()) {
            browser.getDisplay().timerExec(-1, retry);
        }
        offline = false;
        super.dispose();
    }
}
