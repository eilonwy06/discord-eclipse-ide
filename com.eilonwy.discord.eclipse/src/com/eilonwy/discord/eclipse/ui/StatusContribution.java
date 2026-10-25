package com.eilonwy.discord.eclipse.ui;

import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.menus.WorkbenchWindowControlContribution;

import com.eilonwy.discord.eclipse.Activator;
import com.eilonwy.discord.eclipse.PresenceService;
import com.eilonwy.discord.eclipse.rpc.PresenceClient;

/**
 * Status bar item showing the Rich Presence connection, like discord-vscode's status bar entry.
 * Clicking it offers Reconnect, Enable/Disable, the Discord view and the preferences.
 */
public class StatusContribution extends WorkbenchWindowControlContribution {

    private CLabel label;
    private Menu menu;
    private PresenceService service;
    private final Consumer<PresenceClient.Status> listener = this::show;

    /**
     * The status trim measures an item once, when it is created, and never again. Starting as
     * "Discord: off" would leave every later state clipped ("Disc…cted"), so the label sits in a
     * container whose width is fixed up front to fit the longest text.
     */
    @Override
    protected Control createControl(Composite parent) {
        Composite box = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        box.setLayout(layout);

        label = new CLabel(box, SWT.NONE);
        int widest = 0;
        for (String t : ALL_TEXTS) {
            label.setText(t);
            widest = Math.max(widest, label.computeSize(SWT.DEFAULT, SWT.DEFAULT, true).x);
        }
        GridData data = new GridData(SWT.FILL, SWT.CENTER, false, true);
        data.widthHint = widest;
        label.setLayoutData(data);

        service = Activator.getDefault().presence();
        service.addStatusListener(listener);
        label.addListener(SWT.MouseUp, e -> openMenu());
        label.addListener(SWT.Dispose, e -> service.removeStatusListener(listener));
        show(service.status());
        return box;
    }

    private static final String[] ALL_TEXTS = { "Discord: off", "Discord: needs setup", "Discord: connecting…",
            "Discord: connected", "Discord: error", "Discord: disconnected" };

    private void show(PresenceClient.Status s) {
        if (label == null || label.isDisposed()) {
            return;
        }
        String text;
        String tip;
        switch (s.state()) {
            case DISABLED -> {
                text = "Discord: off";
                tip = "Rich Presence is disabled. Click for options.";
            }
            case NO_APPLICATION_ID -> {
                text = "Discord: needs setup";
                tip = "Set a Discord Application ID in Preferences > Discord Rich Presence.";
            }
            case CONNECTING -> {
                text = "Discord: connecting…";
                tip = "Connecting to the Discord desktop app";
            }
            case CONNECTED -> {
                text = s.message() == null ? "Discord: connected" : "Discord: error";
                tip = (s.user() == null ? "Connected to Discord" : "Connected to Discord as " + s.user())
                        + (s.message() == null ? "" : "\n" + s.message());
            }
            default -> {
                text = "Discord: disconnected";
                tip = (s.message() == null ? "Not connected to Discord" : s.message()) + "\nClick for options.";
            }
        }
        label.setText(text);
        label.setToolTipText(tip);
        label.getShell().layout(new Control[] { label }, SWT.DEFER);
    }

    private void openMenu() {
        IWorkbenchWindow window = getWorkbenchWindow();
        // One menu, owned by the label and disposed with it; only its items are rebuilt per click.
        if (menu == null || menu.isDisposed()) {
            menu = new Menu(label);
        }
        for (MenuItem old : menu.getItems()) {
            old.dispose();
        }
        boolean enabled = service.isEnabled();

        MenuItem reconnect = new MenuItem(menu, SWT.PUSH);
        reconnect.setText("Reconnect to Discord");
        reconnect.setEnabled(enabled);
        reconnect.addListener(SWT.Selection, e -> service.reconnect());

        MenuItem toggle = new MenuItem(menu, SWT.PUSH);
        toggle.setText(enabled ? "Disable Rich Presence" : "Enable Rich Presence");
        toggle.addListener(SWT.Selection, e -> service.setEnabled(!enabled));

        new MenuItem(menu, SWT.SEPARATOR);

        MenuItem view = new MenuItem(menu, SWT.PUSH);
        view.setText("Open Discord");
        view.addListener(SWT.Selection, e -> {
            IWorkbenchPage page = window == null ? null : window.getActivePage();
            if (page != null) {
                try {
                    page.showView(DiscordView.ID);
                } catch (PartInitException ex) {
                    Activator.logError("Could not open the Discord view", ex);
                }
            }
        });

        MenuItem prefs = new MenuItem(menu, SWT.PUSH);
        prefs.setText("Preferences…");
        prefs.addListener(SWT.Selection, e -> PreferencesUtil
                .createPreferenceDialogOn(label.getShell(), PresencePreferencePage.ID, null, null).open());

        Point at = label.toDisplay(0, label.getSize().y);
        menu.setLocation(at);
        menu.setVisible(true);
    }

    @Override
    public void dispose() {
        if (service != null) {
            service.removeStatusListener(listener);
        }
        super.dispose();
    }
}
