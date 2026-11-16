package com.lab5.rmi.ui;

import com.formdev.flatlaf.FlatLightLaf;
import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.util.Properties;

public final class Theme {
    private Theme() {}
    public static void install() {
        FlatLightLaf.setup();
        Properties colors = new Properties();
        try (InputStream in = Theme.class.getResourceAsStream("/themes/latte.properties")) {
            if (in == null) throw new IOException("Thiếu theme");
            colors.load(in);
        } catch (IOException e) { throw new IllegalStateException(e); }
        colors.forEach((key, value) -> UIManager.put(key, Color.decode(value.toString())));
        UIManager.put("Component.arc", 12);
        UIManager.put("Button.arc", 12);
    }
    public static <T> void async(java.util.concurrent.Callable<T> work, java.util.function.Consumer<T> done,
                                java.util.function.Consumer<Exception> failed) {
        new SwingWorker<T, Void>() {
            protected T doInBackground() throws Exception { return work.call(); }
            protected void done() {
                try { done.accept(get()); }
                catch (Exception e) { failed.accept(e); }
            }
        }.execute();
    }
}
