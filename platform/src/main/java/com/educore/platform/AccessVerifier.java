package com.educore.platform;

@FunctionalInterface
public interface AccessVerifier {
  Actor verify(String accessToken);
}
