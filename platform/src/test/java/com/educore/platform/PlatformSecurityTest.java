package com.educore.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.servlet.http.Cookie;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.*;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringJUnitWebConfig(PlatformSecurityTest.Config.class)
@org.springframework.test.context.TestExecutionListeners({
  org.springframework.test.context.web.ServletTestExecutionListener.class,
  org.springframework.test.context.support.DependencyInjectionTestExecutionListener.class
})
@TestPropertySource(
    properties = "educore.peers.academics=isolated-test-peer-key-with-at-least-32-characters")
class PlatformSecurityTest {
  static final String TOKEN = UUID.randomUUID().toString();
  static final String PEER = "isolated-test-peer-key-with-at-least-32-characters";
  static final Actor ACTOR = new Actor(UUID.randomUUID(), UUID.randomUUID(), null);
  @Autowired WebApplicationContext context;
  @Autowired FilterChainProxy filters;
  @Autowired TestEndpoints endpoints;
  MockMvc mvc;

  @BeforeEach
  void setup() {
    endpoints.writes.set(0);
    mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filters).build();
  }

  @AfterEach
  void noLeakedAuthentication() {
    assertNull(SecurityContextHolder.getContext().getAuthentication());
  }

  @Test
  void everyProtectedRequestRequiresItsOwnVerifiedBearerToken() throws Exception {
    mvc.perform(get("/api/v1/protected").header("Authorization", "Bearer " + TOKEN))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/v1/protected")
                .header("Authorization", "Bearer " + TOKEN)
                .cookie(new Cookie("JSESSIONID", "incidental-cookie"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());
    assertEquals(1, endpoints.writes.get());

    var forgedSession = new MockHttpSession();
    var forgedContext = SecurityContextHolder.createEmptyContext();
    forgedContext.setAuthentication(
        new UsernamePasswordAuthenticationToken(ACTOR, null, List.of()));
    forgedSession.setAttribute("SPRING_SECURITY_CONTEXT", forgedContext);
    mvc.perform(
            post("/api/v1/protected")
                .session(forgedSession)
                .cookie(new Cookie("JSESSIONID", "forged"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"))
        .andExpect(header().exists("X-Request-Id"));
    mvc.perform(get("/api/v1/protected").header("Authorization", "Basic dXNlcjpwYXNz"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/protected").header("Authorization", "Bearer invalid"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            get("/api/v1/protected")
                .header("X-Service-Name", "academics")
                .header("X-Service-Key", PEER))
        .andExpect(status().isUnauthorized());
    assertEquals(1, endpoints.writes.get());
  }

  @Test
  void peerAuthenticationCannotBeReplacedByUserCredentials() throws Exception {
    mvc.perform(
            post("/internal/test")
                .header("X-Service-Name", "academics")
                .header("X-Service-Key", PEER))
        .andExpect(status().isOk())
        .andExpect(content().string("academics"));
    mvc.perform(post("/internal/test").header("Authorization", "Bearer " + TOKEN))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_SERVICE"));
    mvc.perform(
            post("/internal/test")
                .header("X-Service-Name", "academics")
                .header("X-Service-Key", "invalid"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/internal/test")
                .header("X-Service-Name", "../academics")
                .header("X-Service-Key", PEER))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void onlyExplicitPublicMethodsAcceptJsonAndBrowserFormsRemainCsrfProtected() throws Exception {
    mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isOk());
    for (String type : List.of("application/x-www-form-urlencoded", "text/plain", "*/*")) {
      mvc.perform(post("/api/v1/auth/login").contentType(type).content("{}"))
          .andExpect(status().isForbidden());
    }
    mvc.perform(get("/api/v1/auth/login")).andExpect(status().isUnauthorized());
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    mvc.perform(get("/unregistered")).andExpect(status().isUnauthorized());
    assertEquals(0, endpoints.writes.get());
  }

  @Configuration
  @EnableWebSecurity
  @EnableWebMvc
  @Import(PlatformSecurity.class)
  static class Config {
    @Bean
    Json json() {
      return new Json();
    }

    @Bean
    @Primary
    AccessVerifier accessVerifier() {
      return token -> {
        if (!TOKEN.equals(token)) throw Api.fail(401, "SIGN_IN_REQUIRED", "Sign in to continue.");
        return ACTOR;
      };
    }

    @Bean
    ServiceHttp serviceHttp() {
      return new ServiceHttp(
          "test",
          "http://127.0.0.1:8091",
          "http://127.0.0.1:8092",
          "http://127.0.0.1:8094",
          new org.springframework.mock.env.MockEnvironment());
    }

    @Bean
    TestEndpoints testEndpoints() {
      return new TestEndpoints();
    }
  }

  @RestController
  static class TestEndpoints {
    final AtomicInteger writes = new AtomicInteger();

    @GetMapping("/api/v1/protected")
    String read() {
      return Actor.current().accountId().toString();
    }

    @PostMapping("/api/v1/protected")
    String write() {
      Actor.current();
      writes.incrementAndGet();
      return "accepted";
    }

    @PostMapping("/internal/test")
    String internal() {
      return Actor.internalCaller();
    }

    @PostMapping("/api/v1/auth/login")
    String login() {
      return "public-json";
    }

    @GetMapping("/actuator/health")
    String health() {
      return "healthy";
    }
  }
}
