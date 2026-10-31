package vn.lab5.server;

import static org.junit.jupiter.api.Assertions.*;

import java.net.ServerSocket;
import java.nio.file.*;
import java.rmi.registry.LocateRegistry;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import vn.lab5.common.*;

/** Real JVM processes, RMI sockets and SQLite files; no service mocks. */
class RmiIntegrationTest {
  @TempDir Path dir;
  final List<Process> processes = new ArrayList<>();
  int ap, bp, as, bs;

  int port() throws Exception {
    try (ServerSocket s = new ServerSocket(0)) {
      return s.getLocalPort();
    }
  }

  Process start(String id, int port, int service, int peer) throws Exception {
    Process p =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                ServerMain.class.getName(),
                "--id",
                id,
                "--host",
                "127.0.0.1",
                "--port",
                "" + port,
                "--service-port",
                "" + service,
                "--peer-host",
                "127.0.0.1",
                "--peer-port",
                "" + peer,
                "--db",
                dir.resolve(id + ".db").toString())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(dir.resolve(id + ".log").toFile()))
            .start();
    processes.add(p);
    return p;
  }

  interface Check {
    boolean test() throws Exception;
  }

  void await(Check check) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
    Exception last = null;
    while (System.nanoTime() < end) {
      try {
        if (check.test()) return;
      } catch (Exception e) {
        last = e;
      }
      Thread.sleep(100);
    }
    throw new AssertionError(
        "Timed out; logs: "
            + Files.readString(dir.resolve("A.log"))
            + "\n"
            + (Files.exists(dir.resolve("B.log")) ? Files.readString(dir.resolve("B.log")) : ""),
        last);
  }

  NoteService service(int port) throws Exception {
    return (NoteService) LocateRegistry.getRegistry("127.0.0.1", port).lookup(NoteService.BINDING);
  }

  void stop(Process p) throws Exception {
    p.destroyForcibly();
    assertTrue(p.waitFor(10, TimeUnit.SECONDS));
  }

  @AfterEach
  void cleanup() throws Exception {
    for (Process p : processes) if (p.isAlive()) stop(p);
  }

  @Test
  void twoProcessesCrudOfflineRestartAndConflict() throws Exception {
    ap = port();
    bp = port();
    as = port();
    bs = port();
    Process a = start("A", ap, as, bp);
    await(() -> service(ap).ping().equals("A"));
    NoteService sa = service(ap);
    Note first = sa.save(null, "offline A", "durable", 0);
    stop(a);
    a = start("A", ap, as, bp);
    await(() -> service(ap).list().size() == 1);
    Process b = start("B", bp, bs, ap);
    await(() -> service(bp).list().size() == 1);
    sa = service(ap);
    NoteService sb = service(bp);
    Note second = sb.save(null, "from B", "two-way", 0);
    await(() -> service(ap).list().size() == 2);
    Note edit = sa.save(first.id(), "edited A", "new", first.version());
    await(() -> service(bp).snapshot().contains(edit));
    sb.delete(second.id(), second.version());
    await(() -> service(ap).list().size() == 1);
    stop(b);
    Note left = sa.save(first.id(), "A partition", "left", edit.version());
    Note offline = sa.save(null, "B offline", "queued", 0);
    stop(a);
    b = start("B", bp, bs, ap);
    await(() -> service(bp).ping().equals("B"));
    Note right = service(bp).save(first.id(), "B partition", "right", edit.version());
    stop(b);
    Note winner = Note.ORDER.compare(left, right) > 0 ? left : right;
    a = start("A", ap, as, bp);
    await(() -> service(ap).list().size() == 2);
    b = start("B", bp, bs, ap);
    await(() -> service(bp).snapshot().contains(offline));
    await(() -> service(ap).snapshot().equals(service(bp).snapshot()));
    assertTrue(service(ap).snapshot().contains(winner));
    // Equal logical version/time with distinct origins exercises deterministic tie break over RMI.
    UUID conflict = UUID.randomUUID();
    Note x = new Note(conflict, "left", "retain", 500, 7, false, "A"),
        y = new Note(conflict, "right", "winner", 500, 7, false, "B");
    service(ap).receive(x);
    service(bp).receive(y);
    await(() -> service(ap).snapshot().contains(y) && service(bp).snapshot().contains(y));
    assertThrows(
        java.rmi.RemoteException.class, () -> service(ap).save(first.id(), "stale", "", 1));
    stop(a);
    stop(b);
    a = start("A", ap, as, bp);
    b = start("B", bp, bs, ap);
    await(
        () ->
            service(ap).snapshot().equals(service(bp).snapshot())
                && service(ap).snapshot().contains(y));
    assertTrue(
        service(ap).snapshot().stream().anyMatch(n -> n.id().equals(second.id()) && n.deleted()));
  }
}
