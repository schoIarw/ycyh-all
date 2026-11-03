package com.ycyh.oversight.v9;

import static com.ycyh.oversight.v9.V9Json.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.function.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Chunked portable JSON storage avoids MySQL TEXT's 64KiB limit. */
@Repository
public class V9Store {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;

  public V9Store(JdbcTemplate db, PlatformTransactionManager tm) {
    this.db = db;
    tx = new TransactionTemplate(tm);
    if (db.queryForObject("SELECT COUNT(*) FROM v9_counter", Integer.class) == 0)
      db.update("INSERT INTO v9_counter(id,last_id) VALUES(1,1000000000000000000)");
  }

  public <T> T transaction(Supplier<T> work) {
    return tx.execute(s -> work.get());
  }

  public long nextId() {
    return transaction(
        () -> {
          long id =
              db.queryForObject("SELECT last_id FROM v9_counter WHERE id=1 FOR UPDATE", Long.class)
                  + 1;
          db.update("UPDATE v9_counter SET last_id=? WHERE id=1", id);
          return id;
        });
  }

  public void reserve(long id) {
    db.update("UPDATE v9_counter SET last_id=? WHERE id=1 AND last_id<?", id, id);
  }

  public ObjectNode get(String kind, long id) {
    List<String> chunks =
        db.queryForList(
            "SELECT payload FROM v9_chunks WHERE kind=? AND id=? ORDER BY part_no",
            String.class,
            kind,
            id);
    if (chunks.isEmpty()) throw new IllegalArgumentException(kind + ".not.found");
    return copy(parse(String.join("", chunks)));
  }

  public ObjectNode lock(String kind, long id) {
    if (db.queryForList("SELECT id FROM v9_objects WHERE kind=? AND id=? FOR UPDATE", kind, id)
        .isEmpty()) throw new IllegalArgumentException(kind + ".not.found");
    return get(kind, id);
  }

  public List<ObjectNode> all(String kind) {
    List<ObjectNode> list = new ArrayList<>();
    for (Long id :
        db.queryForList("SELECT id FROM v9_objects WHERE kind=? ORDER BY id", Long.class, kind))
      list.add(get(kind, id));
    return list;
  }

  public Optional<ObjectNode> find(String kind, String key) {
    List<Long> ids =
        db.queryForList(
            "SELECT id FROM v9_objects WHERE kind=? AND lookup_key=?", Long.class, kind, key);
    return ids.isEmpty() ? Optional.empty() : Optional.of(get(kind, ids.get(0)));
  }

  public void put(String kind, long id, String key, ObjectNode value) {
    transaction(
        () -> {
          if (db.queryForObject(
                  "SELECT COUNT(*) FROM v9_objects WHERE kind=? AND id=?", Integer.class, kind, id)
              == 0)
            db.update("INSERT INTO v9_objects(kind,id,lookup_key) VALUES(?,?,?)", kind, id, key);
          else db.update("UPDATE v9_objects SET lookup_key=? WHERE kind=? AND id=?", key, kind, id);
          db.update("DELETE FROM v9_chunks WHERE kind=? AND id=?", kind, id);
          String payload = value.toString();
          for (int start = 0, part = 0; start < payload.length(); part++) {
            int end = Math.min(payload.length(), start + 5500);
            if (end < payload.length() && Character.isHighSurrogate(payload.charAt(end - 1))) end--;
            db.update(
                "INSERT INTO v9_chunks(kind,id,part_no,payload) VALUES(?,?,?,?)",
                kind,
                id,
                part,
                payload.substring(start, end));
            start = end;
          }
          return null;
        });
  }

  public void put(String kind, ObjectNode value) {
    put(kind, value.path("_storeId").asLong(value.path("id").asLong()), text(value, "_key"), value);
  }

  public ObjectNode edit(String kind, long id, Consumer<ObjectNode> editor) {
    return transaction(
        () -> {
          ObjectNode value = lock(kind, id);
          editor.accept(value);
          put(kind, value);
          return value;
        });
  }
}
