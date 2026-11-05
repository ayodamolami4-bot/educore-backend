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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
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
        .addFilterBefore(new ServiceAuthenticationFilter(env), CsrfFilter.class)
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
        .addFilterBefore(new BearerAuthenticationFilter(verifier), CsrfFilter.class)
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

  private static void authenticate(Object principal) {
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    SecurityContextHolder.setContext(context);
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

  private static final class BearerAuthenticationFilter extends OncePerRequestFilter {
    private final AccessVerifier verifier;

    BearerAuthenticationFilter(AccessVerifier verifier) {
      this.verifier = verifier;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      String header = request.getHeader("Authorization");
      if (header == null || !header.startsWith("Bearer ") || header.length() > 512)
        throw Api.fail(401, "SIGN_IN_REQUIRED", "Sign in to continue.");
      Actor actor = verifier.verify(header.substring(7));
      if (actor == null) throw Api.fail(401, "SIGN_IN_REQUIRED", "Sign in to continue.");
      authenticate(actor);
      chain.doFilter(request, response);
    }
  }

  private static final class ServiceAuthenticationFilter extends OncePerRequestFilter {
    private final Environment env;

    ServiceAuthenticationFilter(Environment env) {
      this.env = env;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      String name = request.getHeader("X-Service-Name"), key = request.getHeader("X-Service-Key");
      String expected =
          name != null && name.matches("[a-z]+") ? env.getProperty("educore.peers." + name) : null;
      if (expected == null || expected.length() < 32 || !Crypto.equal(expected, key))
        throw Api.fail(401, "INVALID_SERVICE", "Service authentication required.");
      authenticate(new Actor.Internal(name));
      chain.doFilter(request, response);
    }
  }
}
