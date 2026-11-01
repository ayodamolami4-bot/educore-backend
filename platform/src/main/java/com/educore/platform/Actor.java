package com.educore.platform;

import java.util.*;
import org.springframework.security.core.context.SecurityContextHolder;

public record Actor(UUID accountId, UUID sessionId, Context context) {
  public record Context(
      UUID schoolId,
      String schoolName,
      String role,
      UUID childId,
      String childName,
      UUID subjectId,
      List<String> actions) {}

  public static Actor current() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof Actor actor))
      throw Api.fail(401, "SIGN_IN_REQUIRED", "Sign in to continue.");
    return actor;
  }

  public Context school(UUID schoolId, String... roles) {
    if (context == null || !schoolId.equals(context.schoolId()))
      throw Api.fail(404, "NOT_FOUND", "The requested school record is unavailable.");
    if (roles.length > 0 && Arrays.stream(roles).noneMatch(context.role()::equals))
      throw Api.fail(403, "FORBIDDEN", "Your selected role cannot perform this action.");
    return context;
  }

  public void child(UUID id, boolean payer) {
    if (context == null
        || !"PARENT".equals(context.role())
        || !id.equals(context.childId())
        || (payer && !context.actions().contains("fees.pay")))
      throw Api.fail(404, "NOT_FOUND", "The child record is unavailable.");
  }

  public static String internalCaller() {
    Object p = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    if (!(p instanceof Internal i))
      throw Api.fail(403, "SERVICE_ONLY", "This operation is internal.");
    return i.service();
  }

  public static void internalOnly(String... services) {
    if (Arrays.stream(services).noneMatch(internalCaller()::equals))
      throw Api.fail(403, "SERVICE_FORBIDDEN", "This service cannot perform this operation.");
  }

  public record Internal(String service) {}
}
