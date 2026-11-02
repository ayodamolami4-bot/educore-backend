package com.educore.platform;

import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
public final class ServiceHttp {
  private final RestClient http;
  private final String name;
  private final Environment environment;
  private final Map<String, String> destinations;

  public ServiceHttp(
      @Value("${educore.service-name}") String name,
      @Value("${educore.identity-url:http://localhost:8091}") String identity,
      @Value("${educore.academics-url:http://localhost:8092}") String academics,
      @Value("${educore.communication-url:http://localhost:8094}") String communication,
      Environment environment) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(3));
    factory.setReadTimeout(Duration.ofSeconds(8));
    this.http = RestClient.builder().requestFactory(factory).build();
    this.name = name;
    this.environment = environment;
    this.destinations =
        Map.of(identity, "identity", academics, "academics", communication, "communication");
  }

  public <T> T post(String base, String path, Object body, Class<T> type) {
    String destination = destinations.get(base);
    String key =
        destination == null
            ? null
            : environment.getProperty("educore.outgoing." + destination + "-key");
    if (key == null || key.length() < 32)
      throw new Api.Failure(
          503,
          "SERVICE_CREDENTIAL_NOT_CONFIGURED",
          "A required service connection is not configured.",
          false);
    try {
      return http.post()
          .uri(base + path)
          .header("X-Service-Name", name)
          .header("X-Service-Key", key)
          .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve()
          .body(type);
    } catch (RestClientResponseException ex) {
      if (ex.getStatusCode().value() == 401)
        throw Api.fail(401, "SESSION_EXPIRED", "Sign in again.");
      if (ex.getStatusCode().value() == 404)
        throw Api.fail(
            404, "NOT_FOUND", "The requested school record or relationship is unavailable.");
      if (java.util.Set.of(400, 403, 409, 412, 422, 428).contains(ex.getStatusCode().value()))
        throw Api.fail(
            ex.getStatusCode().value(),
            "RELATED_RECORD_VALIDATION_FAILED",
            "A related school record did not pass the required access or validation check.");
      throw new Api.Failure(
          503,
          "DEPENDENCY_UNAVAILABLE",
          "A required school service is temporarily unavailable.",
          true);
    } catch (RestClientException ex) {
      throw new Api.Failure(
          503,
          "DEPENDENCY_UNAVAILABLE",
          "A required school service is temporarily unavailable.",
          true);
    }
  }
}
