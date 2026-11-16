package com.lab5.rmi.client;

import com.lab5.rmi.shared.Settings;
import com.lab5.rmi.shared.Protocol.Bank;
import com.lab5.rmi.shared.Protocol.Event;
import com.lab5.rmi.shared.Protocol.Login;
import com.lab5.rmi.shared.Protocol.Request;
import com.lab5.rmi.ui.Theme;
import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.math.BigDecimal;
import java.rmi.registry.LocateRegistry;
import java.util.UUID;

public final class ClientMain {
    private final JFrame frame = new JFrame("LAB5 • Client Dashboard");
    private final JTextField account = new JTextField("1001", 12);
    private final JPasswordField password = new JPasswordField(12);
    private final JTextField target = new JTextField("1002", 12);
    private final JTextField amount = new JTextField("10000.00", 12);
    private final JTextArea output = new JTextArea(18, 95);
    private final JPanel actions = new JPanel(new FlowLayout());
    private final JComboBox<String> kind = new JComboBox<>(new String[]{"DEPOSIT", "WITHDRAW", "TRANSFER"});
    private Settings config;
    private Bank bank;
    private Login session;
    // Keep the request ID after an ambiguous network failure; retry with exactly the same payload.
    private Request pending;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> { Theme.install(); new ClientMain().show(args); });
    }
    private interface Job { String run() throws Exception; }
    private void work(Job job) {
        setEnabled(false);
        Theme.async(job::run, text -> { output.setText(text); setEnabled(true); },
            e -> { output.setText("Lỗi: " + e.getMessage()
                + (pending == null ? "" : "\nGiao dịch chưa xác định kết quả. Dùng Thử lại cùng ID: " + pending.id()));
                setEnabled(true); });
    }
    private void setEnabled(boolean value) {
        for (Component c : actions.getComponents()) c.setEnabled(value);
        account.setEnabled(value); password.setEnabled(value);
        target.setEnabled(value); amount.setEnabled(value); kind.setEnabled(value);
    }
    private Bank connect() throws Exception {
        return (Bank) LocateRegistry.getRegistry(config.get("server.host"),
            config.number("server.registry.port")).lookup("Bank");
    }
    private Login requireSession() {
        if (session == null) throw new IllegalStateException("Hãy đăng nhập");
        return session;
    }
    private void button(String label, Runnable action) {
        JButton b = new JButton(label); b.addActionListener(e -> action.run()); actions.add(b);
    }
    private void show(String[] args) {
        JPanel fields = new JPanel(new GridLayout(0, 4, 10, 10));
        fields.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        fields.add(new JLabel("Tài khoản")); fields.add(account);
        fields.add(new JLabel("Mật khẩu")); fields.add(password);
        fields.add(new JLabel("Số tiền")); fields.add(amount);
        fields.add(new JLabel("Tài khoản nhận")); fields.add(target);
        fields.add(new JLabel("Loại giao dịch")); fields.add(kind);
        button("Đăng nhập", () -> {
            if (pending != null && (session == null || !account.getText().trim().equals(session.account()))) { output.setText("Giao dịch đang chờ: chỉ đăng nhập lại cùng tài khoản."); return; }
            String a = account.getText().trim();
            String pass = new String(password.getPassword()); password.setText("");
            work(() -> {
                bank = connect(); session = bank.login(a, pass);
                return "Đăng nhập: " + session.account() + "\nSố dư: " + bank.balance(session.token());
            });
        });
        button("Số dư", () -> work(() -> "Số dư: " + bank.balance(requireSession().token())));
        button("Thực hiện", () -> {
            if (pending != null) { output.setText("Còn giao dịch chưa xác định kết quả; dùng Thử lại cùng ID."); return; }
            try {
                requireSession();
                pending = new Request(UUID.randomUUID().toString(), (String) kind.getSelectedItem(),
                    target.getText().trim(), new BigDecimal(amount.getText().trim()));
            } catch (Exception e) { output.setText(e.getMessage()); return; }
            submit();
        });
        button("Thử lại cùng ID", this::submit);
        button("Lịch sử", () -> work(() -> {
            StringBuilder text = new StringBuilder("200 giao dịch gần nhất\n");
            for (Event e : bank.history(requireSession().token()))
                text.append(e.sequence()).append(" | ").append(e.time()).append(" | ")
                    .append(e.kind()).append(" | ").append(e.account()).append(" -> ").append(e.target())
                    .append(" | ").append(e.amount()).append(" | ID ").append(e.id()).append("\n");
            return text.toString();
        }));
        button("Đăng xuất", () -> {
            if (pending != null) { output.setText("Hãy giải quyết giao dịch đang chờ trước khi đăng xuất."); return; }
            work(() -> { bank.logout(requireSession().token()); session = null; return "Đã đăng xuất"; });
        });
        output.setEditable(false);
        JPanel top = new JPanel(new BorderLayout()); top.add(fields); top.add(actions, BorderLayout.SOUTH);
        frame.add(top, BorderLayout.NORTH); frame.add(new JScrollPane(output), BorderLayout.CENTER);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE); frame.pack();
        frame.setLocationRelativeTo(null); frame.setVisible(true);
        work(() -> {
            config = new Settings(args);
            System.setProperty("sun.rmi.transport.tcp.responseTimeout", "5000");
            System.setProperty("sun.rmi.transport.connectionTimeout", "5000");
            return "Sẵn sàng • " + config.get("client.name") + " • Server " + config.get("server.host");
        });
    }
    private void submit() {
        if (pending == null) { output.setText("Không có giao dịch cần thử lại."); return; }
        Request request = pending;
        work(() -> {
            bank = connect();
            Event event;
            try { event = bank.transact(requireSession().token(), request); }
            catch (java.rmi.RemoteException failure) {
                Throwable cause = failure;
                while (cause.getCause() != null) cause = cause.getCause();
                // Business validation failures occur before commit. Expired session retains request for re-login.
                if (cause instanceof IllegalArgumentException
                    && !String.valueOf(cause.getMessage()).contains("Phiên")) pending = null;
                throw failure;
            }
            pending = null;
            return "Thành công • " + event.id() + "\nSố dư sau giao dịch: " + event.balance();
        });
    }
}
