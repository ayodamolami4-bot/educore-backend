package com.educore.platform;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Network delivery happens after the business transaction commits; consumers must deduplicate IDs.
 */
@Component
public class Outbox {
  private final JdbcTemplate db;
  private final Json json;
  private final Crypto crypto;
  private final ServiceHttp http;
  private final Map<String, String> urls;

  public Outbox(
      JdbcTemplate db,
      Json json,
      Crypto crypto,
      ServiceHttp http,
      @Value("${educore.identity-url:http://localhost:8091}") String identity,
      @Value("${educore.communication-url:http://localhost:8094}") String communication) {
    this.db = db;
    this.json = json;
    this.crypto = crypto;
    this.http = http;
    urls = Map.of("identity", identity, "communication", communication);
  }

  public UUID add(String destination, String kind, Object payload) {
    UUID id = UUID.randomUUID();
    db.update(
        "insert into outbox(id,destination,kind,payload_cipher) values (?,?,?,?)",
        id,
        destination,
        kind,
        crypto.seal(json.write(payload)));
    return id;
  }

  @Scheduled(fixedDelayString = "${educore.outbox-delay-ms:2000}")
  public void dispatch() {
    for (int n = 0; n < 10; n++) {
      var rows =
          db.queryForList(
              """
                update outbox set lease_until=now()+interval '30 seconds',attempts=attempts+1
                where id=(select id from outbox where delivered_at is null and attempts<20
                  and next_attempt_at<=now() and (lease_until is null or lease_until<now())
                  order by created_at,id for update skip locked limit 1)
                returning id,destination,kind,payload_cipher,attempts
                """);
      if (rows.isEmpty()) return;
      var row = rows.get(0);
      UUID id = (UUID) row.get("id");
      try {
        String base = urls.get((String) row.get("destination"));
        if (base == null) throw new IllegalStateException("Unknown event destination.");
        http.post(
            base,
            "/internal/events",
            Api.map(
                "eventId",
                id,
                "kind",
                row.get("kind"),
                "payload",
                json.value(crypto.open((String) row.get("payload_cipher")))),
            Object.class);
        db.update(
            "update outbox set delivered_at=now(),lease_until=null,last_error_code=null where id=?",
            id);
      } catch (RuntimeException ex) {
        int seconds = Math.min(300, 1 << Math.min(8, ((Number) row.get("attempts")).intValue()));
        db.update(
            "update outbox set lease_until=null,next_attempt_at=now()+(?*interval '1 second'),last_error_code='DELIVERY_FAILED' where id=?",
            seconds,
            id);
      }
    }
  }
}
