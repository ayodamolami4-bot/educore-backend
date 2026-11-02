package com.educore.gateway;

import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/**
 * Fixed routing only: identity, school selection and authorisation remain service responsibilities.
 */
@RestController
public class GatewayController {
  private final Map<String, String> upstreams;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(3))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public GatewayController(
      @Value("${educore.identity-url}") String identity,
      @Value("${educore.academics-url}") String academics,
      @Value("${educore.finance-url}") String finance,
      @Value("${educore.communication-url}") String communication) {
    upstreams =
        Map.of(
            "identity",
            identity,
            "academics",
            academics,
            "finance",
            finance,
            "communication",
            communication);
  }

  public static String route(String path) {
    if (!path.startsWith("/api/v1/")
        || path.contains("..")
        || path.contains("\\")
        || path.contains("//")
        || path.toLowerCase(Locale.ROOT).matches(".*%(?:2e|2f|5c).*")) return null;
    String p = path.substring(7);
    if (p.startsWith("/me/notifications")) return "communication";
    if (p.equals("/me")
        || p.startsWith("/me/")
        || p.startsWith("/auth/")
        || p.equals("/public/access-help")) return "identity";
    if (p.equals("/webhooks/paystack")) return "finance";
    if (!p.matches("/schools/[0-9a-fA-F-]{36}(?:/.*)?")) return null;
    String suffix = p.substring(45);
    if (suffix.startsWith("/onboarding/")) return "identity";
    if (suffix.matches(
        "/(?:invoices|payments|payment-intents|fee-plans|fee-batches|fee-adjustments|reconciliation)(?:/.*)?"))
      return "finance";
    if (suffix.matches("/(?:notices|threads|complaints)(?:/.*)?")) return "communication";
    return "academics";
  }

  @RequestMapping("/api/v1/**")
  public ResponseEntity<?> proxy(HttpServletRequest request) throws IOException {
    String route = route(request.getRequestURI());
    if (route == null) return problem(404, "NOT_FOUND", "This API route is unavailable.");
    int maximum =
        request
                .getRequestURI()
                .matches("/api/v1/schools/[0-9a-fA-F-]{36}/files/[0-9a-fA-F-]{36}/content")
            ? 10 * 1024 * 1024
            : 2 * 1024 * 1024;
    byte[] body = request.getInputStream().readNBytes(maximum + 1);
    if (body.length > maximum)
      return problem(413, "REQUEST_TOO_LARGE", "The request exceeds the permitted size.");
    String query = request.getQueryString();
    String url =
        upstreams.get(route) + request.getRequestURI() + (query == null ? "" : "?" + query);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15));
    for (String header :
        List.of(
            "Authorization",
            "Content-Type",
            "Accept",
            "If-Match",
            "Idempotency-Key",
            "X-Paystack-Signature")) {
      String value = request.getHeader(header);
      if (value != null) builder.header(header, value);
    }
    String requestId = request.getHeader("X-Request-Id");
    if (requestId == null || !requestId.matches("[A-Za-z0-9-]{1,64}"))
      requestId = UUID.randomUUID().toString();
    builder.header("X-Request-Id", requestId);
    builder.method(
        request.getMethod(),
        body.length == 0
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(body));
    try {
      HttpResponse<byte[]> response =
          client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
      HttpHeaders headers = new HttpHeaders();
      for (String h : List.of("Content-Type", "Content-Disposition", "ETag", "X-Request-Id"))
        response.headers().firstValue(h).ifPresent(v -> headers.set(h, v));
      headers.setCacheControl("no-store");
      return new ResponseEntity<>(
          response.body(), headers, HttpStatusCode.valueOf(response.statusCode()));
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return problem(503, "SERVICE_UNAVAILABLE", "A required service is temporarily unavailable.");
    } catch (IOException ex) {
      return problem(503, "SERVICE_UNAVAILABLE", "A required service is temporarily unavailable.");
    }
  }

  private static ResponseEntity<?> problem(int status, String code, String detail) {
    return ResponseEntity.status(status)
        .contentType(MediaType.valueOf("application/problem+json"))
        .body(
            Map.of(
                "title",
                "Request failed",
                "status",
                status,
                "detail",
                detail,
                "code",
                code,
                "retryable",
                status == 503,
                "requestId",
                UUID.randomUUID().toString()));
  }
}
