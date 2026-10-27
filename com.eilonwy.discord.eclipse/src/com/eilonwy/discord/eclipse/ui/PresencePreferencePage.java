package com.eilonwy.discord.eclipse.ui;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.eilonwy.discord.eclipse.Activator;
import com.eilonwy.discord.eclipse.PresencePreferences;

/** Window &gt; Preferences &gt; Discord: Rich Presence, and the Discord view. */
public class PresencePreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

    public static final String ID = "com.eilonwy.discord.eclipse.preferences.presence";

    private static final String PORTAL = "https://discord.com/developers/applications";

    public PresencePreferencePage() {
        super(GRID);
        setDescription("Rich Presence shows what you are working on in Eclipse on your Discord profile; "
                + "it needs the Discord desktop app running on this machine. The Discord view settings are "
                + "at the bottom.");
    }

    @Override
    public void init(IWorkbench workbench) {
        setPreferenceStore(Activator.getDefault().getPreferenceStore());
    }

    @Override
    protected void createFieldEditors() {
        Composite p = getFieldEditorParent();

        addField(new BooleanFieldEditor(PresencePreferences.ENABLED, "&Enable Rich Presence", p));
        addField(new StringFieldEditor(PresencePreferences.APPLICATION_ID, "&Application ID:", p));
        Link portal = new Link(p, SWT.WRAP);
        portal.setText("The default shows you as \"Playing Eclipse IDE\". To show a different name or art, "
                + "create your own application at <a>" + PORTAL + "</a> and paste its Application ID here.");
        portal.setLayoutData(span(p));
        portal.addListener(SWT.Selection, e -> DiscordView.openExternal(PORTAL));

        heading(p, "Text");
        addField(new StringFieldEditor(PresencePreferences.DETAILS_EDITING, "Top line, editing:", p));
        addField(new StringFieldEditor(PresencePreferences.DETAILS_DEBUGGING, "Top line, debugging:", p));
        addField(new StringFieldEditor(PresencePreferences.DETAILS_IDLING, "Top line, no editor open:", p));
        addField(new StringFieldEditor(PresencePreferences.LOWER_DETAILS_EDITING, "Bottom line, editing:", p));
        addField(new StringFieldEditor(PresencePreferences.LOWER_DETAILS_DEBUGGING, "Bottom line, debugging:", p));
        addField(new StringFieldEditor(PresencePreferences.LOWER_DETAILS_NO_WORKSPACE_FOUND, "Text for a file outside any project:", p));
        addField(new StringFieldEditor(PresencePreferences.LARGE_IMAGE, "Large image tooltip:", p));
        addField(new StringFieldEditor(PresencePreferences.LARGE_IMAGE_IDLING, "Large image tooltip, no editor:", p));
        addField(new StringFieldEditor(PresencePreferences.SMALL_IMAGE, "Small image tooltip:", p));
        Label tokens = new Label(p, SWT.WRAP);
        tokens.setText("Tokens: {file_name} {dir_name} {full_dir_name} {project} {workspace} {workspace_folder} "
                + "{workspace_and_folder} {lang} {Lang} {LANG} {current_line} {current_column} {total_lines} "
                + "{current_errors} {file_size} {git_branch} {git_repo_name} {app_name} {empty}");
        GridData tokensData = span(p);
        tokensData.widthHint = 420;
        tokens.setLayoutData(tokensData);

        heading(p, "What to show");
        addField(new BooleanFieldEditor(PresencePreferences.REMOVE_DETAILS, "Hide the top line", p));
        addField(new BooleanFieldEditor(PresencePreferences.REMOVE_LOWER_DETAILS, "Hide the bottom line", p));
        addField(new BooleanFieldEditor(PresencePreferences.REMOVE_TIMESTAMP, "Hide the elapsed time", p));
        addField(new BooleanFieldEditor(PresencePreferences.REMOVE_REMOTE_REPOSITORY,
                "Hide the \"View Repository\" button (it links to the Git remote, so it can reveal private repositories)", p));
        addField(new BooleanFieldEditor(PresencePreferences.SWAP_BIG_AND_SMALL_IMAGE, "Swap the large and small images", p));
        IntegerFieldEditor idle = new IntegerFieldEditor(PresencePreferences.IDLE_TIMEOUT,
                "Clear after Eclipse is unfocused for (seconds, 0 = never):", p);
        idle.setValidRange(0, 86_400);
        addField(idle);
        addField(new RegexFieldEditor(PresencePreferences.EXCLUDE_PATTERN,
                "Show nothing for workspace or project paths matching (regex):", p));

        heading(p, "Images");
        addField(new StringFieldEditor(PresencePreferences.ICON_BASE_URL, "Language icon base URL:", p));
        addField(new StringFieldEditor(PresencePreferences.APP_IMAGE, "Small image (asset key or URL):", p));
        Label images = new Label(p, SWT.WRAP);
        images.setText("Language, idle and debugging icons are \"<base URL><name>.png\", using discord-vscode's "
                + "icon set by default. Clear the base URL to send bare names instead, as asset keys uploaded to "
                + "your own application. The small image is sent as typed: upload an asset named \"eclipse\" "
                + "under Rich Presence > Art Assets, or enter an https URL.");
        GridData imagesData = span(p);
        imagesData.widthHint = 420;
        images.setLayoutData(imagesData);

        heading(p, "Discord view");
        subheading(p, "Zoom");
        IntegerFieldEditor zoom = new IntegerFieldEditor(WebViewZoom.PREF, "Zoom level (%):", p, 3);
        zoom.setValidRange(WebViewZoom.MIN, WebViewZoom.MAX);
        zoom.setErrorMessage("Zoom must be a whole number from " + WebViewZoom.MIN + " to " + WebViewZoom.MAX
                + "%, the range the Discord view's browser can display.");
        addField(zoom);
        Label zoomNote = new Label(p, SWT.WRAP);
        zoomNote.setText("From " + WebViewZoom.MIN + "% to " + WebViewZoom.MAX + "%; the default is "
                + WebViewZoom.DEFAULT + "%. Applies at once to open Discord views. Ctrl+scroll and Ctrl+plus/minus "
                + "don't zoom the Discord view, so this setting is the only way to change it.");
        GridData zoomData = span(p);
        zoomData.widthHint = 420;
        zoomNote.setLayoutData(zoomData);
    }

    private static void heading(Composite parent, String text) {
        Label spacer = new Label(parent, SWT.NONE);
        spacer.setLayoutData(span(parent));
        Label l = new Label(parent, SWT.NONE);
        l.setText(text);
        l.setFont(org.eclipse.jface.resource.JFaceResources.getBannerFont());
        l.setLayoutData(span(parent));
    }

    private static void subheading(Composite parent, String text) {
        Label l = new Label(parent, SWT.NONE);
        l.setText(text);
        l.setFont(org.eclipse.jface.resource.JFaceResources.getFontRegistry()
                .getBold(org.eclipse.jface.resource.JFaceResources.DEFAULT_FONT));
        l.setLayoutData(span(parent));
    }

    private static GridData span(Composite parent) {
        GridData d = new GridData(SWT.FILL, SWT.CENTER, true, false);
        d.horizontalSpan = 2;
        return d;
    }

    /** Refuses patterns Java cannot compile, so a typo cannot silently stop excluding anything. */
    private static final class RegexFieldEditor extends StringFieldEditor {
        RegexFieldEditor(String name, String label, Composite parent) {
            super(name, label, parent);
            setEmptyStringAllowed(true);
        }

        @Override
        protected boolean doCheckState() {
            String v = getStringValue();
            if (v == null || v.isBlank()) {
                return true;
            }
            try {
                Pattern.compile(v);
                return true;
            } catch (PatternSyntaxException e) {
                setErrorMessage("Not a valid regular expression: " + e.getDescription());
                return false;
            }
        }
    }
}
