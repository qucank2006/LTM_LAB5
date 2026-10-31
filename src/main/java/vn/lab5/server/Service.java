package vn.lab5.server;

import java.rmi.RemoteException;
import java.util.*;
import java.util.logging.Logger;
import vn.lab5.common.*;

public final class Service implements NoteService {
  private static final Logger LOG = Logger.getLogger(Service.class.getName());
  private final Store store;
  private final String id;

  public Service(Store store, String id) {
    this.store = store;
    this.id = id;
  }

  private interface Operation<T> {
    T run() throws Exception;
  }

  private <T> T call(Operation<T> op) throws RemoteException {
    try {
      return op.run();
    } catch (Exception e) {
      LOG.log(java.util.logging.Level.WARNING, "Operation failed", e);
      throw new RemoteException(e.getMessage(), e);
    }
  }

  public String ping() {
    return id;
  }

  public List<Note> list() throws RemoteException {
    return call(() -> store.all("notes").stream().filter(n -> !n.deleted()).toList());
  }

  public List<Note> snapshot() throws RemoteException {
    return call(() -> store.all("notes"));
  }

  public Note save(UUID id, String title, String content, long expected) throws RemoteException {
    return call(
        () -> {
          Note n = store.mutate(id, title, content, expected, false);
          LOG.info("SAVE " + n.id() + " v" + n.version());
          return n;
        });
  }

  public Note delete(UUID id, long expected) throws RemoteException {
    return call(
        () -> {
          Objects.requireNonNull(id, "id");
          Note n = store.mutate(id, null, null, expected, true);
          LOG.info("DELETE " + id);
          return n;
        });
  }

  public void receive(Note n) throws RemoteException {
    call(
        () -> {
          store.merge(n);
          LOG.fine("MERGE " + n.id());
          return null;
        });
  }
}
