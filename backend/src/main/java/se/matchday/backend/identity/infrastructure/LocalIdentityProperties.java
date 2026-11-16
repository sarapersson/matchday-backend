package se.matchday.backend.identity.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("matchday.identity.local")
record LocalIdentityProperties(@NotNull UUID userId, @NotBlank String username) {}
