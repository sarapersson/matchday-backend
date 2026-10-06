package se.matchday.backend.identity.infrastructure;

import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import se.matchday.backend.identity.application.CurrentUser;
import se.matchday.backend.identity.application.UserDirectory;

/**
 * Maps the single local Basic Auth account to a configured application identity for demos. Other
 * profiles must provide their own identity adapters.
 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@EnableConfigurationProperties(LocalIdentityProperties.class)
class LocalIdentityConfiguration {

  @Bean
  CurrentUser localCurrentUser(LocalIdentityProperties properties) {
    return () -> {
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      if (authentication == null
          || !authentication.isAuthenticated()
          || authentication instanceof AnonymousAuthenticationToken) {
        return Optional.empty();
      }
      return Optional.of(properties.userId());
    };
  }

  @Bean
  UserDirectory localUserDirectory(LocalIdentityProperties properties) {
    return userIds ->
        userIds.contains(properties.userId())
            ? Map.of(properties.userId(), properties.username())
            : Map.of();
  }
}
