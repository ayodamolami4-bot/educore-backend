package com.educore.platform;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ProblemAdvice {
  @ExceptionHandler(Api.Failure.class)
  ResponseEntity<Api.Problem> known(Api.Failure ex, HttpServletRequest r) {
    return response(ex.status, ex.code, ex.getMessage(), ex.retryable, List.of(), r);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<Api.Problem> validation(MethodArgumentNotValidException ex, HttpServletRequest r) {
    return response(
        422,
        "VALIDATION_FAILED",
        "Review the highlighted fields.",
        false,
        ex.getBindingResult().getFieldErrors().stream()
            .map(e -> new Api.FieldError(e.getField(), "INVALID", e.getDefaultMessage()))
            .toList(),
        r);
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class
  })
  ResponseEntity<Api.Problem> invalid(Exception ex, HttpServletRequest r) {
    return response(
        422, "INVALID_REQUEST", "Check the request fields and identifiers.", false, List.of(), r);
  }

  @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
  ResponseEntity<Api.Problem> missing(Exception ex, HttpServletRequest r) {
    return response(
        404, "NOT_FOUND", "This API route or resource is unavailable.", false, List.of(), r);
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<Api.Problem> constraint(Exception ex, HttpServletRequest r) {
    return response(
        409,
        "RECORD_CONFLICT",
        "A duplicate, conflicting booking or invalid relationship was rejected.",
        false,
        List.of(),
        r);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Api.Problem> unexpected(Exception ex, HttpServletRequest r) {
    return response(
        500,
        "INTERNAL_ERROR",
        "The action could not be completed. Contact support with the request reference.",
        false,
        List.of(),
        r);
  }

  @ExceptionHandler(TransientDataAccessException.class)
  ResponseEntity<Api.Problem> retry(TransientDataAccessException ex, HttpServletRequest r) {
    return response(
        503,
        "TEMPORARY_DATABASE_CONFLICT",
        "Keep the same command key and retry after a short wait.",
        true,
        List.of(),
        r);
  }

  private ResponseEntity<Api.Problem> response(
      int status,
      String code,
      String detail,
      boolean retryable,
      List<Api.FieldError> errors,
      HttpServletRequest r) {
    return ResponseEntity.status(status)
        .contentType(MediaType.valueOf("application/problem+json"))
        .body(
            new Api.Problem(
                Api.title(status),
                status,
                detail,
                code,
                Objects.toString(r.getAttribute("requestId"), UUID.randomUUID().toString()),
                retryable,
                errors));
  }
}
