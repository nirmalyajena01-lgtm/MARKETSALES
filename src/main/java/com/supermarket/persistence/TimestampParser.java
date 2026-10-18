package com.supermarket.persistence;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class TimestampParser {
    private static final Logger LOGGER = Logger.getLogger(TimestampParser.class.getName());
    public static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private TimestampParser() {
    }

    public static LocalDateTime parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return LocalDateTime.now();
        }
        try {
            return LocalDateTime.parse(raw.replace(" ", "T"), ISO_FMT);
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(raw.substring(0, Math.min(19, raw.length())).replace(" ", "T"), ISO_FMT);
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "Failed to parse timestamp: " + raw + ", defaulting to now()", ex);
                return LocalDateTime.now();
            }
        }
    }
}

