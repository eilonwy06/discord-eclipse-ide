package com.eilonwy.discord.eclipse;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProduct;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.ListenerList;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.ILaunchesListener2;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.IURIEditorInput;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

import com.eilonwy.discord.eclipse.presence.ActivityBuilder;
import com.eilonwy.discord.eclipse.presence.Languages;
import com.eilonwy.discord.eclipse.presence.PresenceSettings;
import com.eilonwy.discord.eclipse.presence.Snapshot;
import com.eilonwy.discord.eclipse.rpc.PresenceClient;

/**
 * Watches the workbench and keeps Discord's Rich Presence in step with it, the Eclipse
 * counterpart of discord-vscode's {@code extension.ts}.
 *
 * <p>Three threads are involved and each has one job. The UI thread reads the Eclipse model into a
 * {@link Snapshot}; a builder thread turns that into an activity, which needs the disk for file size
 * and Git; and {@link PresenceClient}'s worker does all pipe I/O. Workbench events are debounced by
 * {@link #REFRESH_DELAY_MS}, so a burst of editor switches or keystrokes becomes one snapshot.
 */
public final class PresenceService {

    private static final int REFRESH_DELAY_MS = 750;

    private final IPreferenceStore store;
    private final PresenceClient client;
    private final ExecutorService builder = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Discord presence builder");
        t.setDaemon(true);
        return t;
    });
    private final ListenerList<Consumer<PresenceClient.Status>> statusListeners = new ListenerList<>();

    private volatile PresenceSettings settings;
    private volatile String lastLoggedStatus;
    private volatile boolean idle;
    private volatile boolean stopped;
    private Languages languages = Languages.empty();
    private Display display;

    // UI thread only.
    private final Set<IWorkbenchWindow> hooked = new HashSet<>();
    private IWorkbenchWindow lastWindow;
    private IDocument hookedDocument;
    private boolean refreshPending;
    private long sessionStart = System.currentTimeMillis();

    private final IPropertyChangeListener preferenceListener = e -> applySettings();

    private final IWindowListener windowListener = new IWindowListener() {
        @Override
        public void windowActivated(IWorkbenchWindow window) {
            lastWindow = window;
            display.timerExec(-1, goIdle);
            if (idle) {
                idle = false;
                sessionStart = System.currentTimeMillis();
            }
            scheduleRefresh();
        }

        @Override
        public void windowDeactivated(IWorkbenchWindow window) {
            int timeout = settings.idleTimeout();
            if (timeout > 0) {
                display.timerExec(timeout * 1_000, goIdle);
            }
        }

        @Override
        public void windowOpened(IWorkbenchWindow window) {
            hookWindow(window);
        }

        @Override
        public void windowClosed(IWorkbenchWindow window) {
            unhookWindow(window);
            if (window == lastWindow) {
                lastWindow = null;
            }
            scheduleRefresh();
        }
    };

    private final IPartListener2 partListener = new IPartListener2() {
        @Override
        public void partActivated(IWorkbenchPartReference ref) {
            scheduleRefresh();
        }

        @Override
        public void partBroughtToTop(IWorkbenchPartReference ref) {
            scheduleRefresh();
        }

        @Override
        public void partClosed(IWorkbenchPartReference ref) {
            scheduleRefresh();
        }

        @Override
        public void partInputChanged(IWorkbenchPartReference ref) {
            scheduleRefresh();
        }
    };

    private final ISelectionListener selectionListener = (part, selection) -> {
        if (selection instanceof ITextSelection && usesToken("{current_line}", "{current_column}")) {
            scheduleRefresh();
        }
    };

    private final IDocumentListener documentListener = new IDocumentListener() {
        @Override
        public void documentAboutToBeChanged(DocumentEvent event) {
        }

        @Override
        public void documentChanged(DocumentEvent event) {
            if (usesToken("{total_lines}", "{file_size}", "{current_line}", "{current_column}")) {
                scheduleRefresh();
            }
        }
    };

    private final ILaunchesListener2 launchListener = new ILaunchesListener2() {
        @Override
        public void launchesTerminated(ILaunch[] launches) {
            scheduleRefresh();
        }

        @Override
        public void launchesRemoved(ILaunch[] launches) {
            scheduleRefresh();
        }

        @Override
        public void launchesAdded(ILaunch[] launches) {
            scheduleRefresh();
        }

        @Override
        public void launchesChanged(ILaunch[] launches) {
            scheduleRefresh();
        }
    };

    private final IResourceChangeListener markerListener = event -> {
        if (usesToken("{current_errors}") && event.findMarkerDeltas(IMarker.PROBLEM, true).length > 0) {
            scheduleRefresh();
        }
    };

    private final Runnable goIdle = this::goIdle;

    private final Runnable refresh = this::refreshNow;

    PresenceService(IPreferenceStore store) {
        this.store = store;
        this.client = new PresenceClient(this::fireStatus);
        this.settings = PresencePreferences.read(store);
    }

    void start() {
        languages = loadLanguages();
        store.addPropertyChangeListener(preferenceListener);
        client.start();
        client.configure(settings.applicationId(), settings.enabled());

        IWorkbench workbench = PlatformUI.getWorkbench();
        display = workbench.getDisplay();
        display.asyncExec(() -> {
            if (stopped || display.isDisposed()) {
                return;
            }
            workbench.addWindowListener(windowListener);
            for (IWorkbenchWindow w : workbench.getWorkbenchWindows()) {
                hookWindow(w);
            }
            lastWindow = workbench.getActiveWorkbenchWindow();
            DebugPlugin.getDefault().getLaunchManager().addLaunchListener(launchListener);
            ResourcesPlugin.getWorkspace().addResourceChangeListener(markerListener, IResourceChangeEvent.POST_CHANGE);
            scheduleRefresh();
        });
    }

    void stop() {
        stopped = true;
        store.removePropertyChangeListener(preferenceListener);
        try {
            DebugPlugin.getDefault().getLaunchManager().removeLaunchListener(launchListener);
            ResourcesPlugin.getWorkspace().removeResourceChangeListener(markerListener);
        } catch (RuntimeException ignored) {
            // those bundles may already be gone during shutdown
        }
        Display d = display;
        if (d != null && !d.isDisposed()) {
            d.syncExec(() -> {
                if (PlatformUI.isWorkbenchRunning()) {
                    PlatformUI.getWorkbench().removeWindowListener(windowListener);
                }
                for (IWorkbenchWindow w : Set.copyOf(hooked)) {
                    unhookWindow(w);
                }
                hookDocument(null);
            });
        }
        builder.shutdownNow();
        client.stop();
    }

    public PresenceClient.Status status() {
        return client.status();
    }

    /** Listener runs on the UI thread. */
    public void addStatusListener(Consumer<PresenceClient.Status> l) {
        statusListeners.add(l);
    }

    public void removeStatusListener(Consumer<PresenceClient.Status> l) {
        statusListeners.remove(l);
    }

    public void reconnect() {
        client.reconnect();
        scheduleRefresh();
    }

    public boolean isEnabled() {
        return settings.enabled();
    }

    public void setEnabled(boolean on) {
        store.setValue(PresencePreferences.ENABLED, on); // the preference listener applies it
    }

    private void applySettings() {
        PresenceSettings p = PresencePreferences.read(store);
        settings = p;
        client.configure(p.applicationId(), p.enabled());
        scheduleRefresh();
    }

    private void goIdle() {
        if (stopped) {
            return;
        }
        // discord-vscode clears the activity outright once the window has been unfocused long enough.
        idle = true;
        client.setActivity(null);
    }

    private void fireStatus(PresenceClient.Status s) {
        logStatus(s);
        Display d = display;
        if (d == null || d.isDisposed()) {
            return;
        }
        d.asyncExec(() -> {
            for (Consumer<PresenceClient.Status> l : statusListeners) {
                l.accept(s);
            }
        });
    }

    /**
     * Connection changes go to the Error Log, so "did it connect?" has an answer without hovering
     * over the status bar. While Discord is closed the client alternates Connecting and
     * Disconnected every retry, so only a change of outcome is logged, not every attempt.
     */
    private void logStatus(PresenceClient.Status s) {
        if (s.state() == PresenceClient.State.CONNECTING) {
            return;
        }
        String line = switch (s.state()) {
            case CONNECTED -> "Connected to Discord" + (s.user() == null ? "" : " as " + s.user())
                    + (s.message() == null ? "" : ": " + s.message());
            case DISCONNECTED -> "Not connected to Discord" + (s.message() == null ? "" : ": " + s.message());
            case NO_APPLICATION_ID -> "Discord Rich Presence needs an Application ID (Preferences > Discord)";
            default -> "Discord Rich Presence is disabled";
        };
        if (line.equals(lastLoggedStatus)) {
            return;
        }
        lastLoggedStatus = line;
        Activator.log(s.state() == PresenceClient.State.DISCONNECTED || s.message() != null ? IStatus.WARNING : IStatus.INFO, line);
    }

    private boolean usesToken(String... tokens) {
        PresenceSettings p = settings;
        String all = p.detailsEditing() + p.detailsDebugging() + p.lowerDetailsEditing() + p.lowerDetailsDebugging();
        for (String t : tokens) {
            if (all.contains(t)) {
                return true;
            }
        }
        return false;
    }

    private void hookWindow(IWorkbenchWindow w) {
        if (hooked.add(w)) {
            w.getPartService().addPartListener(partListener);
            w.getSelectionService().addPostSelectionListener(selectionListener);
        }
    }

    private void unhookWindow(IWorkbenchWindow w) {
        if (hooked.remove(w)) {
            w.getPartService().removePartListener(partListener);
            w.getSelectionService().removePostSelectionListener(selectionListener);
        }
    }

    /** Safe from any thread. */
    private void scheduleRefresh() {
        Display d = display;
        if (stopped || d == null || d.isDisposed()) {
            return;
        }
        if (Display.getCurrent() != d) {
            d.asyncExec(this::scheduleRefresh);
            return;
        }
        if (!refreshPending) {
            refreshPending = true;
            d.timerExec(REFRESH_DELAY_MS, refresh);
        }
    }

    private void refreshNow() {
        refreshPending = false;
        PresenceSettings p = settings;
        if (stopped || idle || !p.enabled()) {
            return;
        }
        Snapshot s;
        try {
            s = capture();
        } catch (RuntimeException e) {
            Activator.logError("Could not read the workbench state for Discord", e);
            return;
        }
        long start = sessionStart;
        Languages langs = languages;
        builder.execute(() -> {
            try {
                if (!idle) {
                    client.setActivity(ActivityBuilder.build(s, p, langs, start));
                }
            } catch (RuntimeException e) {
                Activator.logError("Could not build the Discord activity", e);
            }
        });
    }

    private Snapshot capture() {
        IWorkbench wb = PlatformUI.getWorkbench();
        IWorkbenchWindow w = wb.getActiveWorkbenchWindow();
        if (w == null) {
            w = lastWindow; // focus is in a dialog or another application
        } else {
            lastWindow = w;
        }
        IWorkbenchPage page = w == null ? null : w.getActivePage();
        IEditorPart editor = page == null ? null : page.getActiveEditor();
        if (editor == null) {
            hookDocument(null); // the last editor closed; don't keep listening to its document
        }
        Snapshot.Editor described = editor == null ? null : describe(editor);
        IPath root = ResourcesPlugin.getWorkspace().getRoot().getLocation();
        return new Snapshot(appName(), workspaceName(root), root == null ? null : root.toOSString(), isDebugging(), described);
    }

    private Snapshot.Editor describe(IEditorPart editor) {
        IEditorInput input = editor.getEditorInput();
        String name = input == null ? editor.getTitle() : input.getName();
        String path = null;
        String projectName = null;
        String projectPath = null;
        String relativeDir = "";
        int errors = 0;

        IFile file = input == null ? null : Adapters.adapt(input, IFile.class);
        if (file != null) {
            name = file.getName();
            IPath loc = file.getLocation();
            path = loc == null ? null : loc.toOSString();
            IProject project = file.getProject();
            projectName = project.getName();
            IPath pl = project.getLocation();
            projectPath = pl == null ? null : pl.toOSString();
            relativeDir = file.getParent().getProjectRelativePath().toOSString();
            try {
                for (IMarker m : file.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO)) {
                    if (m.getAttribute(IMarker.SEVERITY, -1) == IMarker.SEVERITY_ERROR) {
                        errors++;
                    }
                }
            } catch (CoreException ignored) {
                // the file was deleted between the event and now
            }
        } else if (input instanceof IURIEditorInput u && u.getURI() != null && "file".equals(u.getURI().getScheme())) {
            try {
                java.nio.file.Path p = Paths.get(u.getURI());
                path = p.toString();
                if (p.getFileName() != null) {
                    name = p.getFileName().toString();
                }
            } catch (RuntimeException ignored) {
                // not a path this file system understands; keep the input's own name
            }
        }

        int total = -1;
        int line = -1;
        int column = -1;
        ITextEditor text = Adapters.adapt(editor, ITextEditor.class);
        IDocument doc = null;
        if (text != null) {
            IDocumentProvider dp = text.getDocumentProvider();
            doc = dp == null ? null : dp.getDocument(text.getEditorInput());
            if (doc != null) {
                total = doc.getNumberOfLines();
                ISelection sel = text.getSelectionProvider() == null ? null : text.getSelectionProvider().getSelection();
                if (sel instanceof ITextSelection ts && ts.getOffset() >= 0) {
                    try {
                        int l = doc.getLineOfOffset(ts.getOffset());
                        line = l + 1;
                        column = ts.getOffset() - doc.getLineOffset(l) + 1;
                    } catch (BadLocationException ignored) {
                        // selection from before the last edit; the next event corrects it
                    }
                }
            }
        }
        hookDocument(doc);
        return new Snapshot.Editor(name == null ? "" : name, path, projectName, projectPath, relativeDir, total, line, column, errors);
    }

    private void hookDocument(IDocument doc) {
        if (doc == hookedDocument) {
            return;
        }
        if (hookedDocument != null) {
            hookedDocument.removeDocumentListener(documentListener);
        }
        hookedDocument = doc;
        if (doc != null) {
            doc.addDocumentListener(documentListener);
        }
    }

    private static boolean isDebugging() {
        for (ILaunch l : DebugPlugin.getDefault().getLaunchManager().getLaunches()) {
            if (ILaunchManager.DEBUG_MODE.equals(l.getLaunchMode()) && !l.isTerminated()) {
                return true;
            }
        }
        return false;
    }

    private static String appName() {
        IProduct product = Platform.getProduct();
        return product != null && product.getName() != null && !product.getName().isBlank() ? product.getName() : "Eclipse";
    }

    /** The name set under General &gt; Workspace, else the workspace folder's name. */
    private static String workspaceName(IPath root) {
        String named = InstanceScope.INSTANCE.getNode("org.eclipse.ui.ide").get("WORKSPACE_NAME", "");
        if (!named.isBlank()) {
            return named;
        }
        return root == null || root.lastSegment() == null ? "Eclipse" : root.lastSegment();
    }

    private static Languages loadLanguages() {
        URL url = Activator.getDefault().getBundle().getEntry("resources/languages.json");
        if (url == null) {
            Activator.logError("resources/languages.json is missing from the bundle", null);
            return Languages.empty();
        }
        try (InputStream in = url.openStream()) {
            return Languages.load(in);
        } catch (IOException e) {
            Activator.logError("Could not read resources/languages.json", e);
            return Languages.empty();
        }
    }
}
