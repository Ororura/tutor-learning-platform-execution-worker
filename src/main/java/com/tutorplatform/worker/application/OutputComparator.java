package com.tutorplatform.worker.application;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class OutputComparator {
    private static final Pattern TRAILING_LINE_WHITESPACE = Pattern.compile("[\\t\\x0B\\f ]+(?=\\n|$)");

    public boolean matches(String expected, String actual, ComparisonMode mode) {
        return switch (mode) {
            case EXACT -> expected.equals(actual);
            case NORMALIZED -> normalize(expected).equals(normalize(actual));
        };
    }

    String normalize(String value) {
        var normalizedLineEndings = value.replace("\r\n", "\n").replace('\r', '\n');
        var withoutLineTrailingWhitespace = TRAILING_LINE_WHITESPACE.matcher(normalizedLineEndings).replaceAll("");
        return withoutLineTrailingWhitespace.replaceFirst("\\n+$", "");
    }
}
