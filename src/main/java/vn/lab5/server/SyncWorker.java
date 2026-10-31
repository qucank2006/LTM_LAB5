package vn.lab5.server;

import java.rmi.registry.LocateRegistry;
import java.util.concurrent.*;
import java.util.logging.*;
import vn.lab5.common.*;

public final class SyncWorker implements AutoCloseable {
  private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
  private final Store store;
  private final String id, host;
  private final int port;
  private static final Logger LOG = Logger.getLogger(SyncWorker.class.getName());
  private boolean connected;
  private boolean reportedOffline;

  public SyncWorker(Store store, String id, String host, int port) {
    this.store = store;
    this.id = id;
    this.host = host;
    this.port = port;
  }

  public void start(long interval) {
    executor.scheduleWithFixedDelay(this::tick, 0, interval, TimeUnit.MILLISECONDS);
  }

  public synchronized void tick() {
    try {
      NoteService peer =
          (NoteService) LocateRegistry.getRegistry(host, port).lookup(NoteService.BINDING);
      if (id.equals(peer.ping()))
        throw new IllegalStateException("Peer must have a distinct server ID");
      for (Note n : store.all("outbox")) {
        peer.receive(n);
        store.acknowledge(n);
      }
      // Anti-entropy repairs a lost database, initial join, and delivery races.
      for (Note n : peer.snapshot()) store.merge(n);
      for (Note n : store.all("notes")) peer.receive(n);
      if (!connected) LOG.info("Peer connected; synchronization complete: " + host + ":" + port);
      connected = true;
      reportedOffline = false;
    } catch (Exception e) {
      if (connected || !reportedOffline)
        LOG.log(
            Level.WARNING, "Peer unavailable; durable outbox retained: " + host + ":" + port, e);
      connected = false;
      reportedOffline = true;
    }
  }

  public void close() {
    executor.shutdownNow();
  }
}
