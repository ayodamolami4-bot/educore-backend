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
}
