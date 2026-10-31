package vn.lab5.server;

import java.sql.*;
import java.util.*;
import vn.lab5.common.Note;

/** All access serialized; notes and outbox commit atomically. Tombstones are retained. */
public final class Store implements AutoCloseable {
  private final Connection db;
  private final String serverId;

  public Store(String path, String serverId) throws SQLException {
    this.serverId = serverId;
    db = DriverManager.getConnection("jdbc:sqlite:" + path);
    try (Statement s = db.createStatement()) {
      s.execute("PRAGMA journal_mode=WAL");
      s.execute("PRAGMA synchronous=FULL");
      s.execute("PRAGMA busy_timeout=5000");
      s.execute(
          "CREATE TABLE IF NOT EXISTS notes(id TEXT PRIMARY KEY,title TEXT,content TEXT,updatedAt"
              + " INTEGER,version INTEGER,deleted INTEGER,origin TEXT)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS outbox(id TEXT PRIMARY KEY,title TEXT,content TEXT,updatedAt"
              + " INTEGER,version INTEGER,deleted INTEGER,origin TEXT)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS conflicts(id TEXT,title TEXT,content TEXT,updatedAt"
              + " INTEGER,version INTEGER,deleted INTEGER,origin"
              + " TEXT,UNIQUE(id,version,updatedAt,origin))");
      s.execute("CREATE TABLE IF NOT EXISTS metadata(key TEXT PRIMARY KEY,value TEXT)");
    }
    try (PreparedStatement p =
        db.prepareStatement("INSERT OR IGNORE INTO metadata VALUES('serverId',?)")) {
      p.setString(1, serverId);
      p.executeUpdate();
    }
    try (Statement s = db.createStatement();
        ResultSet r = s.executeQuery("SELECT value FROM metadata WHERE key='serverId'")) {
      if (!r.next() || !serverId.equals(r.getString(1))) {
        db.close();
        throw new SQLException("Database belongs to a different server ID");
      }
    }
  }

  private Note read(ResultSet r) throws SQLException {
    return new Note(
        UUID.fromString(r.getString("id")),
        r.getString("title"),
        r.getString("content"),
        r.getLong("updatedAt"),
        r.getLong("version"),
        r.getBoolean("deleted"),
        r.getString("origin"));
  }

  public synchronized List<Note> all(String table) throws SQLException {
    if (!Set.of("notes", "outbox", "conflicts").contains(table))
      throw new IllegalArgumentException("table");
    List<Note> result = new ArrayList<>();
    try (Statement s = db.createStatement();
        ResultSet r = s.executeQuery("SELECT * FROM " + table + " ORDER BY id")) {
      while (r.next()) result.add(read(r));
    }
    return result;
  }

  private Note find(UUID id) throws SQLException {
    try (PreparedStatement p = db.prepareStatement("SELECT * FROM notes WHERE id=?")) {
      p.setString(1, id.toString());
      try (ResultSet r = p.executeQuery()) {
        return r.next() ? read(r) : null;
      }
    }
  }

  private void put(String table, Note n) throws SQLException {
    String sql =
        table.equals("conflicts")
            ? "INSERT OR IGNORE INTO conflicts VALUES(?,?,?,?,?,?,?)"
            : "INSERT OR REPLACE INTO " + table + " VALUES(?,?,?,?,?,?,?)";
    try (PreparedStatement p = db.prepareStatement(sql)) {
      p.setString(1, n.id().toString());
      p.setString(2, n.title());
      p.setString(3, n.content());
      p.setLong(4, n.updatedAt());
      p.setLong(5, n.version());
      p.setBoolean(6, n.deleted());
      p.setString(7, n.origin());
      p.executeUpdate();
    }
  }

  public synchronized Note mutate(
      UUID id, String title, String content, long expected, boolean deleted) throws SQLException {
    db.setAutoCommit(false);
    try {
      Note old = id == null ? null : find(id);
      if ((id != null && (old == null || old.deleted()))
          || (old == null ? expected != 0 : expected != old.version()))
        throw new IllegalArgumentException(
            "Ghi chú đã thay đổi hoặc bị xóa. Hãy làm mới danh sách.");
      if (!deleted && (title == null || title.isBlank()))
        throw new IllegalArgumentException("Tiêu đề không được trống.");
      Note n =
          new Note(
              id == null ? UUID.randomUUID() : id,
              deleted ? old.title() : title.trim(),
              deleted ? old.content() : content,
              Math.max(System.currentTimeMillis(), old == null ? 0 : old.updatedAt() + 1),
              old == null ? 1 : Math.addExact(old.version(), 1),
              deleted,
              serverId);
      put("notes", n);
      put("outbox", n);
      db.commit();
      return n;
    } catch (SQLException | RuntimeException e) {
      db.rollback();
      throw e;
    } finally {
      db.setAutoCommit(true);
    }
  }

  public synchronized void merge(Note incoming) throws SQLException {
    Objects.requireNonNull(incoming);
    db.setAutoCommit(false);
    try {
      Note current = find(incoming.id());
      if (current == null || Note.ORDER.compare(incoming, current) > 0) {
        if (current != null && !current.equals(incoming)) put("conflicts", current);
        put("notes", incoming);
      } else if (!current.equals(incoming)) put("conflicts", incoming);
      db.commit();
    } catch (SQLException | RuntimeException e) {
      db.rollback();
      throw e;
    } finally {
      db.setAutoCommit(true);
    }
  }

  public synchronized void acknowledge(Note n) throws SQLException {
    try (PreparedStatement p =
        db.prepareStatement(
            "DELETE FROM outbox WHERE id=? AND version=? AND updatedAt=? AND origin=?")) {
      p.setString(1, n.id().toString());
      p.setLong(2, n.version());
      p.setLong(3, n.updatedAt());
      p.setString(4, n.origin());
      p.executeUpdate();
    }
  }

  @Override
  public synchronized void close() throws SQLException {
    db.close();
  }
}
