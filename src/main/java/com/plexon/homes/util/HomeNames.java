package com.plexon.homes.util;

import java.util.Locale;
import java.util.Optional;

public final class HomeNames {
    private HomeNames() {}

    public static Optional<String> normalize(String input, int maxLength) {
        if (input == null) return Optional.empty();
        String value = input.trim();
        if (value.isEmpty() || value.length() > maxLength) return Optional.empty();
        if (!value.matches("[A-Za-z0-9_-]+")) return Optional.empty();
        return Optional.of(value.toLowerCase(Locale.ROOT));
    }
}
