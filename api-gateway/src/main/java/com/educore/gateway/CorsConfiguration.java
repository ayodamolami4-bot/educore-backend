package com.educore.gateway;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class CorsConfiguration implements WebMvcConfigurer {
  private final String[] origins;

  public CorsConfiguration(@Value("${educore.cors-origins}") String origins) {
    this.origins = Arrays.stream(origins.split(",")).map(String::strip).toArray(String[]::new);
    if (Arrays.asList(this.origins).contains("*"))
      throw new IllegalStateException("Configure explicit frontend origins.");
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping("/api/v1/**")
        .allowedOrigins(origins)
        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        .allowedHeaders(
            "Authorization",
            "Content-Type",
            "Accept",
            "If-Match",
            "Idempotency-Key",
            "X-Request-Id")
        .exposedHeaders("X-Request-Id", "ETag")
        .allowCredentials(false)
        .maxAge(600);
  }
}
