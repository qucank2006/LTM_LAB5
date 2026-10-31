package vn.lab5.common;

import java.io.Serializable;
import java.util.Comparator;
import java.util.UUID;

public record Note(
    UUID id,
    String title,
    String content,
    long updatedAt,
    long version,
    boolean deleted,
    String origin)
    implements Serializable {
  public static final Comparator<Note> ORDER =
      Comparator.comparingLong(Note::version)
          .thenComparingLong(Note::updatedAt)
          .thenComparing(Note::origin);

  public Note {
    if (id == null
        || title == null
        || content == null
        || origin == null
        || origin.isBlank()
        || version < 1
        || updatedAt < 0
        || title.length() > 200
        || content.length() > 100_000) throw new IllegalArgumentException("Invalid note fields");
  }
}
