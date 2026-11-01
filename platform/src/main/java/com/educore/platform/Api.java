package com.educore.platform;

import java.util.*;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

public final class Api {
  private Api() {}

  public static Map<String, Object> map(Object... pairs) {
    var result = new LinkedHashMap<String, Object>();
    for (int i = 0; i < pairs.length; i += 2)
      if (pairs[i + 1] != null) result.put((String) pairs[i], pairs[i + 1]);
    return result;
  }

  public record FieldError(String field, String code, String message) {}

  public record Problem(
      String title,
      int status,
      String detail,
      String code,
      String requestId,
      boolean retryable,
      List<FieldError> errors) {}

  public record Page<T>(List<T> items, String nextCursor, boolean hasMore) {}

  public static final class Failure extends RuntimeException {
    public final int status;
    public final String code;
    public final boolean retryable;

    public Failure(int status, String code, String detail) {
      this(status, code, detail, false);
    }

    public Failure(int status, String code, String detail, boolean retryable) {
      super(detail);
      this.status = status;
      this.code = code;
      this.retryable = retryable;
    }
  }

  public static Failure fail(int status, String code, String detail) {
    return new Failure(status, code, detail);
  }

  public static UUID uuid(String value) {
    try {
      return UUID.fromString(value);
    } catch (RuntimeException ex) {
      throw fail(422, "INVALID_ID", "Use a valid record identifier.");
    }
  }

  public static long version(String ifMatch) {
    if (ifMatch == null)
      throw fail(428, "VERSION_REQUIRED", "Reload the record and send its If-Match version.");
    if (!Pattern.matches("\"v[1-9][0-9]*\"", ifMatch))
      throw fail(422, "INVALID_VERSION", "If-Match must be a quoted version, such as \"v1\".");
    try {
      return Long.parseLong(ifMatch.substring(2, ifMatch.length() - 1));
    } catch (NumberFormatException ex) {
      throw fail(422, "INVALID_VERSION", "Invalid record version.");
    }
  }

  public static void expectVersion(long expected, long actual) {
    if (expected != actual)
      throw fail(
          412, "STALE_VERSION", "This record changed. Keep your draft, reload and review it.");
  }

  public static String text(String value, int min, int max, String label) {
    if (value == null || value.strip().length() < min || value.strip().length() > max)
      throw fail(422, "INVALID_FIELD", label + " must contain " + min + "–" + max + " characters.");
    return value.strip();
  }

  public static String phone(String value) {
    String v = text(value, 10, 25, "Phone").replaceAll("[ ()-]", "");
    if (v.matches("0[789][01][0-9]{8}")) v = "+234" + v.substring(1);
    if (!v.matches("\\+234[789][01][0-9]{8}"))
      throw fail(422, "INVALID_PHONE", "Use a valid Nigerian mobile number.");
    return v;
  }

  public static String personName(String value) {
    String v = text(value, 2, 120, "Full name");
    if (!v.matches("[\\p{L}\\p{M} .'-]+") || v.codePoints().noneMatch(Character::isLetter))
      throw fail(
          422, "INVALID_NAME", "Use a person's name with letters, spaces, apostrophes or hyphens.");
    return v;
  }

  public static String email(String value) {
    String v = text(value, 5, 254, "Email").toLowerCase(Locale.ROOT);
    if (!v.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
      throw fail(422, "INVALID_EMAIL", "Use a valid email address.");
    return v;
  }

  public static String maskedEmail(String value) {
    int i = value.indexOf('@');
    return value.substring(0, 1) + "***" + value.substring(i);
  }

  public static int pageSize(Integer limit) {
    return limit == null ? 30 : Math.min(100, Math.max(1, limit));
  }

  public static <T> Page<T> page(
      List<T> rows, int size, java.util.function.Function<T, String> key) {
    boolean more = rows.size() > size;
    List<T> items = List.copyOf(rows.subList(0, Math.min(size, rows.size())));
    return new Page<>(items, more ? key.apply(items.get(items.size() - 1)) : null, more);
  }

  public static String title(int status) {
    HttpStatus s = HttpStatus.resolve(status);
    return s == null ? "Request failed" : s.getReasonPhrase();
  }
}
