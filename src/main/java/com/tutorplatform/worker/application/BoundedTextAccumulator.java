package com.tutorplatform.worker.application;

import java.nio.charset.StandardCharsets;

final class BoundedTextAccumulator {
    private final int maxBytes;
    private final StringBuilder value = new StringBuilder();
    private int usedBytes;

    BoundedTextAccumulator(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    void append(String text) {
        if (text == null || text.isEmpty() || usedBytes >= maxBytes) {
            return;
        }
        for (var offset = 0; offset < text.length();) {
            var codePoint = text.codePointAt(offset);
            var character = new String(Character.toChars(codePoint));
            var bytes = character.getBytes(StandardCharsets.UTF_8).length;
            if (usedBytes + bytes > maxBytes) {
                return;
            }
            value.append(character);
            usedBytes += bytes;
            offset += Character.charCount(codePoint);
        }
    }

    String valueOrNull() {
        return value.isEmpty() ? null : value.toString();
    }

    static String truncate(String text, int maxBytes) {
        var accumulator = new BoundedTextAccumulator(maxBytes);
        accumulator.append(text);
        return accumulator.valueOrNull();
    }
}
