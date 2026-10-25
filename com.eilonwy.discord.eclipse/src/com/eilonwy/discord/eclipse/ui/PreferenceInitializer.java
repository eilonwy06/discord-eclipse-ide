package com.eilonwy.discord.eclipse.ui;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;

import com.eilonwy.discord.eclipse.Activator;
import com.eilonwy.discord.eclipse.PresencePreferences;

public class PreferenceInitializer extends AbstractPreferenceInitializer {

    @Override
    public void initializeDefaultPreferences() {
        PresencePreferences.setDefaults(Activator.getDefault().getPreferenceStore());
    }
}
