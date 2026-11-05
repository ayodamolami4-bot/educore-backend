package com.educore.communication;

import static com.educore.platform.Api.*;

import com.educore.platform.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NotificationDeliveryWorker {
  private final CommunicationService s;
  private final boolean enabled;
  private final String base, accessToken;
  private final long receiptDelay;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(3))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public NotificationDeliveryWorker(
      CommunicationService s,
      @Value("${educore.push-enabled:false}") boolean enabled,
      @Value("${educore.expo-base-url:https://exp.host/--/api/v2}") String base,
      @Value("${educore.expo-access-token:}") String accessToken,
      @Value("${educore.push-receipt-delay-ms:900000}") long receiptDelay) {
    this.s = s;
    this.enabled = enabled;
    this.base = base;
    this.accessToken = accessToken;
    this.receiptDelay = receiptDelay;
    URI uri = URI.create(base);
    if (!Set.of("exp.host", "localhost", "127.0.0.1").contains(uri.getHost())
        || uri.getUserInfo() != null
        || ("exp.host".equals(uri.getHost())
            && (!"https".equals(uri.getScheme()) || receiptDelay < 60000)))
      throw new IllegalStateException(
          "Use Expo's HTTPS endpoint or an explicit local push test server.");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> delivery(Map<String, Object> n) {
    return s.http.post(
        s.identity,
        "/internal/delivery-context",
        map(
            "accountId",
            n.get("account_id"),
            "schoolId",
            n.get("school_id"),
            "childId",
            n.get("child_id"),
            "category",
            n.get("category")),
        Map.class);
  }

  @Scheduled(fixedDelayString = "${educore.delivery-delay-ms:30000}")
  public void prepare() {
    s.db.update(
        "insert into notification_dispatch(notification_id) select n.id from notifications n where n.created_at>now()-interval '24 hours' and not exists(select 1 from notification_dispatch d where d.notification_id=n.id) on conflict do nothing");
    for (int batch = 0; batch < 10; batch++) {
      UUID lease = UUID.randomUUID();
      var claimed =
          s.db.queryForList(
              "update notification_dispatch set state='PREPARING',lease_token=?,lease_until=now()+interval '60 seconds',attempts=attempts+1 where notification_id=(select notification_id from notification_dispatch where (state='PENDING' or (state='PREPARING' and lease_until<now())) and attempts<10 and next_attempt_at<=now() order by next_attempt_at for update skip locked limit 1) returning notification_id",
              lease);
      if (claimed.isEmpty()) break;
      UUID id = (UUID) claimed.get(0).get("notification_id");
      try {
        var n = s.one("select * from notifications where id=?", id);
        var ctx = delivery(n);
        s.commands.transaction(
            () -> {
              if (Boolean.TRUE.equals(ctx.get("allowed"))) {
                for (Object value : (List<?>) ctx.get("devices")) {
                  Map<?, ?> d = (Map<?, ?>) value;
                  s.db.update(
                      "insert into push_deliveries(id,notification_id,device_id,token_cipher) values (?,?,?,?) on conflict(notification_id,device_id) do nothing",
                      UUID.randomUUID(),
                      id,
                      uuid(d.get("id").toString()),
                      s.crypto.seal(d.get("token").toString()));
                }
                boolean email =
                    ctx.get("email") != null
                        && (Set.of("FEES").contains(n.get("category"))
                            || ("CASES".equals(n.get("category"))
                                && (n.get("title").toString().contains("acknowledged")
                                    || n.get("title").toString().contains("status")))
                            || ("SCHOOL".equals(n.get("category"))
                                && n.get("title")
                                    .toString()
                                    .toLowerCase(Locale.ROOT)
                                    .contains("calendar")));
                if (email)
                  s.db.update(
                      "insert into private_mail(id,event_id,payload_cipher,expires_at) values (?,?,?,now()+interval '48 hours') on conflict(event_id) do nothing",
                      UUID.randomUUID(),
                      id,
                      s.crypto.seal(
                          s.json.write(
                              map(
                                  "template",
                                  "NOTIFICATION",
                                  "to",
                                  ctx.get("email"),
                                  "accountId",
                                  n.get("account_id"),
                                  "schoolId",
                                  n.get("school_id"),
                                  "childId",
                                  n.get("child_id"),
                                  "category",
                                  n.get("category")))));
              }
              s.db.update(
                  "update notification_dispatch set state=?,lease_token=null,lease_until=null where notification_id=? and lease_token=?",
                  Boolean.TRUE.equals(ctx.get("allowed")) ? "PREPARED" : "SKIPPED",
                  id,
                  lease);
              return null;
            });
      } catch (RuntimeException ex) {
        s.db.update(
            "update notification_dispatch set state=case when attempts>=10 then 'FAILED' else 'PENDING' end,lease_token=null,lease_until=null,next_attempt_at=now()+interval '1 minute' where notification_id=? and lease_token=?",
            id,
            lease);
      }
    }
  }

  private JsonNode post(String endpoint, Object body) {
    try {
      var b =
          HttpRequest.newBuilder(URI.create(base + endpoint))
              .timeout(Duration.ofSeconds(8))
              .header("Content-Type", "application/json")
              .header("Accept", "application/json");
      if (!accessToken.isBlank()) b.header("Authorization", "Bearer " + accessToken);
      var response =
          client.send(
              b.POST(HttpRequest.BodyPublishers.ofString(s.json.write(body))).build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300)
        throw fail(503, "PUSH_PROVIDER_UNAVAILABLE", "Push delivery could not be confirmed.");
      return s.json.tree(response.body()).path("data");
    } catch (java.io.IOException ex) {
      throw fail(503, "PUSH_PROVIDER_UNAVAILABLE", "Push delivery is temporarily unavailable.");
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw fail(503, "PUSH_PROVIDER_UNAVAILABLE", "Push delivery was interrupted.");
    }
  }

  private void failDelivery(UUID id, UUID lease, String code, boolean retry) {
    s.db.update(
        "update push_deliveries set state=case when ? and attempts<5 then 'QUEUED' else 'FAILED' end,error_code=?,next_attempt_at=now()+interval '2 minutes',lease_token=null,lease_until=null where id=? and lease_token=?",
        retry,
        code,
        id,
        lease);
  }

  private void disable(Map<String, Object> n, UUID device) {
    s.http.post(
        s.identity,
        "/internal/disable-push-device",
        map("accountId", n.get("account_id"), "deviceId", device),
        Map.class);
  }

  @Scheduled(fixedDelayString = "${educore.push-delay-ms:30000}")
  public void send() {
    s.db.update(
        "update push_deliveries set state='EXPIRED',error_code='DELIVERY_WINDOW_EXPIRED',lease_token=null,lease_until=null where created_at<now()-interval '24 hours' and state in ('QUEUED','WAITING_CONFIGURATION','AWAITING_RECEIPT')");
    if (!enabled) {
      s.db.update(
          "update push_deliveries set state='WAITING_CONFIGURATION',error_code='PUSH_NOT_CONFIGURED' where state='QUEUED'");
      return;
    }
    for (int batch = 0; batch < 10; batch++) {
      UUID lease = UUID.randomUUID();
      var rows =
          s.db.queryForList(
              "update push_deliveries set state='SENDING',lease_token=?,lease_until=now()+interval '30 seconds',attempts=attempts+1 where id=(select id from push_deliveries where (state in ('QUEUED','WAITING_CONFIGURATION') or (state='SENDING' and lease_until<now())) and attempts<5 and next_attempt_at<=now() and created_at>now()-interval '24 hours' order by next_attempt_at for update skip locked limit 1) returning *",
              lease);
      if (rows.isEmpty()) break;
      var p = rows.get(0);
      UUID id = (UUID) p.get("id");
      try {
        var n = s.one("select * from notifications where id=?", p.get("notification_id"));
        var ctx = delivery(n);
        boolean allowed = false;
        for (Object value : (List<?>) ctx.getOrDefault("devices", List.of())) {
          Map<?, ?> d = (Map<?, ?>) value;
          if (p.get("device_id").toString().equals(d.get("id"))
              && Crypto.equal(
                  s.crypto.open(p.get("token_cipher").toString()), d.get("token").toString()))
            allowed = true;
        }
        if (!Boolean.TRUE.equals(ctx.get("allowed")) || !allowed) {
          s.db.update(
              "update push_deliveries set state='DISABLED',error_code='CURRENT_ACCESS_OR_DEVICE_REMOVED',lease_token=null,lease_until=null where id=? and lease_token=?",
              id,
              lease);
          continue;
        }
        var result =
            post(
                "/push/send",
                map(
                    "to",
                    s.crypto.open(p.get("token_cipher").toString()),
                    "title",
                    "EduCore",
                    "body",
                    "You have a school update. Open EduCore to view it.",
                    "sound",
                    "default",
                    "ttl",
                    86400,
                    "data",
                    map("notificationId", n.get("id"), "schoolId", n.get("school_id"))));
        if (result.isArray()) result = result.path(0);
        if ("ok".equals(result.path("status").asText())
            && result.path("id").asText().matches("[A-Za-z0-9_-]{1,160}")) {
          s.db.update(
              "update push_deliveries set state='AWAITING_RECEIPT',ticket_id=?,next_attempt_at=now()+(? * interval '1 millisecond'),lease_token=null,lease_until=null,error_code=null where id=? and lease_token=?",
              result.path("id").asText(),
              receiptDelay,
              id,
              lease);
        } else {
          String code = result.path("details").path("error").asText("PUSH_TICKET_REJECTED");
          if ("DeviceNotRegistered".equals(code)) disable(n, (UUID) p.get("device_id"));
          failDelivery(id, lease, safeCode(code), Set.of("MessageRateExceeded").contains(code));
        }
      } catch (RuntimeException ex) {
        failDelivery(id, lease, "PUSH_DELIVERY_UNCONFIRMED", true);
      }
    }
    receipts();
  }

  private String safeCode(String code) {
    return code.matches("[A-Za-z0-9_]{1,80}") ? code : "PROVIDER_REJECTED";
  }

  private void receipts() {
    for (int batch = 0; batch < 10; batch++) {
      UUID lease = UUID.randomUUID();
      var rows =
          s.db.queryForList(
              "update push_deliveries set lease_token=?,lease_until=now()+interval '30 seconds' where id=(select id from push_deliveries where state='AWAITING_RECEIPT' and next_attempt_at<=now() and (lease_until is null or lease_until<now()) order by next_attempt_at for update skip locked limit 1) returning *",
              lease);
      if (rows.isEmpty()) break;
      var p = rows.get(0);
      UUID id = (UUID) p.get("id");
      try {
        var response = post("/push/getReceipts", map("ids", List.of(p.get("ticket_id"))));
        var receipt = response.path(p.get("ticket_id").toString());
        if (receipt.isMissingNode()) {
          s.db.update(
              "update push_deliveries set next_attempt_at=now()+interval '15 minutes',lease_token=null,lease_until=null where id=? and lease_token=?",
              id,
              lease);
          continue;
        }
        if ("ok".equals(receipt.path("status").asText()))
          s.db.update(
              "update push_deliveries set state='SENT',sent_at=now(),error_code=null,lease_token=null,lease_until=null where id=? and lease_token=?",
              id,
              lease);
        else {
          String code = receipt.path("details").path("error").asText("PUSH_RECEIPT_REJECTED");
          if ("DeviceNotRegistered".equals(code))
            disable(
                s.one("select * from notifications where id=?", p.get("notification_id")),
                (UUID) p.get("device_id"));
          failDelivery(id, lease, safeCode(code), false);
        }
      } catch (RuntimeException ex) {
        s.db.update(
            "update push_deliveries set error_code='RECEIPT_UNAVAILABLE',next_attempt_at=now()+interval '15 minutes',lease_token=null,lease_until=null where id=? and lease_token=?",
            id,
            lease);
      }
    }
  }
}
