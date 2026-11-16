package com.lab5.rmi.server;

import com.lab5.rmi.shared.Settings;
import com.lab5.rmi.shared.Protocol.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;

final class Store {
    private final Settings config;
    Store(Settings config) { this.config = config; }
    Connection open() throws SQLException {
        return DriverManager.getConnection(config.get("db.url"), config.get("db.user"), config.get("db.password"));
    }
    static PreparedStatement query(Connection c, String sql, Object... args) throws SQLException {
        PreparedStatement p = c.prepareStatement(sql);
        try {
            for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
            return p;
        } catch (SQLException e) { p.close(); throw e; }
    }
    static void update(Connection c, String sql, Object... args) throws SQLException {
        try (var p = query(c, sql, args)) { p.executeUpdate(); }
    }
    boolean authenticate(String account, String hash) throws SQLException {
        try (var c = open(); var p = query(c, "SELECT account FROM accounts WHERE account=? AND password_hash=?", account, hash);
             var r = p.executeQuery()) { return r.next(); }
    }
    BigDecimal balance(Connection c, String account) throws SQLException {
        try (var p = query(c, "SELECT balance FROM accounts WHERE account=?", account); var r = p.executeQuery()) {
            if (!r.next()) throw new IllegalArgumentException("Tài khoản không tồn tại");
            return r.getBigDecimal(1);
        }
    }
    BigDecimal balance(String account) throws SQLException {
        try (var c = open()) { return balance(c, account); }
    }
    static Event read(ResultSet r) throws SQLException {
        return new Event(r.getLong("sequence_no"), r.getString("request_id"), r.getString("account"),
            r.getString("kind"), r.getString("target"), r.getBigDecimal("amount"),
            r.getBigDecimal("balance"), r.getBigDecimal("target_balance"), r.getString("event_time"));
    }
    static Event find(Connection c, String id) throws SQLException {
        try (var p = query(c, "SELECT * FROM events WHERE request_id=?", id); var r = p.executeQuery()) {
            return r.next() ? read(r) : null;
        }
    }
    static long lockSequence(Connection c) throws SQLException {
        try (var p = query(c, "SELECT sequence_no FROM ledger_state WHERE id=1 FOR UPDATE"); var r = p.executeQuery()) {
            if (!r.next()) throw new SQLException("Chưa khởi tạo ledger_state");
            return r.getLong(1);
        }
    }
    static void save(Connection c, Event e) throws SQLException {
        update(c, "UPDATE accounts SET balance=? WHERE account=?", e.balance(), e.account());
        if (e.kind().equals("TRANSFER"))
            update(c, "UPDATE accounts SET balance=? WHERE account=?", e.targetBalance(), e.target());
        update(c, "INSERT INTO events VALUES (?,?,?,?,?,?,?,?,?)", e.sequence(), e.id(), e.account(),
            e.kind(), e.target(), e.amount(), e.balance(), e.targetBalance(), e.time());
        update(c, "UPDATE ledger_state SET sequence_no=? WHERE id=1", e.sequence());
    }
    Event transact(String account, Request req) throws SQLException {
        Objects.requireNonNull(req);
        UUID.fromString(req.id());
        BigDecimal amount;
        try { amount = Objects.requireNonNull(req.amount()).setScale(2, java.math.RoundingMode.UNNECESSARY); }
        catch (ArithmeticException | NullPointerException e) {
            throw new IllegalArgumentException("Số tiền phải có tối đa hai chữ số thập phân");
        }
        if (amount.signum() <= 0 || amount.precision() > 19) throw new IllegalArgumentException("Số tiền không hợp lệ");
        if (!Set.of("DEPOSIT", "WITHDRAW", "TRANSFER").contains(req.kind()))
            throw new IllegalArgumentException("Loại giao dịch không hợp lệ");
        String target = req.kind().equals("TRANSFER") ? Objects.requireNonNull(req.target()).trim() : "";
        if (target.equals(account)) throw new IllegalArgumentException("Không chuyển cho chính mình");
        try (var c = open()) {
            c.setAutoCommit(false);
            try {
                long sequence = lockSequence(c);
                Event old = find(c, req.id());
                if (old != null) {
                    if (!old.account().equals(account) || !old.kind().equals(req.kind())
                        || !old.target().equals(target) || old.amount().compareTo(amount) != 0)
                        throw new IllegalArgumentException("Request ID đã dùng cho nội dung khác");
                    c.commit(); return old;
                }
                BigDecimal current = balance(c, account);
                BigDecimal after = req.kind().equals("DEPOSIT") ? current.add(amount) : current.subtract(amount);
                if (after.signum() < 0) throw new IllegalArgumentException("Số dư không đủ");
                BigDecimal targetAfter = req.kind().equals("TRANSFER") ? balance(c, target).add(amount) : null;
                Event event = new Event(sequence + 1, req.id(), account, req.kind(), target, amount,
                    after, targetAfter, Instant.now().toString());
                save(c, event);
                update(c, "INSERT INTO outbox(sequence_no) VALUES (?)", event.sequence());
                c.commit(); return event;
            } catch (Exception e) { c.rollback(); throw e; }
        }
    }
    void apply(Event event) throws SQLException {
        try (var c = open()) {
            c.setAutoCommit(false);
            try {
                long sequence = lockSequence(c);
                Event existing = find(c, event.id());
                if (existing != null) {
                    if (!existing.equals(event)) throw new SQLException("Replication ID conflict");
                } else {
                    if (event.sequence() != sequence + 1) throw new SQLException("Replication sequence gap");
                    balance(c, event.account());
                    if (event.kind().equals("TRANSFER")) balance(c, event.target());
                    save(c, event);
                }
                c.commit();
            } catch (Exception e) { c.rollback(); throw e; }
        }
    }
    List<Event> history(String account) throws SQLException {
        try (var c = open(); var p = query(c,
            "SELECT * FROM events WHERE account=? OR target=? ORDER BY sequence_no DESC LIMIT 200", account, account);
             var r = p.executeQuery()) {
            List<Event> list = new ArrayList<>();
            while (r.next()) list.add(read(r));
            return list;
        }
    }
    Event pending() throws SQLException {
        try (var c = open(); var p = query(c,
            "SELECT e.* FROM events e JOIN outbox o USING(sequence_no) WHERE o.delivered=FALSE ORDER BY sequence_no LIMIT 1");
             var r = p.executeQuery()) { return r.next() ? read(r) : null; }
    }
    void delivery(long sequence, String error) throws SQLException {
        try (var c = open()) {
            update(c, "UPDATE outbox SET delivered=?, attempts=attempts+1, last_error=? WHERE sequence_no=?",
                error == null, error == null ? null : error.substring(0, Math.min(1000, error.length())), sequence);
        }
    }
    String summary() throws SQLException {
        try (var c = open(); var p = query(c,
            "SELECT (SELECT sequence_no FROM ledger_state WHERE id=1), (SELECT COUNT(*) FROM outbox WHERE delivered=FALSE)");
             var r = p.executeQuery()) {
            r.next(); return "MySQL: online | Sequence: " + r.getLong(1) + " | Outbox pending: " + r.getLong(2);
        }
    }
}
