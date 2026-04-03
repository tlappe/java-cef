// Copyright (c) 2024 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests.setup;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Downloads a file via HTTP with resume support for interrupted downloads.
 * Uses HTTP Range headers and RandomAccessFile to continue partial downloads.
 */
class NativesDownloader {
    private static final Logger LOG = Logger.getLogger(NativesDownloader.class.getName());
    private static final int BUFFER_SIZE = 16 * 1024;
    private static final int READ_TIMEOUT = 30000;
    private static final int CONNECT_TIMEOUT = 10000;

    static void download(String downloadUrl, File destination) throws IOException {
        if (!destination.exists() && !destination.createNewFile()) {
            throw new IOException("Could not create target file: " + destination);
        }

        long existingSize = destination.length();

        // Resolve redirects first (GitHub releases redirect to S3)
        URL resolvedUrl = resolveRedirects(new URL(downloadUrl));
        long totalSize = getContentLength(resolvedUrl);

        if (totalSize != -1 && existingSize == totalSize) {
            LOG.info("File already fully downloaded.");
            return;
        }

        if (totalSize != -1 && existingSize > totalSize) {
            // Existing file is larger than expected — re-download
            if (!destination.delete() || !destination.createNewFile()) {
                throw new IOException("Could not recreate target file: " + destination);
            }
            existingSize = 0;
        }

        HttpURLConnection connection = (HttpURLConnection) resolvedUrl.openConnection();
        connection.setReadTimeout(READ_TIMEOUT);
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setInstanceFollowRedirects(true);

        long seekPosition = 0;
        if (existingSize > 0) {
            LOG.info("Resuming download at byte " + existingSize);
            connection.setRequestProperty("Range", "bytes=" + existingSize + "-");

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                String contentRange = connection.getHeaderField("Content-Range");
                LOG.info("Content-Range: " + contentRange);
                ContentRange range = ContentRange.parse(contentRange);
                seekPosition = Math.min(range.getStart(), existingSize);
            }
            // If server doesn't support Range, seekPosition stays 0 and we re-download
        }

        try (InputStream in = connection.getInputStream()) {
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK
                    && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                throw new IOException("Download failed with HTTP " + responseCode);
            }

            try (RandomAccessFile outputFile = new RandomAccessFile(destination, "rw")) {
                outputFile.seek(seekPosition);

                byte[] buffer = new byte[BUFFER_SIZE];
                long transferred = seekPosition;
                int read;
                long lastLog = System.currentTimeMillis();

                while ((read = in.read(buffer)) > 0) {
                    outputFile.write(buffer, 0, read);
                    transferred += read;

                    // Log progress every 2 seconds
                    long now = System.currentTimeMillis();
                    if (now - lastLog > 2000) {
                        lastLog = now;
                        if (totalSize > 0) {
                            LOG.info(String.format("Downloaded %.1f MB / %.1f MB (%.0f%%)",
                                    transferred / 1048576.0, totalSize / 1048576.0,
                                    transferred * 100.0 / totalSize));
                        } else {
                            LOG.info(String.format("Downloaded %.1f MB", transferred / 1048576.0));
                        }
                    }
                }

                LOG.info(String.format("Download complete: %.1f MB", transferred / 1048576.0));
            }
        } finally {
            connection.disconnect();
        }
    }

    private static URL resolveRedirects(URL url) throws IOException {
        int maxRedirects = 5;
        for (int i = 0; i < maxRedirects; i++) {
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setRequestMethod("HEAD");

            int code = conn.getResponseCode();
            conn.disconnect();

            if (code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                if (location != null) {
                    url = new URL(location);
                    continue;
                }
            }
            return url;
        }
        return url;
    }

    private static long getContentLength(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setRequestMethod("HEAD");
            return connection.getContentLengthLong();
        } finally {
            connection.disconnect();
        }
    }
}
