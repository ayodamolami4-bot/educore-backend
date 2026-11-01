package com.educore.platform;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class PlatformSecurity {
  @Bean
  @ConditionalOnMissingBean(AccessVerifier.class)
  AccessVerifier remoteVerifier(
      ServiceHttp http, @Value("${educore.identity-url:http://localhost:8091}") String base) {
    return token ->
        http.post(base, "/internal/introspect", Map.of("accessToken", token), Actor.class);
  }

  @Bean
  SecurityFilterChain chain(HttpSecurity http, AccessVerifier verifier, Json json, Environment env)
      throws Exception {
    OncePerRequestFilter filter =
        new OncePerRequestFilter() {
          @Override
          protected void doFilterInternal(
              HttpServletRequest req, HttpServletResponse res, FilterChain chain)
              throws ServletException, IOException {
            String requestId = req.getHeader("X-Request-Id");
            if (requestId == null || !requestId.matches("[A-Za-z0-9-]{1,64}"))
              requestId = UUID.randomUUID().toString();
            req.setAttribute("requestId", requestId);
            res.setHeader("X-Request-Id", requestId);
            try {
              String path = req.getRequestURI();
              if (path.startsWith("/internal/")) {
                String name = req.getHeader("X-Service-Name"), key = req.getHeader("X-Service-Key");
                String expected =
                    name != null && name.matches("[a-z]+")
                        ? env.getProperty("educore.peers." + name)
                        : null;
                if (expected == null || expected.length() < 32 || !Crypto.equal(expected, key))
                  throw Api.fail(401, "INVALID_SERVICE", "Service authentication required.");
                authenticate(new Actor.Internal(name));
              } else if (!publicPath(path, req.getMethod())) {
                String header = req.getHeader("Authorization");
                if (header == null || !header.startsWith("Bearer ") || header.length() > 512)
                  throw Api.fail(401, "SIGN_IN_REQUIRED", "Sign in to continue.");
                authenticate(verifier.verify(header.substring(7)));
              }
              chain.doFilter(req, res);
            } catch (Api.Failure ex) {
              if (!res.isCommitted()) {
                res.setStatus(ex.status);
                res.setContentType("application/problem+json");
                res.getWriter()
                    .write(
                        json.write(
                            new Api.Problem(
                                Api.title(ex.status),
                                ex.status,
                                ex.getMessage(),
                                ex.code,
                                (String) req.getAttribute("requestId"),
                                ex.retryable,
                                List.of())));
              }
            } finally {
              org.springframework.security.core.context.SecurityContextHolder.clearContext();
            }
          }

          private void authenticate(Object principal) {
            org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(
                    new UsernamePasswordAuthenticationToken(principal, null, List.of()));
          }
        };
    return http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(a -> a.anyRequest().permitAll())
        .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  private static boolean publicPath(String p, String method) {
    if (p.equals("/actuator/health") || p.equals("/error")) return true;
    if (method.equals("GET") && p.equals("/api/v1/public/access-help")) return true;
    if (!method.equals("POST")) return false;
    return Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            "/api/v1/auth/activation/review",
            "/api/v1/auth/password/setup",
            "/api/v1/auth/recovery/email",
            "/api/v1/auth/recovery/email/review",
            "/api/v1/auth/recovery/code/review",
            "/api/v1/auth/email-verifications/confirm",
            "/api/v1/webhooks/paystack")
        .contains(p);
  }
}
