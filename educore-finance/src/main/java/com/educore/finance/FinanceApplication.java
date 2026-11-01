package com.educore.finance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
    exclude =
        org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
            .class,
    scanBasePackages = {"com.educore.finance", "com.educore.platform"})
@EnableScheduling
public class FinanceApplication {
  public static void main(String[] args) {
    SpringApplication.run(FinanceApplication.class, args);
  }
}
