package com.lab5.rmi.server;

import com.lab5.rmi.shared.Settings;
import com.lab5.rmi.shared.Protocol.*;
import java.rmi.*;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

final class BankServer extends UnicastRemoteObject implements Bank, Replica {
    private final Settings config;
    final Store store;
    final boolean primary;
    private record Session(String account, long expires) {}
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    volatile String replicationStatus = "Chưa kết nối";
    BankServer(Settings config, Store store) throws RemoteException {
        super(config.number("rmi.service.port"));
        this.config = config; this.store = store;
        String role = config.get("role");
        if (!Set.of("PRIMARY", "STANDBY").contains(role)) throw new IllegalArgumentException("Role không hợp lệ");
        primary = role.equals("PRIMARY");
        if (primary != config.get("node.id").equals(config.get("primary.id")))
            throw new IllegalArgumentException("Role phải khớp primary.id");
    }
    private Bank upstream() throws Exception {
        return (Bank) LocateRegistry.getRegistry(config.get("primary.host"),
            config.number("primary.registry.port")).lookup("Bank");
    }
    private interface Call<T> { T run() throws Exception; }
    private <T> T remote(Call<T> call) throws RemoteException {
        try { return call.run(); }
        catch (Exception e) { throw new RemoteException(e.getMessage(), e); }
    }
    private String account(String token) {
        Session s = sessions.get(token);
        if (s == null || s.expires() < System.currentTimeMillis()) {
            sessions.remove(token); throw new IllegalArgumentException("Phiên hết hạn, hãy đăng nhập lại");
        }
        return s.account();
    }
    public Login login(String account, String password) throws RemoteException {
        return remote(() -> {
            if (!primary) return upstream().login(account, password);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(password.getBytes(StandardCharsets.UTF_8)));
            if (!store.authenticate(account, hash)) throw new IllegalArgumentException("Sai tài khoản hoặc mật khẩu");
            sessions.entrySet().removeIf(e -> e.getValue().expires() < System.currentTimeMillis());
            String token = UUID.randomUUID().toString();
            sessions.put(token, new Session(account, System.currentTimeMillis() + 3600000));
            return new Login(token, account);
        });
    }
    public BigDecimal balance(String token) throws RemoteException {
        return remote(() -> primary ? store.balance(account(token)) : upstream().balance(token));
    }
    public Event transact(String token, Request request) throws RemoteException {
        return remote(() -> primary ? store.transact(account(token), request) : upstream().transact(token, request));
    }
    public List<Event> history(String token) throws RemoteException {
        return remote(() -> primary ? store.history(account(token)) : upstream().history(token));
    }
    public void logout(String token) throws RemoteException {
        remote(() -> { if (primary) sessions.remove(token); else upstream().logout(token); return null; });
    }
    public void apply(String secret, String primaryId, Event event) throws RemoteException {
        remote(() -> {
            if (primary || !config.get("primary.id").equals(primaryId)
                || !MessageDigest.isEqual(config.get("replication.secret").getBytes(StandardCharsets.UTF_8),
                    secret.getBytes(StandardCharsets.UTF_8)))
                throw new SecurityException("Replication không được phép");
            store.apply(event); replicationStatus = "Đã nhận sequence " + event.sequence(); return null;
        });
    }
    void replicate() {
        if (!primary) return;
        Event event = null;
        try {
            event = store.pending();
            if (event == null) { replicationStatus = "Outbox đã gửi hết"; return; }
            Replica peer = (Replica) LocateRegistry.getRegistry(config.get("peer.host"),
                config.number("peer.registry.port")).lookup("Replica");
            peer.apply(config.get("replication.secret"), config.get("node.id"), event);
            store.delivery(event.sequence(), null);
            replicationStatus = "Đã ACK sequence " + event.sequence();
        } catch (Exception e) {
            replicationStatus = "Retry: " + e.getMessage();
            if (event != null) try { store.delivery(event.sequence(), e.toString()); }
            catch (Exception failure) { replicationStatus += " | " + failure.getMessage(); }
        }
    }
}
