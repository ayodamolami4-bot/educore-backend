package com.educore.identity;

import com.educore.platform.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class IdentityController {
  public record SchoolAudience(UUID schoolId) {}

  @PostMapping("/internal/active-principals")
  Object activePrincipals(@RequestBody SchoolAudience b) {
    return service.activePrincipals(b.schoolId());
  }

  @PostMapping("/internal/school-audience")
  Object schoolAudience(@RequestBody SchoolAudience b) {
    return service.schoolAudience(b.schoolId());
  }

  public record BootstrapSlip(UUID schoolId, UUID principalSubjectId, String idempotencyKey) {}

  @PostMapping("/internal/bootstrap-slip")
  Object firstPrincipalSlip(@RequestBody BootstrapSlip b) {
    return service.firstPrincipalSlip(b.schoolId(), b.principalSubjectId(), b.idempotencyKey());
  }

  private final IdentityService service;
  private final InvitationService invitations;

  public IdentityController(IdentityService service, InvitationService invitations) {
    this.service = service;
    this.invitations = invitations;
  }

  public record Login(
      String identifier, String password, String installationId, String deviceName) {}

  public record Token(String refreshToken) {}

  public record AccessToken(String accessToken) {}

  public record Activation(String loginId, String activationCode, String invitationToken) {}

  public record Identifier(String identifier) {}

  public record Proof(String token) {}

  public record Recovery(String loginId, String recoveryCode) {}

  public record Setup(String flowToken, String newPassword) {}

  public record ChangePassword(String currentPassword, String newPassword) {}

  public record Password(String currentPassword) {}

  public record Recent(String recentAuthToken) {}

  public record Preference(UUID schoolId, String role, UUID childId) {}

  public record Phone(String phone) {}

  public record Preferences(
      boolean pushEnabled, boolean emailOptionalEnabled, List<String> categories) {}

  public record Email(String email, String recentAuthToken) {}

  public record Resend(UUID requestId) {}

  public record Push(String installationId, String platform, String pushToken) {}

  public record Event(UUID eventId, String kind, JsonNode payload) {}

  public record Audience(UUID schoolId, UUID childId, String category) {}

  @PostMapping("/api/v1/auth/login")
  Object login(@RequestBody Login b) {
    return service.login(b.identifier(), b.password(), b.installationId(), b.deviceName());
  }

  @PostMapping("/api/v1/auth/refresh")
  Object refresh(@RequestBody Token b) {
    return service.refresh(b.refreshToken());
  }

  @PostMapping("/api/v1/auth/logout")
  Object logout(@RequestBody Token b) {
    return service.logout(b.refreshToken());
  }

  @PostMapping("/api/v1/auth/activation/review")
  Object activation(@RequestBody Activation b) {
    return service.activation(b.loginId(), b.activationCode(), b.invitationToken());
  }

  @PostMapping("/api/v1/auth/recovery/email")
  Object emailRecovery(@RequestBody Identifier b) {
    return service.emailRecovery(b.identifier());
  }

  @PostMapping("/api/v1/auth/recovery/email/review")
  Object emailReview(@RequestBody Proof b) {
    return service.emailReview(b.token());
  }

  @PostMapping("/api/v1/auth/recovery/code/review")
  Object recoveryReview(@RequestBody Recovery b) {
    return service.recoveryReview(b.loginId(), b.recoveryCode());
  }

  @PostMapping("/api/v1/auth/password/setup")
  Object setup(@RequestBody Setup b) {
    return service.setupPassword(b.flowToken(), b.newPassword());
  }

  @PostMapping("/api/v1/me/password/change")
  Object changePassword(@RequestBody ChangePassword b) {
    return service.changePassword(Actor.current(), b.currentPassword(), b.newPassword());
  }

  @GetMapping("/api/v1/me")
  Object me() {
    return service.me(Actor.current());
  }

  @PatchMapping("/api/v1/me")
  Object phone(
      @RequestBody Phone b, @RequestHeader(value = "If-Match", required = false) String version) {
    return service.phone(Actor.current(), b.phone(), Api.version(version));
  }

  @GetMapping("/api/v1/me/contexts")
  Object contexts() {
    return service.contexts(Actor.current());
  }

  @PutMapping("/api/v1/me/context")
  Object context(@RequestBody Preference b) {
    return service.choose(Actor.current(), b.schoolId(), b.role(), b.childId());
  }

  @PostMapping("/api/v1/me/reauthenticate")
  Object reauth(@RequestBody Password b) {
    return service.reauthenticate(Actor.current(), b.currentPassword());
  }

  @PostMapping("/api/v1/me/recovery-code/rotate")
  Object rotate(@RequestBody Recent b) {
    return service.rotate(Actor.current(), b.recentAuthToken());
  }

  @PostMapping("/api/v1/me/sessions/revoke-others")
  Object revoke(
      @RequestBody Recent b,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.revokeOthers(Actor.current(), b.recentAuthToken(), key);
  }

  @GetMapping("/api/v1/me/sessions")
  Object sessions(
      @RequestParam(required = false) UUID cursor, @RequestParam(defaultValue = "30") int limit) {
    return service.sessions(Actor.current(), cursor, Api.pageSize(limit));
  }

  @GetMapping("/api/v1/me/notification-preferences")
  Object preferences() {
    return service.preferences(Actor.current());
  }

  @PatchMapping("/api/v1/me/notification-preferences")
  Object preferences(@RequestBody Preferences b) {
    return service.preferences(
        Actor.current(), b.pushEnabled(), b.emailOptionalEnabled(), b.categories());
  }

  @PostMapping("/api/v1/me/email-change-requests")
  @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
  Object email(
      @RequestBody Email b,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.changeEmail(Actor.current(), b.email(), b.recentAuthToken(), key);
  }

  @PostMapping("/api/v1/me/email-change-requests/resend")
  @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
  Object resend(
      @RequestBody Resend b,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.resendEmail(Actor.current(), b.requestId(), key);
  }

  @PostMapping("/api/v1/auth/email-verifications/confirm")
  Object confirm(@RequestBody Proof b) {
    return service.confirmEmail(b.token());
  }

  @PostMapping("/api/v1/me/push-devices")
  Object push(
      @RequestBody Push b, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return service.registerPush(
        Actor.current(), b.installationId(), b.platform(), b.pushToken(), key);
  }

  @DeleteMapping("/api/v1/me/push-devices/{id}")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  void removePush(@PathVariable UUID id) {
    service.removePush(Actor.current(), id);
  }

  @PostMapping("/api/v1/schools/{school}/onboarding/{type}/{subject}/activation-slips")
  Object slip(
      @PathVariable UUID school,
      @PathVariable String type,
      @PathVariable UUID subject,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return invitations.issue(Actor.current(), school, type, subject, key);
  }

  @GetMapping("/api/v1/public/access-help")
  Object help() {
    return Api.map(
        "platformContact",
        "Contact your school office using its independently confirmed contact details.",
        "instructions",
        List.of(
            "Your school creates or links your account and provides your EduCore login ID.",
            "Use your school-issued one-time activation code to choose your private password.",
            "Recover using verified email or your privately saved recovery code.",
            "Ask the school office to review a missing school or child relationship."),
        "assistedRecoveryAvailable",
        false);
  }

  @PostMapping("/internal/introspect")
  Object introspect(@RequestBody AccessToken b) {
    Actor.internalOnly("academics", "finance", "communication");
    return service.verify(b.accessToken());
  }

  @PostMapping("/internal/events")
  Object event(@RequestBody Event b) {
    return service.event(b.eventId(), b.kind(), b.payload());
  }

  @PostMapping("/internal/audience")
  Object audience(@RequestBody Audience b) {
    return service.internalAudience(b.schoolId(), b.childId(), b.category());
  }

  public record SubjectAccount(UUID schoolId, UUID subjectId, String role, UUID childId) {}

  @PostMapping("/internal/subject-account")
  Object subjectAccount(@RequestBody SubjectAccount b) {
    return service.subjectAccount(b.schoolId(), b.subjectId(), b.role(), b.childId());
  }

  public record Delivery(UUID accountId, UUID schoolId, UUID childId, String category) {}

  @PostMapping("/internal/delivery-context")
  Object delivery(@RequestBody Delivery b) {
    return service.deliveryContext(b.accountId(), b.schoolId(), b.childId(), b.category());
  }

  public record DisableDevice(UUID accountId, UUID deviceId) {}

  @PostMapping("/internal/disable-push-device")
  Object disable(@RequestBody DisableDevice b) {
    return service.disablePush(b.accountId(), b.deviceId());
  }
}
