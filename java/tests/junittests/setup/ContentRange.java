// Copyright (c) 2024 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package tests.junittests.setup;

/**
 * Represents an HTTP Content-Range header value.
 */
class ContentRange {
    private final long start;
    private final long end;
    private final long total;

    ContentRange(long start, long end, long total) {
        this.start = start;
        this.end = end;
        this.total = total;
    }

    /**
     * Parses a Content-Range header value.
     *
     * @param contentRange standard format "bytes 5000-9999/10000"
     * @return the parsed ContentRange
     */
    static ContentRange parse(String contentRange) {
        if (contentRange == null || !contentRange.startsWith("bytes ")) {
            throw new IllegalArgumentException("Invalid Content-Range header: " + contentRange);
        }

        String[] parts = contentRange.substring(6).trim().split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid Content-Range format: " + contentRange);
        }

        String[] range = parts[0].split("-");
        if (range.length != 2) {
            throw new IllegalArgumentException("Invalid range in Content-Range: " + contentRange);
        }

        try {
            long start = Long.parseLong(range[0]);
            long end = Long.parseLong(range[1]);
            long total = Long.parseLong(parts[1]);
            return new ContentRange(start, end, total);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Failed to parse Content-Range values: " + contentRange, e);
        }
    }

    long getStart() {
        return start;
    }

    long getEnd() {
        return end;
    }

    long getTotal() {
        return total;
    }
}
