package com.educore.academics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
    exclude =
        org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
            .class,
    scanBasePackages = {"com.educore.academics", "com.educore.platform"})
@EnableScheduling
public class AcademicsApplication {
  public static void main(String[] args) {
    SpringApplication.run(AcademicsApplication.class, args);
  }
}
