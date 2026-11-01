package com.educore.communication;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
    exclude =
        org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
            .class,
    scanBasePackages = {"com.educore.communication", "com.educore.platform"})
@EnableScheduling
public class CommunicationApplication {
  public static void main(String[] args) {
    SpringApplication.run(CommunicationApplication.class, args);
  }
}
