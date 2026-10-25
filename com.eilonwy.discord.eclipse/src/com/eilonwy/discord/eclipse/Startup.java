package com.eilonwy.discord.eclipse;

import org.eclipse.ui.IStartup;

/** Starts Rich Presence with the workbench, the way discord-vscode activates on startup. */
public class Startup implements IStartup {

    @Override
    public void earlyStartup() {
        Activator.getDefault().presence();
    }
}
