package com.lab5.rmi.server;

import com.lab5.rmi.shared.Settings;
import com.lab5.rmi.ui.Theme;
import javax.swing.*;
import java.awt.*;
import java.rmi.registry.*;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.*;

public final class ServerMain {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            Theme.install();
            JFrame frame = new JFrame("LAB5 • Server Dashboard");
            JTextArea status = new JTextArea("Đang khởi tạo...", 12, 85);
            status.setEditable(false);
            frame.add(new JScrollPane(status), BorderLayout.CENTER);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.pack(); frame.setLocationRelativeTo(null); frame.setVisible(true);
            Theme.async(() -> {
                Settings config = new Settings(args);
                System.setProperty("java.rmi.server.hostname", config.get("rmi.host"));
                System.setProperty("sun.rmi.transport.tcp.responseTimeout", "5000");
                System.setProperty("sun.rmi.transport.connectionTimeout", "5000");
                Store store = new Store(config);
                // Dedicated MySQL advisory lock prevents two servers using the same local database.
                var guard = store.open();
                try (var p = Store.query(guard, "SELECT GET_LOCK('lab5_server',0)"); var r = p.executeQuery()) {
                    r.next();
                    if (r.getInt(1) != 1) { guard.close(); throw new IllegalStateException("Database đã có Server đang chạy"); }
                }
                store.summary();
                BankServer server = new BankServer(config, store);
                Registry registry = LocateRegistry.createRegistry(config.number("rmi.registry.port"));
                registry.rebind("Bank", server); registry.rebind("Replica", server);
                ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
                worker.scheduleWithFixedDelay(() -> {
                    try {
                        if (!guard.isValid(2)) {
                            UnicastRemoteObject.unexportObject(server, true);
                            throw new IllegalStateException("Mất kết nối khóa database; hãy khởi động lại Server");
                        }
                        server.replicate();
                        String text = "Node: " + config.get("node.id") + " | Role: " + config.get("role")
                            + "\nRMI: " + config.get("rmi.host") + ":" + config.get("rmi.registry.port")
                            + " / service " + config.get("rmi.service.port") + "\n" + store.summary()
                            + "\nReplication: " + server.replicationStatus
                            + "\nStandby không tự thăng cấp. Đọc/ghi của Client đều qua Primary.";
                        SwingUtilities.invokeLater(() -> status.setText(text));
                    } catch (Exception e) { SwingUtilities.invokeLater(() -> status.setText("Lỗi: " + e.getMessage())); }
                }, 0, config.number("replication.retry.seconds"), TimeUnit.SECONDS);
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    worker.shutdownNow();
                    try { UnicastRemoteObject.unexportObject(server, true); } catch (Exception ignored) {}
                    try { UnicastRemoteObject.unexportObject(registry, true); } catch (Exception ignored) {}
                    try { guard.close(); } catch (Exception ignored) {}
                }));
                return "Server đã khởi tạo";
            }, status::setText, e -> status.setText("Không khởi tạo được: " + e.getMessage()));
        });
    }
}
