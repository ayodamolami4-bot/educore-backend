package com.educore.platform;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.security.web.authentication.AuthenticationFilter;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
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
  @Order(1)
  SecurityFilterChain publicEndpoints(HttpSecurity http, Json json) throws Exception {
    var paths = PathPatternRequestMatcher.withDefaults();
    List<RequestMatcher> publicRequests = new ArrayList<>();
    publicRequests.add(paths.matcher(org.springframework.http.HttpMethod.GET, "/actuator/health"));
    publicRequests.add(paths.matcher("/error"));
    publicRequests.add(
        paths.matcher(org.springframework.http.HttpMethod.GET, "/api/v1/public/access-help"));
    for (String path :
        List.of(
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            "/api/v1/auth/activation/review",
            "/api/v1/auth/password/setup",
            "/api/v1/auth/recovery/email",
            "/api/v1/auth/recovery/email/review",
            "/api/v1/auth/recovery/code/review",
            "/api/v1/auth/email-verifications/confirm",
            "/api/v1/webhooks/paystack"))
      publicRequests.add(paths.matcher(org.springframework.http.HttpMethod.POST, path));
    return boundary(http, json)
        .securityMatcher(new OrRequestMatcher(publicRequests))
        // Public commands take JSON proofs, never ambient cookies or HTTP Basic credentials.
        // Browser form/text submissions still require Spring's CSRF protection.
        .csrf(c -> c.ignoringRequestMatchers(PlatformSecurity::jsonRequest))
        .authorizeHttpRequests(a -> a.anyRequest().permitAll())
        .build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain internalEndpoints(HttpSecurity http, Json json, Environment env)
      throws Exception {
    return boundary(http, json)
        .securityMatcher("/internal/**")
        .addFilterBefore(serviceAuthentication(env, json), CsrfFilter.class)
        .csrf(c -> c.ignoringRequestMatchers(request -> verifiedPrincipal(Actor.Internal.class)))
        .authorizeHttpRequests(a -> a.anyRequest().authenticated())
        .build();
  }

  @Bean
  @Order(3)
  SecurityFilterChain userEndpoints(HttpSecurity http, Json json, AccessVerifier verifier)
      throws Exception {
    return boundary(http, json)
        .securityMatcher("/api/v1/**")
        .addFilterBefore(bearerAuthentication(verifier, json), CsrfFilter.class)
        .csrf(c -> c.ignoringRequestMatchers(request -> verifiedPrincipal(Actor.class)))
        .authorizeHttpRequests(a -> a.anyRequest().authenticated())
        .build();
  }

  @Bean
  @Order(4)
  SecurityFilterChain denyOtherEndpoints(HttpSecurity http, Json json) throws Exception {
    return boundary(http, json).authorizeHttpRequests(a -> a.anyRequest().denyAll()).build();
  }

  private static HttpSecurity boundary(HttpSecurity http, Json json) throws Exception {
    return http.securityContext(
            c -> c.securityContextRepository(new NullSecurityContextRepository()))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(c -> c.requestCache(new NullRequestCache()))
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (request, response, failure) ->
                            problem(
                                request,
                                response,
                                json,
                                Api.fail(401, "SIGN_IN_REQUIRED", "Sign in to continue.")))
                    .accessDeniedHandler(
                        (request, response, failure) ->
                            problem(
                                request,
                                response,
                                json,
                                Api.fail(403, "FORBIDDEN", "This request is not permitted."))))
        .addFilterBefore(new BoundaryFilter(json), SecurityContextHolderFilter.class);
  }

  private static boolean jsonRequest(HttpServletRequest request) {
    try {
      if (request.getContentType() == null) return false;
      MediaType type = MediaType.parseMediaType(request.getContentType());
      return "application".equals(type.getType()) && "json".equals(type.getSubtype());
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  private static boolean verifiedPrincipal(Class<?> type) {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication != null
        && authentication.isAuthenticated()
        && type.isInstance(authentication.getPrincipal());
  }

  private static void problem(
      HttpServletRequest request, HttpServletResponse response, Json json, Api.Failure ex)
      throws IOException {
    if (response.isCommitted()) return;
    response.setStatus(ex.status);
    response.setContentType("application/problem+json");
    response
        .getWriter()
        .write(
            json.write(
                new Api.Problem(
                    Api.title(ex.status),
                    ex.status,
                    ex.getMessage(),
                    ex.code,
                    (String) request.getAttribute("requestId"),
                    ex.retryable,
                    List.of())));
  }

  private static final class BoundaryFilter extends OncePerRequestFilter {
    private final Json json;

    BoundaryFilter(Json json) {
      this.json = json;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      String requestId = request.getHeader("X-Request-Id");
      if (requestId == null || !requestId.matches("[A-Za-z0-9-]{1,64}"))
        requestId = UUID.randomUUID().toString();
      request.setAttribute("requestId", requestId);
      response.setHeader("X-Request-Id", requestId);
      try {
        chain.doFilter(request, response);
      } catch (Api.Failure ex) {
        problem(request, response, json, ex);
      }
    }
  }

  private static AuthenticationFilter bearerAuthentication(AccessVerifier verifier, Json json) {
    AuthenticationManager manager =
        credentials -> {
          Actor actor = verifier.verify((String) credentials.getCredentials());
          if (actor == null) throw Api.fail(401, "SIGN_IN_REQUIRED", "Sign in to continue.");
          return UsernamePasswordAuthenticationToken.authenticated(actor, null, List.of());
        };
    AuthenticationConverter converter =
        request -> {
          String header = request.getHeader("Authorization");
          if (header == null || !header.startsWith("Bearer ") || header.length() > 512)
            throw Api.fail(401, "SIGN_IN_REQUIRED", "Sign in to continue.");
          return UsernamePasswordAuthenticationToken.unauthenticated(
              "opaque-session", header.substring(7));
        };
    return credentialFilter(manager, converter, json);
  }

  private static AuthenticationFilter serviceAuthentication(Environment env, Json json) {
    AuthenticationManager manager =
        credentials -> {
          String name = credentials.getName(), key = (String) credentials.getCredentials();
          String expected =
              name.matches("[a-z]+") ? env.getProperty("educore.peers." + name) : null;
          if (expected == null || expected.length() < 32 || !Crypto.equal(expected, key))
            throw Api.fail(401, "INVALID_SERVICE", "Service authentication required.");
          return UsernamePasswordAuthenticationToken.authenticated(
              new Actor.Internal(name), null, List.of());
        };
    AuthenticationConverter converter =
        request ->
            UsernamePasswordAuthenticationToken.unauthenticated(
                Objects.requireNonNullElse(request.getHeader("X-Service-Name"), ""),
                Objects.requireNonNullElse(request.getHeader("X-Service-Key"), ""));
    return credentialFilter(manager, converter, json);
  }

  private static AuthenticationFilter credentialFilter(
      AuthenticationManager manager, AuthenticationConverter converter, Json json) {
    // Spring performs the verification and installs the verified context. A null conversion
    // still cannot pass the chain's authenticated() authorization rule.
    var filter = new AuthenticationFilter(manager, converter);
    filter.setSecurityContextRepository(new NullSecurityContextRepository());
    filter.setSuccessHandler(
        new AuthenticationSuccessHandler() {
          @Override
          public void onAuthenticationSuccess(
              HttpServletRequest request,
              HttpServletResponse response,
              Authentication authentication) {}

          @Override
          public void onAuthenticationSuccess(
              HttpServletRequest request,
              HttpServletResponse response,
              FilterChain chain,
              Authentication authentication)
              throws IOException, ServletException {
            chain.doFilter(request, response);
          }
        });
    filter.setFailureHandler(
        (request, response, failure) ->
            problem(
                request,
                response,
                json,
                Api.fail(401, "SIGN_IN_REQUIRED", "Sign in to continue.")));
    return filter;
  }
}
