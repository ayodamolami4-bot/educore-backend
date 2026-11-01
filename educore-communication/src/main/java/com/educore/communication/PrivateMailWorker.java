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

  public PrivateMailWorker(
      JdbcTemplate db,
      Crypto crypto,
      Json json,
      ObjectProvider<JavaMailSender> sender,
      @Value("${educore.email-enabled:false}") boolean enabled,
      @Value("${educore.mail-from:}") String from,
      @Value("${spring.mail.host:}") String host,
      @Value("${spring.mail.properties.mail.smtp.starttls.enable:true}") boolean tls) {
    this.db = db;
    this.crypto = crypto;
    this.json = json;
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
        "update private_mail set state='EXPIRED',last_error_code='TOKEN_DELIVERY_WINDOW_EXPIRED' where state in ('QUEUED','WAITING_CONFIGURATION') and created_at<now()-interval '15 minutes'");
    JavaMailSender mail = sender.getIfAvailable();
    if (!enabled || mail == null) {
      db.update(
          "update private_mail set state='WAITING_CONFIGURATION',last_error_code='SMTP_NOT_CONFIGURED' where state='QUEUED'");
      return;
    }
    for (var row :
        db.queryForList(
            "select id,payload_cipher from private_mail where state in ('QUEUED','WAITING_CONFIGURATION') and attempts<5 and next_attempt_at<=now() order by created_at limit 10")) {
      UUID id = (UUID) row.get("id");
      try {
        var payload = json.tree(crypto.open((String) row.get("payload_cipher")));
        boolean reset = "RESET".equals(payload.path("template").asText());
        String url =
            "educore://"
                + (reset ? "reset" : "verify-email")
                + "?token="
                + payload.path("token").asText();
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(payload.path("to").asText());
        message.setSubject(reset ? "Reset your EduCore password" : "Verify your EduCore email");
        message.setText(
            "Open this private EduCore link in your app:\n\n"
                + url
                + "\n\nIf you did not request this, ignore it. Do not share your password or private recovery code with school staff.");
        mail.send(message);
        db.update(
            "update private_mail set state='SENT',sent_at=now(),last_error_code=null where id=?",
            id);
      } catch (RuntimeException ex) {
        db.update(
            "update private_mail set attempts=attempts+1,next_attempt_at=now()+interval '2 minutes',last_error_code='SMTP_DELIVERY_FAILED' where id=?",
            id);
      }
    }
  }
}
