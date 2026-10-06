package se.matchday.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import se.matchday.backend.TestcontainersConfiguration;
import se.matchday.backend.match.application.MatchRepository;
import se.matchday.backend.match.application.ProviderMatch;
import se.matchday.backend.match.application.SeasonMatchImportResult;
import se.matchday.backend.match.application.SeasonMatchImporter;
import se.matchday.backend.match.domain.MatchStatus;

@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(
    properties = {
      "matchday.security.local.username=local-operator",
      "matchday.security.local.password=test-password",
      "matchday.identity.local.user-id=40000000-0000-0000-0000-000000000001",
      "matchday.identity.local.username=sara"
    })
class LocalSecurityConfigurationIntegrationTest {

  private static final UUID USER_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");

  private final MockMvc mockMvc;
  private final MatchRepository matchRepository;
  private final JdbcTemplate jdbcTemplate;

  @MockitoBean(enforceOverride = true)
  private SeasonMatchImporter seasonMatchImporter;

  @Autowired
  LocalSecurityConfigurationIntegrationTest(
      MockMvc mockMvc, MatchRepository matchRepository, JdbcTemplate jdbcTemplate) {
    this.mockMvc = mockMvc;
    this.matchRepository = matchRepository;
    this.jdbcTemplate = jdbcTemplate;
  }

  @BeforeEach
  void prepareTest() {
    clearDatabase();
  }

  @AfterEach
  void cleanUpTest() {
    clearDatabase();
  }

  private void clearDatabase() {
    jdbcTemplate.update("TRUNCATE TABLE messages, circle_memberships, circles, matches");
  }

  @Test
  void rejectsMissingCredentials() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/match-imports")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"season": 2026}
                    """))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(seasonMatchImporter);
  }

  @Test
  void rejectsInvalidCredentials() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/match-imports")
                .with(httpBasic("local-operator", "wrong-password"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"season": 2026}
                    """))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(seasonMatchImporter);
  }

  @Test
  void importsWithValidLocalCredentialsWithoutACsrfToken() throws Exception {
    when(seasonMatchImporter.importSeason(2026)).thenReturn(new SeasonMatchImportResult(2026, 240));

    mockMvc
        .perform(
            post("/api/v1/admin/match-imports")
                .with(httpBasic("local-operator", "test-password"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"season": 2026}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.season").value(2026))
        .andExpect(jsonPath("$.processedMatches").value(240));

    verify(seasonMatchImporter).importSeason(2026);
  }

  @Test
  void createsACircleAndMessageWithTheConfiguredLocalIdentity() throws Exception {
    UUID matchId = storeMatch();

    mockMvc
        .perform(
            post("/api/v1/matches/{matchId}/circle", matchId)
                .with(httpBasic("local-operator", "test-password")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.membershipActive").value(true));

    UUID circleId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM circles WHERE match_id = ?", UUID.class, matchId);

    mockMvc
        .perform(
            post("/api/v1/circles/{circleId}/messages", circleId)
                .with(httpBasic("local-operator", "test-password"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"content":"Hello from the local demo"}
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.circleId").value(circleId.toString()))
        .andExpect(jsonPath("$.content").value("Hello from the local demo"))
        .andExpect(jsonPath("$.authorUsername").value("sara"))
        .andExpect(jsonPath("$.authorUserId").doesNotExist());

    Integer membershipCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM circle_memberships WHERE user_id = ?", Integer.class, USER_ID);
    Integer messageCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM messages WHERE author_user_id = ?", Integer.class, USER_ID);

    assertThat(membershipCount).isOne();
    assertThat(messageCount).isOne();
  }

  private UUID storeMatch() {
    matchRepository.saveAll(
        List.of(
            new ProviderMatch(
                "local-demo-match",
                2026,
                1,
                "home-team",
                "Home",
                "away-team",
                "Away",
                LocalDate.of(2026, 4, 4),
                Instant.parse("2026-04-04T13:00:00Z"),
                null,
                null,
                MatchStatus.SCHEDULED,
                null)));
    return matchRepository.findAll().getFirst().id();
  }
}
