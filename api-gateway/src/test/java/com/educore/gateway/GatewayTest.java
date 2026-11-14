package com.educore.gateway;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class GatewayTest {
  String school = "/api/v1/schools/12345678-1234-1234-1234-123456789012";

  @Test
  void fixedRoutesSeparateServicesAndKeepInternalRoutesUnavailable() {
    assertEquals("identity", GatewayController.route("/api/v1/me/contexts"));
    assertEquals("communication", GatewayController.route("/api/v1/me/notifications"));
    assertEquals("finance", GatewayController.route(school + "/invoices"));
    assertEquals("finance", GatewayController.route("/api/v1/webhooks/paystack"));
    assertEquals("communication", GatewayController.route(school + "/complaints"));
    assertEquals("communication", GatewayController.route(school + "/complaints/metadata"));
    assertEquals("communication", GatewayController.route(school + "/delivery/notifications"));
    assertEquals("finance", GatewayController.route(school + "/reconciliation/reports/catalogue"));
    assertEquals("academics", GatewayController.route(school + "/school-reports/catalogue"));
    assertEquals("academics", GatewayController.route(school + "/students"));
    assertEquals(
        "identity", GatewayController.route(school + "/onboarding/STUDENT/id/activation-slips"));
    assertNull(GatewayController.route("/internal/introspect"));
  }

  @Test
  void encodedTraversalAndArbitraryDestinationsAreRejected() {
    for (String path :
        new String[] {
          "/api/v1/https://example.invalid",
          "/api/v1/schools/../internal/events",
          school + "/%2f..%2finternal/events",
          "/api/v1//auth/login"
        }) assertNull(GatewayController.route(path));
  }

  @Test
  void encodedQueriesStayQueriesAtTheConfiguredOrigin() {
    var origin = GatewayController.origin("http://academics:8092");
    String query = "name=A%26B&next=https%3A%2F%2Fevil.invalid%2F&literal=%252f";
    var target = GatewayController.target(origin, school + "/students", query);
    assertEquals("academics", target.getHost());
    assertEquals(8092, target.getPort());
    assertEquals(query, target.getRawQuery());
    assertEquals(school + "/students", target.getRawPath());
    assertNull(target.getRawFragment());
    assertThrows(
        IllegalArgumentException.class,
        () -> GatewayController.target(origin, "//evil.invalid/path", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> GatewayController.target(origin, school + "/students", "name=value#fragment"));
  }

  @Test
  void upstreamConfigurationCannotEmbedCredentialsOrRequestComponents() {
    for (String invalid :
        new String[] {
          "file:///tmp/data",
          "http://user@academics:8092",
          "http://academics:8092/api",
          "http://academics:8092?next=elsewhere",
          "http://academics:8092#fragment",
          "http://academics:0"
        }) assertThrows(IllegalArgumentException.class, () -> GatewayController.origin(invalid));
  }
}
