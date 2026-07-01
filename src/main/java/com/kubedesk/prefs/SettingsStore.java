package com.kubedesk.prefs;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;

/**
 * Loads and saves {@link Settings} as JSON under {@code ~/.kubedesk/prefs.json}.
 * All failures are swallowed to defaults — preferences are best-effort and must never crash the app.
 */
public class SettingsStore {

    private final File file;
    private final ObjectMapper mapper;

    public SettingsStore() {
        File dir = new File(System.getProperty("user.home"), ".kubedesk");
        this.file = new File(dir, "prefs.json");
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        // Read/write plain fields directly (no getters/setters needed).
        this.mapper.setVisibility(PropertyAccessor.FIELD, Visibility.ANY);
    }

    public File file() {
        return file;
    }

    /** Load settings, or return fresh defaults if the file is missing/unreadable. */
    public Settings load() {
        try {
            if (file.exists()) {
                return mapper.readValue(file, Settings.class);
            }
        } catch (Exception e) {
            // ignore — fall back to defaults
        }
        return new Settings();
    }

    /** Persist settings (best-effort; ignores I/O errors). */
    public void save(Settings settings) {
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            mapper.writeValue(file, settings);
        } catch (Exception e) {
            // ignore — preferences are best-effort
        }
    }
}
