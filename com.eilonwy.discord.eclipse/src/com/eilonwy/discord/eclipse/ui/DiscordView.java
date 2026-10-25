package com.eilonwy.discord.eclipse.ui;

import java.net.URI;
import java.util.Locale;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.LocationEvent;
import org.eclipse.swt.browser.LocationListener;
import org.eclipse.swt.browser.ProgressAdapter;
import org.eclipse.swt.browser.ProgressEvent;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.ISharedImages;
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
 * <p>It needs Edge (WebView2). Without it there is no fallback, because the Internet Explorer engine
 * cannot run Discord. The sign-in is kept in SWT's WebView2 profile, which every Edge browser in this
 * Eclipse shares, so it is meant to survive a restart.
 */
public class DiscordView extends ViewPart {

    public static final String ID = "com.eilonwy.discord.eclipse.views.discord";

    static final String HOME = "https://discord.com/app";

    private static final int POPUP_TIMEOUT_MS = 10_000;

    private Browser browser;

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

        buildActions();
        browser.setUrl(HOME);
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

    private void buildActions() {
        ISharedImages images = PlatformUI.getWorkbench().getSharedImages();
        IToolBarManager tb = getViewSite().getActionBars().getToolBarManager();
        IMenuManager menu = getViewSite().getActionBars().getMenuManager();

        Action back = new Action("Back") {
            @Override
            public void run() {
                browser.back();
            }
        };
        back.setImageDescriptor(images.getImageDescriptor(ISharedImages.IMG_TOOL_BACK));

        Action forward = new Action("Forward") {
            @Override
            public void run() {
                browser.forward();
            }
        };
        forward.setImageDescriptor(images.getImageDescriptor(ISharedImages.IMG_TOOL_FORWARD));

        Action reload = new Action("Reload") {
            @Override
            public void run() {
                browser.refresh();
            }
        };
        reload.setImageDescriptor(images.getImageDescriptor(ISharedImages.IMG_ELCL_SYNCED));

        Action home = new Action("Home") {
            @Override
            public void run() {
                browser.setUrl(HOME);
            }
        };
        home.setImageDescriptor(images.getImageDescriptor(ISharedImages.IMG_ETOOL_HOME_NAV));

        Action external = new Action("Open in System Browser") {
            @Override
            public void run() {
                String url = browser.getUrl();
                openExternal(url == null || url.isBlank() || url.startsWith("about:") ? HOME : url);
            }
        };

        tb.add(back);
        tb.add(forward);
        tb.add(reload);
        tb.add(home);
        menu.add(external);
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
}
