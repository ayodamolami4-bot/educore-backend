package com.educore.finance;

import com.educore.platform.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Paystack {
  private final String key, base;
  private final Json json;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(4))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public Paystack(
      @Value("${educore.paystack-key:}") String key,
      @Value("${educore.paystack-base-url:https://api.paystack.co}") String base,
      Json json) {
    this.key = key;
    this.base = base;
    this.json = json;
    if (!key.isEmpty() && !key.startsWith("sk_test_"))
      throw new IllegalStateException(
          "This development backend only accepts a Paystack TEST secret key.");
    URI u = URI.create(base);
    if (!"api.paystack.co".equals(u.getHost())
        && !Set.of("localhost", "127.0.0.1").contains(u.getHost()))
      throw new IllegalStateException(
          "Use Paystack's API origin or an explicit local test server.");
    if ("api.paystack.co".equals(u.getHost()) && !"https".equals(u.getScheme()))
      throw new IllegalStateException("Paystack requires HTTPS.");
  }

  public void configured() {
    if (key.isBlank())
      throw Api.fail(
          503,
          "PAYSTACK_NOT_CONFIGURED",
          "Paystack test checkout is not configured. No payment has been initiated.");
  }

  public JsonNode initialize(String reference, long amount, String email, String subaccount) {
    configured();
    return request(
        "POST",
        "/transaction/initialize",
        Api.map(
            "reference",
            reference,
            "amount",
            Long.toString(amount),
            "email",
            email,
            "currency",
            "NGN",
            "subaccount",
            subaccount,
            "bearer",
            "subaccount"));
  }

  public JsonNode verify(String reference) {
    configured();
    if (!reference.matches("[A-Za-z0-9.=-]{1,80}"))
      throw Api.fail(422, "INVALID_REFERENCE", "Invalid transaction reference.");
    return request("GET", "/transaction/verify/" + reference, null);
  }

  public JsonNode refund(String id) {
    configured();
    if (id == null || !id.matches("[A-Za-z0-9_-]{1,100}"))
      throw Api.fail(422, "INVALID_REFUND_REFERENCE", "Use the provider's refund identifier.");
    return request("GET", "/refund/" + id, null);
  }

  public JsonNode settlements(
      String subaccount, java.time.LocalDate from, java.time.LocalDate to, int page) {
    configured();
    if (subaccount == null
        || !subaccount.matches("ACCT_[A-Za-z0-9]{1,70}")
        || page < 1
        || page > 100)
      throw Api.fail(
          422,
          "INVALID_SETTLEMENT_SCOPE",
          "Choose the configured school subaccount and a valid page.");
    return request(
        "GET",
        "/settlement?subaccount="
            + subaccount
            + "&from="
            + from
            + "&to="
            + to
            + "&status=success&perPage=100&page="
            + page,
        null);
  }

  public JsonNode settlementTransactions(String id, int page) {
    configured();
    if (id == null || !id.matches("[0-9]{1,30}") || page < 1 || page > 100)
      throw Api.fail(422, "INVALID_SETTLEMENT_ID", "Use the provider settlement identifier.");
    return request("GET", "/settlement/" + id + "/transactions?perPage=100&page=" + page, null);
  }

  private JsonNode request(String method, String path, Object body) {
    try {
      HttpRequest.Builder b =
          HttpRequest.newBuilder(URI.create(base + path))
              .timeout(Duration.ofSeconds(10))
              .header("Authorization", "Bearer " + key)
              .header("Content-Type", "application/json");
      b.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(json.write(body)));
      var r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
      if (r.statusCode() < 200 || r.statusCode() >= 300)
        throw Api.fail(
            503, "PROVIDER_UNAVAILABLE", "The payment provider could not confirm this request.");
      JsonNode result = json.tree(r.body());
      if (!result.path("status").asBoolean())
        throw Api.fail(
            503, "PROVIDER_UNAVAILABLE", "The payment provider did not accept this request.");
      return result.path("data");
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new Api.Failure(
          503, "PROVIDER_UNAVAILABLE", "Payment verification is temporarily unavailable.", true);
    } catch (java.io.IOException ex) {
      throw new Api.Failure(
          503, "PROVIDER_UNAVAILABLE", "Payment verification is temporarily unavailable.", true);
    }
  }

  public boolean signature(byte[] raw, String supplied) {
    if (key.isBlank() || supplied == null || !supplied.matches("[a-fA-F0-9]{128}")) return false;
    try {
      Mac mac = Mac.getInstance("HmacSHA512");
      mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
      return Crypto.equal(
          HexFormat.of().formatHex(mac.doFinal(raw)), supplied.toLowerCase(Locale.ROOT));
    } catch (java.security.GeneralSecurityException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
