// Copyright (c) 2024 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests.setup;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;

/**
 * Extracts .tar.gz archives using only Java standard library classes.
 * Parses the tar format manually (512-byte header blocks, POSIX/UStar).
 */
class TarGzExtractor {
    private static final int BLOCK_SIZE = 512;
    private static final int BUFFER_SIZE = 16 * 1024;

    static void extract(File tarGzFile, File targetDir) throws IOException {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new IOException("Could not create target directory: " + targetDir);
        }

        try (InputStream fileIn = new BufferedInputStream(new FileInputStream(tarGzFile));
             InputStream gzipIn = new GZIPInputStream(fileIn)) {
            extractTar(gzipIn, targetDir);
        }
    }

    private static void extractTar(InputStream tarIn, File targetDir) throws IOException {
        byte[] header = new byte[BLOCK_SIZE];
        byte[] buffer = new byte[BUFFER_SIZE];

        while (true) {
            int bytesRead = readFully(tarIn, header);
            if (bytesRead < BLOCK_SIZE || isEndOfArchive(header)) {
                break;
            }

            String name = parseString(header, 0, 100);
            if (name.isEmpty()) {
                break;
            }

            // Check for UStar prefix (offset 345, 155 bytes)
            String prefix = parseString(header, 345, 155);
            if (!prefix.isEmpty()) {
                name = prefix + "/" + name;
            }

            long size = parseOctal(header, 124, 12);
            byte typeFlag = header[156];

            File outputFile = new File(targetDir, name);

            // Security: prevent path traversal
            if (!outputFile.getCanonicalPath().startsWith(targetDir.getCanonicalPath())) {
                throw new IOException("Tar entry outside target directory: " + name);
            }

            if (typeFlag == '5' || name.endsWith("/")) {
                // Directory
                if (!outputFile.exists() && !outputFile.mkdirs()) {
                    throw new IOException("Could not create directory: " + outputFile);
                }
            } else if (typeFlag == '0' || typeFlag == 0) {
                // Regular file
                File parent = outputFile.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("Could not create parent directory: " + parent);
                }

                try (OutputStream out = new FileOutputStream(outputFile)) {
                    long remaining = size;
                    while (remaining > 0) {
                        int toRead = (int) Math.min(buffer.length, remaining);
                        int read = tarIn.read(buffer, 0, toRead);
                        if (read < 0) {
                            throw new IOException("Unexpected end of tar stream");
                        }
                        out.write(buffer, 0, read);
                        remaining -= read;
                    }
                }

                // Skip padding to next 512-byte boundary
                long remainder = size % BLOCK_SIZE;
                if (remainder != 0) {
                    long skip = BLOCK_SIZE - remainder;
                    skipFully(tarIn, skip);
                }
            } else {
                // Symlink or other type — skip
                long blocks = (size + BLOCK_SIZE - 1) / BLOCK_SIZE;
                skipFully(tarIn, blocks * BLOCK_SIZE);
            }
        }
    }

    private static String parseString(byte[] buf, int offset, int length) {
        int end = offset;
        int max = Math.min(offset + length, buf.length);
        while (end < max && buf[end] != 0) {
            end++;
        }
        return new String(buf, offset, end - offset).trim();
    }

    private static long parseOctal(byte[] buf, int offset, int length) {
        String s = parseString(buf, offset, length);
        if (s.isEmpty()) {
            return 0;
        }
        return Long.parseLong(s, 8);
    }

    private static boolean isEndOfArchive(byte[] block) {
        for (byte b : block) {
            if (b != 0) return false;
        }
        return true;
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int read = in.read(buf, total, buf.length - total);
            if (read < 0) {
                return total;
            }
            total += read;
        }
        return total;
    }

    private static void skipFully(InputStream in, long bytes) throws IOException {
        long remaining = bytes;
        byte[] skipBuf = new byte[BUFFER_SIZE];
        while (remaining > 0) {
            int toRead = (int) Math.min(skipBuf.length, remaining);
            int read = in.read(skipBuf, 0, toRead);
            if (read < 0) {
                throw new IOException("Unexpected end of stream while skipping");
            }
            remaining -= read;
        }
    }
}
