// Copyright (c) 2024 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests.setup;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.logging.Logger;

/**
 * Downloads and installs JCEF native binaries from a GitHub release.
 * Designed for local test execution — downloads binaries built by GitHub Actions
 * from the tlappe/jcefbuild repository.
 *
 * <p>Can be used in two ways:
 * <ul>
 *   <li>Automatically via {@link #ensureInstalled()} from test setup</li>
 *   <li>Standalone via {@link #main(String[])} as an IntelliJ Run Configuration</li>
 * </ul>
 */
public class NativesInstaller {
    private static final Logger LOG = Logger.getLogger(NativesInstaller.class.getName());

    private static final String GITHUB_REPO = "tlappe/jcefbuild";
    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest";
    private static final String INSTALL_LOCK = "install.lock";
    private static final String TEMP_FILE = "download.tar.gz.tmp";

    private static final File DEFAULT_INSTALL_DIR = new File("jcef-natives");

    /**
     * Ensures native binaries are installed. Downloads from the latest GitHub
     * release if not already present. Safe to call multiple times.
     *
     * @return the directory containing the native binaries
     */
    public static File ensureInstalled() throws IOException {
        return ensureInstalled(DEFAULT_INSTALL_DIR);
    }

    /**
     * Ensures native binaries are installed in the given directory.
     *
     * @param installDir the target directory for native binaries
     * @return the directory containing the native binaries
     */
    public static File ensureInstalled(File installDir) throws IOException {
        File lockFile = new File(installDir, INSTALL_LOCK);
        if (lockFile.exists()) {
            LOG.info("Native binaries already installed in: " + installDir.getAbsolutePath());
            return installDir;
        }

        LOG.info("Native binaries not found. Starting download...");

        if (!installDir.exists() && !installDir.mkdirs()) {
            throw new IOException("Could not create install directory: " + installDir);
        }

        String platform = detectPlatform();
        LOG.info("Detected platform: " + platform);

        String downloadUrl = findDownloadUrl(platform);
        LOG.info("Download URL: " + downloadUrl);

        File tempFile = new File(installDir, TEMP_FILE);
        NativesDownloader.download(downloadUrl, tempFile);

        LOG.info("Extracting...");
        TarGzExtractor.extract(tempFile, installDir);

        if (!tempFile.delete()) {
            LOG.warning("Could not delete temp file: " + tempFile);
        }

        if (!lockFile.createNewFile()) {
            throw new IOException("Could not create install lock: " + lockFile);
        }

        LOG.info("Native binaries installed successfully in: " + installDir.getAbsolutePath());
        return installDir;
    }

    /**
     * Detects the current platform in the format used by jcefbuild releases.
     *
     * @return platform identifier like "windows-amd64", "linux-amd64", "macosx-amd64"
     */
    static String detectPlatform() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        String osArch = System.getProperty("os.arch", "").toLowerCase();

        String os;
        if (osName.contains("win")) {
            os = "windows";
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            os = "macosx";
        } else {
            os = "linux";
        }

        String arch;
        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            arch = "amd64";
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            arch = "arm64";
        } else if (osArch.contains("386") || osArch.equals("x86") || osArch.equals("i386") || osArch.equals("i686")) {
            arch = "i386";
        } else {
            arch = osArch;
        }

        return os + "-" + arch;
    }

    /**
     * Queries the GitHub API for the latest release and finds the download URL
     * for the given platform's tar.gz asset.
     */
    private static String findDownloadUrl(String platform) throws IOException {
        String expectedAsset = platform + ".tar.gz";

        URL url = new URL(LATEST_RELEASE_API);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        try {
            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub API returned HTTP " + responseCode
                        + ". Is there a release in " + GITHUB_REPO + "?");
            }

            // Read entire response
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }

            String json = sb.toString();
            return extractDownloadUrl(json, expectedAsset);
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Extracts the browser_download_url for the given asset name from
     * a GitHub releases JSON response. Simple string-based parsing to
     * avoid any JSON library dependency.
     */
    static String extractDownloadUrl(String json, String assetName) throws IOException {
        // Find the asset by name, then extract its browser_download_url.
        // The JSON structure has: "assets": [..., {"name": "X", ..., "browser_download_url": "Y"}, ...]
        int searchFrom = 0;
        while (true) {
            int nameIdx = json.indexOf("\"name\"", searchFrom);
            if (nameIdx < 0) {
                break;
            }

            // Find the value after "name":
            int colonIdx = json.indexOf(':', nameIdx + 6);
            if (colonIdx < 0) break;
            int valueStart = json.indexOf('"', colonIdx + 1);
            if (valueStart < 0) break;
            int valueEnd = json.indexOf('"', valueStart + 1);
            if (valueEnd < 0) break;

            String name = json.substring(valueStart + 1, valueEnd);

            if (name.equals(assetName)) {
                // Found the asset — now find browser_download_url nearby
                int downloadUrlIdx = json.indexOf("\"browser_download_url\"", valueEnd);
                if (downloadUrlIdx < 0) break;

                int dColonIdx = json.indexOf(':', downloadUrlIdx + 22);
                if (dColonIdx < 0) break;
                int dValueStart = json.indexOf('"', dColonIdx + 1);
                if (dValueStart < 0) break;
                int dValueEnd = json.indexOf('"', dValueStart + 1);
                if (dValueEnd < 0) break;

                return json.substring(dValueStart + 1, dValueEnd);
            }

            searchFrom = valueEnd + 1;
        }

        throw new IOException("Asset '" + assetName + "' not found in the latest release of "
                + GITHUB_REPO + ". Available assets may not include your platform.");
    }

    /**
     * Maps a library name to the platform-specific file name.
     * E.g. "libcef" → "libcef.dll" on Windows, "cef" → "libcef.so" on Linux.
     */
    public static String mapLibraryName(String libName) {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("win")) {
            return libName + ".dll";
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            return "lib" + libName + ".dylib";
        } else {
            return "lib" + libName + ".so";
        }
    }

    /**
     * Standalone entry point. Downloads and installs native binaries.
     */
    public static void main(String[] args) throws IOException {
        File installDir = args.length > 0 ? new File(args[0]) : DEFAULT_INSTALL_DIR;
        ensureInstalled(installDir);
    }
}
