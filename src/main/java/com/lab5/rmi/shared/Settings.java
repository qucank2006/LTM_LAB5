package com.lab5.rmi.shared;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

public final class Settings {
    private final Properties values = new Properties();
    public Settings(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("Cần một tham số: đường dẫn file properties");
        try (Reader reader = Files.newBufferedReader(Path.of(args[0]))) { values.load(reader); }
    }
    public String get(String key) {
        String value = values.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Thiếu cấu hình: " + key);
        return value.trim();
    }
    public int number(String key) { return Integer.parseInt(get(key)); }
}
