package com.educore.identity;

import com.educore.platform.AccessVerifier;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.password.*;

@Configuration
public class IdentityConfiguration {
  @Bean
  PasswordEncoder passwords() {
    return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
  }

  @Bean
  @Primary
  AccessVerifier identityVerifier(IdentityService service) {
    return service::verify;
  }
}
