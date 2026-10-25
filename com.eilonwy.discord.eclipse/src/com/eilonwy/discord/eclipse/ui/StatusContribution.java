package com.eilonwy.discord.eclipse.ui;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

import org.eclipse.e4.ui.model.application.ui.MUIElement;
import org.eclipse.e4.ui.model.application.ui.basic.MWindow;
import org.eclipse.e4.ui.workbench.IPresentationEngine;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageDataProvider;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
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
 * Status bar item showing the Rich Presence connection, like discord-vscode's status bar entry: a
 * coloured dot and a short text. Clicking it offers Reconnect, Enable/Disable, the Discord view
 * and the preferences.
 */
public class StatusContribution extends WorkbenchWindowControlContribution {

    /** The toolbar this item lives in, as declared in plugin.xml. */
    private static final String TOOLBAR_ID = "com.eilonwy.discord.eclipse.trim.status";

    /** Dot diameter at 100% scaling; the trim's own drag-handle dots are about a third of this. */
    private static final int DOT_SIZE = 8;

    /** Discord's own presence colours, so the dot reads the way Discord's status dots do. */
    private enum Dot {
        GREEN(new RGB(0x23, 0xA5, 0x5A)), // online
        ORANGE(new RGB(0xF0, 0xB2, 0x32)), // idle
        RED(new RGB(0xF2, 0x3F, 0x43)), // do not disturb
        GREY(new RGB(0x80, 0x84, 0x8E)); // offline

        final RGB rgb;

        Dot(RGB rgb) {
            this.rgb = rgb;
        }
    }

    private CLabel label;
    private Menu menu;
    private PresenceService service;
    private final Map<Dot, Image> dots = new EnumMap<>(Dot.class);
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
        for (Dot d : Dot.values()) {
            dots.put(d, dotImage(label.getDisplay(), d.rgb));
        }
        label.setImage(dots.get(Dot.GREY)); // every state has a dot, so measure with one
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
        label.addListener(SWT.Dispose, e -> {
            service.removeStatusListener(listener);
            dots.values().forEach(Image::dispose);
            dots.clear();
        });
        show(service.status());
        label.getDisplay().asyncExec(this::dropDragHandle);
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
        Dot dot;
        switch (s.state()) {
            case DISABLED -> {
                text = "Discord: off";
                tip = "Rich Presence is disabled. Click for options.";
                dot = Dot.GREY;
            }
            case NO_APPLICATION_ID -> {
                text = "Discord: needs setup";
                tip = "Set a Discord Application ID in Preferences > Discord Rich Presence.";
                dot = Dot.GREY;
            }
            case CONNECTING -> {
                text = "Discord: connecting…";
                tip = "Connecting to the Discord desktop app";
                dot = Dot.ORANGE;
            }
            case CONNECTED -> {
                text = s.message() == null ? "Discord: connected" : "Discord: error";
                tip = (s.user() == null ? "Connected to Discord" : "Connected to Discord as " + s.user())
                        + (s.message() == null ? "" : "\n" + s.message());
                dot = s.message() == null ? Dot.GREEN : Dot.RED;
            }
            default -> {
                text = "Discord: disconnected";
                tip = (s.message() == null ? "Not connected to Discord" : s.message()) + "\nClick for options.";
                dot = Dot.RED;
            }
        }
        label.setImage(dots.get(dot));
        label.setText(text);
        label.setToolTipText(tip);
        label.getShell().layout(new Control[] { label }, SWT.DEFER);
    }

    /**
     * A filled circle with anti-aliased edges and a transparent background, computed per pixel for
     * each display zoom so it stays round and crisp at 125%, 150% and 200% scaling.
     */
    private static Image dotImage(Display display, RGB color) {
        ImageDataProvider provider = zoom -> {
            int size = Math.max(1, Math.round(DOT_SIZE * zoom / 100f));
            ImageData data = new ImageData(size, size, 24, new PaletteData(0xFF0000, 0x00FF00, 0x0000FF));
            int pixel = data.palette.getPixel(color);
            double r = size / 2.0;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    double dist = Math.hypot(x + 0.5 - r, y + 0.5 - r);
                    double coverage = Math.max(0, Math.min(1, r - dist + 0.5));
                    data.setPixel(x, y, pixel);
                    data.setAlpha(x, y, (int) Math.round(coverage * 255));
                }
            }
            return data;
        };
        return new Image(display, provider);
    }

    /**
     * Eclipse draws a drag handle (a small grey dot) beside every movable status bar item. Next to
     * a coloured status dot it reads as a second, stray dot, so this item opts out of being movable
     * and is drawn once more without it. The renderer adds the handle at creation unless the
     * toolbar is tagged NoMove, hence tag-then-redraw. The tag is kept in the workbench model,
     * so this happens on the first start only. If any of it fails, the handle simply stays.
     */
    private void dropDragHandle() {
        try {
            IWorkbenchWindow window = getWorkbenchWindow();
            if (window == null) {
                return;
            }
            MWindow model = window.getService(MWindow.class);
            EModelService models = window.getService(EModelService.class);
            if (model == null || models == null) {
                return;
            }
            MUIElement toolbar = models.find(TOOLBAR_ID, model);
            if (toolbar == null || toolbar.getTags().contains(IPresentationEngine.NO_MOVE)) {
                return;
            }
            toolbar.getTags().add(IPresentationEngine.NO_MOVE);
            toolbar.getTags().remove(IPresentationEngine.DRAGGABLE);
            toolbar.setToBeRendered(false);
            toolbar.setToBeRendered(true);
        } catch (RuntimeException e) {
            Activator.logError("Could not remove the status bar drag handle", e);
        }
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
