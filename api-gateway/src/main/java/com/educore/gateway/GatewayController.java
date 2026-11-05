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
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Fixed routing only: identity, school selection and authorisation remain service responsibilities.
 */
@RestController
public class GatewayController {
  private final Map<String, URI> upstreams;
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
            origin(identity),
            "academics",
            origin(academics),
            "finance",
            origin(finance),
            "communication",
            origin(communication));
  }

  static URI origin(String configured) {
    URI value = URI.create(configured);
    if (!Set.of("http", "https").contains(value.getScheme())
        || value.getHost() == null
        || value.getRawUserInfo() != null
        || value.getRawQuery() != null
        || value.getRawFragment() != null
        || !(value.getRawPath().isEmpty() || value.getRawPath().equals("/"))
        || value.getPort() == 0
        || value.getPort() > 65535)
      throw new IllegalArgumentException("An upstream must be a fixed HTTP(S) origin.");
    return value;
  }

  static URI target(URI origin, String path, String rawQuery) {
    if (route(path) == null) throw new IllegalArgumentException("Unavailable route.");
    URI target =
        UriComponentsBuilder.fromUri(origin)
            .replacePath(path)
            .replaceQuery(rawQuery)
            .build(true)
            .toUri();
    // The request may set only the encoded path/query; authority and scheme stay operator
    // configured.
    if (!origin.getScheme().equals(target.getScheme())
        || !origin.getHost().equals(target.getHost())
        || origin.getPort() != target.getPort()
        || target.getRawUserInfo() != null
        || target.getRawFragment() != null)
      throw new IllegalArgumentException("The upstream origin cannot be changed.");
    return target;
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
    if (suffix.matches("/activity/(?:identity|academics|finance|communication)(?:/.*)?"))
      return suffix.split("/")[2];
    if (suffix.startsWith("/onboarding/")) return "identity";
    if (suffix.matches(
        "/(?:invoices|payments|payment-intents|fee-plans|fee-batches|fee-adjustments|reconciliation)(?:/.*)?"))
      return "finance";
    if (suffix.matches("/(?:notices|threads|complaints|delivery)(?:/.*)?")) return "communication";
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
    URI destination;
    try {
      destination = target(upstreams.get(route), request.getRequestURI(), request.getQueryString());
    } catch (IllegalArgumentException ex) {
      return problem(400, "INVALID_REQUEST", "The request URL is invalid.");
    }
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(destination).timeout(Duration.ofSeconds(15));
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
