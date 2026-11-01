package com.educore.platform;

import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Permission checks belong before execute, even when the command result is already cached. */
@Component
public final class Commands {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final Json json;
  private final Crypto crypto;

  public Commands(JdbcTemplate db, PlatformTransactionManager tm, Json json, Crypto crypto) {
    this.db = db;
    tx = new TransactionTemplate(tm);
    this.json = json;
    this.crypto = crypto;
  }

  public Object execute(
      String actor, String scope, String route, String key, Object body, Supplier<Object> action) {
    if (key == null)
      throw Api.fail(
          400, "IDEMPOTENCY_REQUIRED", "Send a stable Idempotency-Key for this command.");
    Api.uuid(key);
    String namespace = actor + "|" + scope + "|" + route + "|" + key;
    String digest = Crypto.hash(json.write(body));
    return tx.execute(
        status -> {
          db.queryForObject(
              "select pg_advisory_xact_lock(hashtextextended(?,0))", Object.class, namespace);
          var previous =
              db.queryForList(
                  "select request_hash,response_cipher from command_results where command_key=?",
                  namespace);
          if (!previous.isEmpty()) {
            var row = previous.get(0);
            if (!Crypto.equal(digest, (String) row.get("request_hash")))
              throw Api.fail(
                  409, "IDEMPOTENCY_CONFLICT", "This key was already used for different input.");
            return json.value(crypto.open((String) row.get("response_cipher")));
          }
          Object response = action.get();
          db.update(
              "insert into command_results(command_key,request_hash,response_cipher) values (?,?,?)",
              namespace,
              digest,
              crypto.seal(json.write(response)));
          return response;
        });
  }

  public <T> T transaction(Supplier<T> action) {
    return tx.execute(status -> action.get());
  }

  public void audit(
      UUID schoolId,
      UUID actor,
      String action,
      String type,
      UUID id,
      String reason,
      Object details) {
    db.update(
        "insert into audit_entries(id,school_id,actor_id,action,resource_type,resource_id,reason,details) values (?,?,?,?,?,?,?,?::jsonb)",
        UUID.randomUUID(),
        schoolId,
        actor,
        action,
        type,
        id,
        reason,
        json.write(details));
  }
}
