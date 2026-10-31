package vn.lab5.server;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import vn.lab5.common.Note;

class StoreTest {
  @TempDir Path dir;

  @Test
  void durableMutationAndTombstone() throws Exception {
    String path = dir.resolve("a.db").toString();
    Note n;
    try (Store s = new Store(path, "A")) {
      n = s.mutate(null, "Title", "Text", 0, false);
      assertEquals(1, s.all("outbox").size());
      assertThrows(IllegalArgumentException.class, () -> s.mutate(n.id(), "bad", "", 0, false));
    }
    try (Store s = new Store(path, "A")) {
      assertEquals(n, s.all("notes").getFirst());
      assertEquals(n, s.all("outbox").getFirst());
      Note d = s.mutate(n.id(), null, null, 1, true);
      assertTrue(d.deleted());
      assertEquals(2, d.version());
      s.acknowledge(n);
      assertEquals(1, s.all("outbox").size());
      s.acknowledge(d);
      assertTrue(s.all("outbox").isEmpty());
    }
    assertThrows(java.sql.SQLException.class, () -> new Store(path, "B"));
  }

  @Test
  void deterministicConflictAndIdempotence() throws Exception {
    try (Store a = new Store(dir.resolve("a.db").toString(), "A");
        Store b = new Store(dir.resolve("b.db").toString(), "B")) {
      UUID id = UUID.randomUUID();
      Note x = new Note(id, "A", "a", 100, 2, false, "A"),
          y = new Note(id, "B", "b", 100, 2, false, "B");
      a.merge(x);
      b.merge(y);
      a.merge(y);
      b.merge(x);
      a.merge(y);
      assertEquals(a.all("notes"), b.all("notes"));
      assertEquals(y, a.all("notes").getFirst());
      assertEquals(1, a.all("conflicts").size());
      assertTrue(a.all("outbox").isEmpty());
      Note deletion = new Note(id, "B", "b", 101, 3, true, "A");
      a.merge(deletion);
      a.merge(y);
      assertTrue(a.all("notes").getFirst().deleted());
    }
  }
}
