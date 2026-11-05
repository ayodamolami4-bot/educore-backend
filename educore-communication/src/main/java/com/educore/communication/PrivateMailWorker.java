package com.educore.communication;

import com.educore.platform.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Delivery configuration is explicit. Unconfigured mail remains visibly waiting, never fake-sent.
 */
@Component
public class PrivateMailWorker {
  private final JdbcTemplate db;
  private final Crypto crypto;
  private final Json json;
  private final ObjectProvider<JavaMailSender> sender;
  private final boolean enabled;
  private final String from;
  private final CommunicationService communication;

  public PrivateMailWorker(
      JdbcTemplate db,
      Crypto crypto,
      Json json,
      CommunicationService communication,
      ObjectProvider<JavaMailSender> sender,
      @Value("${educore.email-enabled:false}") boolean enabled,
      @Value("${educore.mail-from:}") String from,
      @Value("${spring.mail.host:}") String host,
      @Value("${spring.mail.properties.mail.smtp.starttls.enable:true}") boolean tls) {
    this.db = db;
    this.crypto = crypto;
    this.json = json;
    this.communication = communication;
    this.sender = sender;
    this.enabled = enabled;
    this.from = from;
    if (enabled) {
      Api.email(from);
      if (host.isBlank())
        throw new IllegalStateException("Configure the SMTP host before enabling mail.");
      if (!tls && !Set.of("localhost", "127.0.0.1").contains(host))
        throw new IllegalStateException("Remote SMTP requires TLS.");
    }
  }

  @Scheduled(fixedDelayString = "${educore.mail-delay-ms:30000}")
  public void send() {
    db.update(
        "update private_mail set state='EXPIRED',lease_token=null,lease_until=null,last_error_code='DELIVERY_WINDOW_EXPIRED' where expires_at<=now() and (state in ('QUEUED','WAITING_CONFIGURATION') or (state='SENDING' and lease_until<now()))");
    JavaMailSender mail = sender.getIfAvailable();
    if (!enabled || mail == null) {
      db.update(
          "update private_mail set state='WAITING_CONFIGURATION',last_error_code='SMTP_NOT_CONFIGURED' where state='QUEUED'");
      return;
    }
    for (int batch = 0; batch < 10; batch++) {
      UUID lease = UUID.randomUUID();
      var claimed =
          db.queryForList(
              "update private_mail set state='SENDING',lease_token=?,lease_until=now()+interval '60 seconds',attempts=attempts+1 where id=(select id from private_mail where (state in ('QUEUED','WAITING_CONFIGURATION') or (state='SENDING' and lease_until<now())) and attempts<5 and next_attempt_at<=now() and expires_at>now() order by created_at for update skip locked limit 1) returning id,payload_cipher,attempts",
              lease);
      if (claimed.isEmpty()) break;
      var row = claimed.get(0);
      UUID id = (UUID) row.get("id");
      try {
        var payload = json.tree(crypto.open((String) row.get("payload_cipher")));
        boolean reset = "RESET".equals(payload.path("template").asText());
        boolean notification = "NOTIFICATION".equals(payload.path("template").asText());
        if (notification) {
          @SuppressWarnings("unchecked")
          Map<String, Object> access =
              communication.http.post(
                  communication.identity,
                  "/internal/delivery-context",
                  Api.map(
                      "accountId",
                      payload.path("accountId").asText(),
                      "schoolId",
                      payload.path("schoolId").asText(),
                      "childId",
                      payload.hasNonNull("childId") ? payload.path("childId").asText() : null,
                      "category",
                      payload.path("category").asText()),
                  Map.class);
          if (!Boolean.TRUE.equals(access.get("allowed"))
              || !Crypto.equal(
                  payload.path("to").asText(), Objects.toString(access.get("email"), ""))) {
            db.update(
                "update private_mail set state='SKIPPED',lease_token=null,lease_until=null,last_error_code='CURRENT_EMAIL_PREFERENCE_OR_ACCESS_CHANGED' where id=? and lease_token=?",
                id,
                lease);
            continue;
          }
        }
        String url =
            "educore://"
                + (reset ? "reset" : "verify-email")
                + "?token="
                + payload.path("token").asText();
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(payload.path("to").asText());
        message.setSubject(
            notification
                ? "You have an EduCore school update"
                : reset ? "Reset your EduCore password" : "Verify your EduCore email");
        message.setText(
            notification
                ? "You have an update from your school. Open EduCore and sign in to view the details. No student information, payment amounts or complaint text are included in this email."
                : "Open this private EduCore link in your app:\n\n"
                    + url
                    + "\n\nIf you did not request this, ignore it. Do not share your password or private recovery code with school staff.");
        mail.send(message);
        db.update(
            "update private_mail set state='SENT',sent_at=now(),lease_token=null,lease_until=null,last_error_code=null where id=? and lease_token=?",
            id,
            lease);
      } catch (RuntimeException ex) {
        db.update(
            "update private_mail set state=case when attempts>=5 then 'FAILED' else 'QUEUED' end,lease_token=null,lease_until=null,next_attempt_at=now()+interval '2 minutes',last_error_code='SMTP_DELIVERY_FAILED' where id=? and lease_token=?",
            id,
            lease);
      }
    }
  }
}
