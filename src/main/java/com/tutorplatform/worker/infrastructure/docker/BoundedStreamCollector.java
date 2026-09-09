package com.tutorplatform.worker.infrastructure.docker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;

final class BoundedStreamCollector implements Callable<BoundedStreamCollector.CollectedOutput> {
    private final InputStream input;
    private final int maxBytes;

    BoundedStreamCollector(InputStream input, int maxBytes) {
        this.input = input;
        this.maxBytes = maxBytes;
    }

    @Override
    public CollectedOutput call() throws IOException {
        var retained = new ByteArrayOutputStream(Math.min(maxBytes, 8_192));
        var buffer = new byte[8_192];
        var truncated = false;
        int read;
        while ((read = input.read(buffer)) != -1) {
            var remaining = maxBytes - retained.size();
            if (remaining > 0) {
                retained.write(buffer, 0, Math.min(read, remaining));
            }
            if (read > remaining) {
                truncated = true;
            }
        }
        return new CollectedOutput(decodeUtf8Prefix(retained.toByteArray()), truncated);
    }

    private static String decodeUtf8Prefix(byte[] bytes) {
        for (var length = bytes.length; length >= 0; length--) {
            try {
                return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, length))
                    .toString();
            } catch (CharacterCodingException ignored) {
                // A byte limit may split the last UTF-8 code point; retry without that suffix.
            }
        }
        return "";
    }

    record CollectedOutput(String value, boolean truncated) {
    }
}
