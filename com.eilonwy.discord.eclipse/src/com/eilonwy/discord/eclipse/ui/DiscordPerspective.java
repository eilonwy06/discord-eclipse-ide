package com.eilonwy.discord.eclipse.ui;

import org.eclipse.ui.IFolderLayout;
import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.IPerspectiveFactory;

/**
 * Window &gt; Perspective &gt; Open Perspective &gt; Discord: Discord beside the editor, for
 * chatting while you code.
 *
 * <pre>
 * +-----------+------------------+------------------+
 * |  Project  |                  |                  |
 * |  Explorer |   editor area    |     Discord      |
 * |           +------------------+                  |
 * |           | Problems/Console |                  |
 * +-----------+------------------+------------------+
 * </pre>
 */
public class DiscordPerspective implements IPerspectiveFactory {

    public static final String ID = "com.eilonwy.discord.eclipse.perspective";

    /** org.eclipse.ui.console's view, named by id so the plug-in needn't depend on that bundle. */
    private static final String CONSOLE_VIEW = "org.eclipse.ui.console.ConsoleView";

    @Override
    public void createInitialLayout(IPageLayout layout) {
        String editor = layout.getEditorArea();
        layout.setEditorAreaVisible(true);

        // Each ratio is the share the reference part keeps; the new part takes the rest.
        layout.addView(IPageLayout.ID_PROJECT_EXPLORER, IPageLayout.LEFT, 0.18f, editor);
        layout.addView(DiscordView.ID, IPageLayout.RIGHT, 0.5f, editor);

        IFolderLayout bottom = layout.createFolder("com.eilonwy.discord.eclipse.perspective.bottom",
                IPageLayout.BOTTOM, 0.72f, editor);
        bottom.addView(IPageLayout.ID_PROBLEM_VIEW);
        bottom.addPlaceholder(CONSOLE_VIEW);

        layout.addShowViewShortcut(DiscordView.ID);
        layout.addShowViewShortcut(IPageLayout.ID_PROJECT_EXPLORER);
        layout.addShowViewShortcut(IPageLayout.ID_PROBLEM_VIEW);
        layout.addShowViewShortcut(CONSOLE_VIEW);
    }
}
