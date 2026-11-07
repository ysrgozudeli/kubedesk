package com.kubedesk.prefs;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional on-disk store for the Overview's live cluster-metric samples, so the chart can refill after
 * a restart when the user enables "keep history across restarts". Keyed by kube context name.
 *
 * <p>Best-effort like {@link SettingsStore}: every failure falls back to empty and never crashes the
 * app. Each sample is {@code [epochSeconds, cpuMillicores, memMiB]}; the store caps how many it keeps
 * per context so the file can't grow without bound.
 */
public class MetricsHistoryStore {

    /** Upper bound on samples kept per context on disk (≈ one 30s-interval day). */
    private static final int MAX_PER_CONTEXT = 3000;

    private final File file;
    private final ObjectMapper mapper;

    public MetricsHistoryStore() {
        File dir = new File(System.getProperty("user.home"), ".kubedesk");
        this.file = new File(dir, "metrics-history.json");
        this.mapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.mapper.setVisibility(PropertyAccessor.FIELD, Visibility.ANY);
    }

    /** Samples for a context (each {@code [epochSeconds, cpuMillicores, memMiB]}); empty if none/unreadable. */
    public List<double[]> load(String context) {
        try {
            if (!file.exists()) {
                return new ArrayList<>();
            }
            Map<String, List<double[]>> all = mapper.readValue(file,
                    new TypeReference<Map<String, List<double[]>>>() {
                    });
            List<double[]> samples = all.get(context);
            return samples != null ? new ArrayList<>(samples) : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** Replace the stored samples for a context (capped to the most recent {@value #MAX_PER_CONTEXT}). */
    public void save(String context, List<double[]> samples) {
        if (context == null) {
            return;
        }
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            Map<String, List<double[]>> all;
            if (file.exists()) {
                all = mapper.readValue(file, new TypeReference<Map<String, List<double[]>>>() {
                });
            } else {
                all = new HashMap<>();
            }
            List<double[]> capped = samples.size() > MAX_PER_CONTEXT
                    ? new ArrayList<>(samples.subList(samples.size() - MAX_PER_CONTEXT, samples.size()))
                    : new ArrayList<>(samples);
            all.put(context, capped);
            mapper.writeValue(file, all);
        } catch (Exception e) {
            // ignore — best-effort
        }
    }
}
