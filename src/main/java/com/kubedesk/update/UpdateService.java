package com.kubedesk.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.function.DoubleConsumer;

/**
 * Checks GitHub Releases for a newer KubeDesk and downloads the installer for the current OS.
 * Pure logic, no UI — the UI decides when to check and how to present results.
 */
public class UpdateService {

    private static final String LATEST_RELEASE_URL =
            "https://api.github.com/repos/ysrgozudeli/kubedesk/releases/latest";

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /** A downloadable file attached to a release. */
    public record Asset(String name, String downloadUrl, long size) {
    }

    /** The latest release: its version (tag without the leading 'v'), page URL, and assets. */
    public record ReleaseInfo(String version, String htmlUrl, List<Asset> assets) {
    }

    /** The version of the running app, read from the Maven-filtered version.properties. */
    public static String currentVersion() {
        try (InputStream in = UpdateService.class.getResourceAsStream("/version.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty("app.version");
                if (v != null && !v.isBlank() && !v.contains("${")) {
                    return v.trim();
                }
            }
        } catch (Exception ignore) {
            // fall through
        }
        return "0.0.0";
    }

    /** Query GitHub for the latest release. Returns empty on any failure (offline, rate-limited, …). */
    public Optional<ReleaseInfo> latestRelease() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(LATEST_RELEASE_URL))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "KubeDesk-Updater")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return Optional.empty();
            }
            JsonNode root = mapper.readTree(resp.body());
            String tag = root.path("tag_name").asText(null);
            if (tag == null || tag.isBlank()) {
                return Optional.empty();
            }
            String version = tag.startsWith("v") ? tag.substring(1) : tag;
            String htmlUrl = root.path("html_url").asText("");
            List<Asset> assets = new ArrayList<>();
            for (JsonNode a : root.path("assets")) {
                String name = a.path("name").asText(null);
                String url = a.path("browser_download_url").asText(null);
                if (name != null && url != null) {
                    assets.add(new Asset(name, url, a.path("size").asLong(0)));
                }
            }
            return Optional.of(new ReleaseInfo(version, htmlUrl, assets));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** True if {@code latest} is a strictly higher semantic version than {@code current}. */
    public static boolean isNewer(String latest, String current) {
        int[] l = parse(latest);
        int[] c = parse(current);
        for (int i = 0; i < 3; i++) {
            if (l[i] != c[i]) {
                return l[i] > c[i];
            }
        }
        return false;
    }

    private static int[] parse(String v) {
        int[] out = {0, 0, 0};
        if (v == null) {
            return out;
        }
        // Drop any pre-release/build suffix (e.g. 1.2.3-rc1) before splitting.
        String core = v.split("[-+]", 2)[0];
        String[] parts = core.split("\\.");
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException ignore) {
                out[i] = 0;
            }
        }
        return out;
    }

    /** Pick the installer asset matching the current OS, or empty if none fits (e.g. portable-only). */
    public Optional<Asset> installerForCurrentOs(ReleaseInfo release) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> preferred;
        if (os.contains("win")) {
            preferred = List.of(".msi");
        } else if (os.contains("mac") || os.contains("darwin")) {
            preferred = List.of(".dmg");
        } else if (os.contains("nux") || os.contains("nix")) {
            preferred = List.of(".deb", ".rpm");
        } else {
            return Optional.empty();
        }
        for (String ext : preferred) {
            for (Asset a : release.assets()) {
                if (a.name().toLowerCase(Locale.ROOT).endsWith(ext)) {
                    return Optional.of(a);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Download an asset to a temp file, reporting progress in [0,1] (or a negative value when the
     * total size is unknown). Returns the downloaded file's path.
     */
    public Path download(Asset asset, DoubleConsumer onProgress) throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("kubedesk-update");
        Path target = dir.resolve(asset.name());
        HttpRequest req = HttpRequest.newBuilder(URI.create(asset.downloadUrl()))
                .header("User-Agent", "KubeDesk-Updater")
                .GET()
                .build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("Download failed with HTTP " + resp.statusCode());
        }
        long total = resp.headers().firstValueAsLong("content-length").orElse(asset.size());
        try (InputStream in = resp.body();
             OutputStream out = Files.newOutputStream(target)) {
            byte[] buf = new byte[1 << 16];
            long read = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                read += n;
                if (onProgress != null) {
                    onProgress.accept(total > 0 ? (double) read / total : -1);
                }
            }
        }
        return target;
    }
}
