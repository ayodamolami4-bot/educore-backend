package com.educore.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
    exclude =
        org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
            .class,
    scanBasePackages = {"com.educore.identity", "com.educore.platform"})
@EnableScheduling
public class IdentityApplication {
  public static void main(String[] args) {
    SpringApplication.run(IdentityApplication.class, args);
  }
}
