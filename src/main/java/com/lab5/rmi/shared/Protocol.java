package com.lab5.rmi.shared;

import java.io.Serializable;
import java.math.BigDecimal;
import java.rmi.*;
import java.util.List;

public final class Protocol {
    private Protocol() {}
    public record Login(String token, String account) implements Serializable {}
    public record Request(String id, String kind, String target, BigDecimal amount) implements Serializable {}
    public record Event(long sequence, String id, String account, String kind, String target,
                        BigDecimal amount, BigDecimal balance, BigDecimal targetBalance, String time)
            implements Serializable {}
    public interface Bank extends Remote {
        Login login(String account, String password) throws RemoteException;
        BigDecimal balance(String token) throws RemoteException;
        Event transact(String token, Request request) throws RemoteException;
        List<Event> history(String token) throws RemoteException;
        void logout(String token) throws RemoteException;
    }
    public interface Replica extends Remote {
        void apply(String secret, String primaryId, Event event) throws RemoteException;
    }
}
