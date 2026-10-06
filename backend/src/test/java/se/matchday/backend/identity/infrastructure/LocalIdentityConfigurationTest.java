package se.matchday.backend.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import se.matchday.backend.identity.application.CurrentUser;
import se.matchday.backend.identity.application.UserDirectory;

class LocalIdentityConfigurationTest {

  private static final UUID USER_ID = UUID.fromString("f0e4e8a2-1767-4d50-9b08-a927ef15b8d1");

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withPropertyValues(
              "spring.profiles.active=local",
              "matchday.identity.local.user-id=" + USER_ID,
              "matchday.identity.local.username=sara")
          .withUserConfiguration(
              CurrentUserConfiguration.class,
              UserDirectoryConfiguration.class,
              LocalIdentityConfiguration.class);

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void resolvesTheConfiguredIdentityForAnAuthenticatedLocalRequest() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                "local-operator",
                "ignored",
                AuthorityUtils.createAuthorityList("ROLE_MATCH_IMPORTER")));

    contextRunner.run(
        context -> {
          CurrentUser currentUser = context.getBean(CurrentUser.class);
          UserDirectory userDirectory = context.getBean(UserDirectory.class);

          assertThat(currentUser.userId()).contains(USER_ID);
          assertThat(userDirectory.findUsernamesByUserIds(Set.of(USER_ID)))
              .isEqualTo(Map.of(USER_ID, "sara"));
        });
  }

  @Test
  void doesNotResolveAnIdentityWithoutAuthentication() {
    contextRunner.run(context -> assertThat(context.getBean(CurrentUser.class).userId()).isEmpty());
  }

  @Test
  void doesNotResolveAnIdentityForUnauthenticatedAuthentication() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.unauthenticated("local-operator", "ignored"));

    contextRunner.run(context -> assertThat(context.getBean(CurrentUser.class).userId()).isEmpty());
  }

  @Test
  void doesNotResolveAnIdentityForAnonymousAuthentication() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new AnonymousAuthenticationToken(
                "test-key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

    contextRunner.run(context -> assertThat(context.getBean(CurrentUser.class).userId()).isEmpty());
  }

  @Test
  void returnsOnlyTheConfiguredPublicIdentity() {
    UUID unknownUserId = UUID.fromString("9bb42d23-202e-4fa6-9e2d-6f85ac54ea36");

    contextRunner.run(
        context -> {
          UserDirectory userDirectory = context.getBean(UserDirectory.class);

          assertThat(userDirectory.findUsernamesByUserIds(Set.of(USER_ID, unknownUserId)))
              .isEqualTo(Map.of(USER_ID, "sara"));
          assertThat(userDirectory.findUsernamesByUserIds(Set.of(unknownUserId))).isEmpty();
        });
  }
}
