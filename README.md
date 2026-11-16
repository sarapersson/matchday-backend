# Matchday Backend

REST API for a social app built around matches in Allsvenskan, the Swedish top football league.
Matches are imported from TheSportsDB, and each match can have a discussion group (a *circle*)
that users join, leave and post messages in.

> Project assignment for part 2 of the DevSecOps with Security Specialization course (Swedish
> higher vocational education, YH) at NBI/Handelsakademin. It was built as a team project, and
> this repository contains the parts I built: match import, circles, memberships and messages.
> Authentication is represented by a fail-closed identity contract and a local demo adapter, so
> the API can be run and tested on its own.

## Highlights

- **Feature slices end to end.** Each feature is built through every layer (API, application
  logic, domain and persistence) together with tests and OpenAPI documentation. Each feature was
  developed in small commits and merged as one pull request; the individual commits are visible
  in the
  [merged pull requests](https://github.com/sarapersson/matchday-backend/pulls?q=is%3Apr+is%3Amerged).
- **Fail-closed authorization.** Business logic depends on a `CurrentUser` contract. Without a
  real identity adapter every protected request is rejected; a local demo adapter is available
  only under the `local` profile, which binds the server to `127.0.0.1`.
- **Safe under concurrent requests.** Database constraints, transactions and row locking ensure
  that a match never gets two circles, and that once a user has left a circle, a concurrent
  request from that user can no longer post or read messages. Concurrency tests run competing
  operations and then check what was actually stored.
- **No data leakage.** API responses identify users only by their public username, never by
  internal user ID or email address, and never include provider IDs or configuration. Application
  errors are returned as RFC 9457 Problem Details without stack traces; requests rejected by the
  security filter chain get a plain `401` or `403`.
- **Shift-left pipeline.** GitHub Actions runs the test suite, formatting checks, a 70% line
  coverage gate, OpenAPI linting and OWASP Dependency-Check (fails on CVSS ≥ 7). Actions are
  pinned to commit SHAs and run with read-only permissions.

## Tech stack

| Area | Technology |
| --- | --- |
| Language and framework | Java 25, Spring Boot 4.1, Spring Security, Maven Wrapper |
| Database | PostgreSQL 18, Flyway, Spring Data JPA |
| Testing | JUnit 6, AssertJ, MockMvc, Testcontainers, JaCoCo |
| Quality and security | Spotless (Google Java Format), Redocly CLI, OWASP Dependency-Check |
| CI | GitHub Actions |

## Architecture

The code is organized by feature (`match`, `circle`, `message`), supported by the `identity` and
`security` packages. The feature packages share the same layers:

```text
HTTP request
  -> api             transport validation, DTO mapping, Problem Details
  -> application     use cases, identity and authorization rules
  -> domain          business rules and value invariants
  -> infrastructure  JPA/PostgreSQL adapters, TheSportsDB client
```

The API layer never exposes JPA entities or domain objects. The application layer depends only on
interfaces such as `CircleRepository`, `MessageRepository`, `MatchDataProvider`, `CurrentUser` and
`UserDirectory`, which the infrastructure layer implements. This keeps TheSportsDB's data model out
of the domain and lets the authentication solution be replaced without touching business logic.

Flyway owns the schema. Where an authorization check belongs to a read or write, both happen in one
transaction with row locking or a single atomic query, so the result cannot change between the
check and the operation.

## API

| Method and path | Access | Description |
| --- | --- | --- |
| `GET /api/v1/matches` | Public | List stored matches |
| `GET /api/v1/matches/{matchId}/circle` | Authenticated | Get the match's circle and the caller's membership status |
| `POST /api/v1/matches/{matchId}/circle` | Authenticated | Create the circle; the creator becomes a member in the same transaction |
| `PUT /api/v1/matches/{matchId}/circle/membership` | Authenticated | Join the circle (idempotent) |
| `DELETE /api/v1/matches/{matchId}/circle/membership` | Authenticated | Leave the circle (idempotent) |
| `GET /api/v1/circles/{circleId}/messages?page=0&size=50` | Active member | Paginated message history, newest first |
| `POST /api/v1/circles/{circleId}/messages` | Active member | Post a message |
| `POST /api/v1/admin/match-imports` | Role `MATCH_IMPORTER` | Import or refresh a season |

Matches are sorted by season, round, date, kickoff time and internal ID; filtering into upcoming
and played matches is left to the client. The full contract is in
[`docs/openapi.yaml`](docs/openapi.yaml) (OpenAPI 3.1).

### Rules worth knowing

- A match has zero or one circle. Creating one that already exists returns `409 Conflict`; when
  create requests compete, exactly one gets `201 Created`.
- Joining and leaving are idempotent and return `204 No Content`. Leaving keeps the circle, other
  memberships and earlier messages.
- Only `content` is read from a new message; author and timestamp fields sent by the client are
  ignored, and the server sets them. Content is trimmed and must be 1–1000 Unicode characters
  with no null characters.
- Message history is returned newest first in a stable order, with page sizes from 1 to 100
  (default `page=0&size=50`).
- A user who has left a circle gets `403 Forbidden` on both reading and posting messages.
- Match imports tolerate empty or incomplete rounds and never delete stored data, so an import can
  be re-run to fill in or update matches as the schedule is published. If the provider returns a
  match for the wrong season or round, a duplicate external ID or more than eight matches in a
  round, the whole import is rejected before anything is stored.
- TheSportsDB rate limits (`429` with `Retry-After`) are respected. Provider failures return
  sanitized Problem Details: `502 Bad Gateway` for invalid provider data and
  `503 Service Unavailable` when a provider request fails or the rate limit does not clear in time.

## Behavior examples

The scenarios below drove development and are verified by descriptively named JUnit tests at
domain, application, persistence and API levels.

```gherkin
Given a match exists and the user's application identity can be resolved
When the user creates the match's circle
Then at most one circle exists for the match
And the creator has an active membership created in the same transaction
```

```gherkin
Given the user has left the circle
When the user tries to post or read messages
Then access is denied with 403 Forbidden
And no message is stored or exposed
```

Further cases cover empty, oversized and disallowed content, unknown or malformed IDs, missing
identity (`401`), invalid pagination, missing request bodies, malformed JSON, attempts to set the
author from the client (ignored), and a user leaving while messages are being posted or read.

## Running locally

Prerequisites: JDK 25 and Docker with Docker Compose. Maven is provided by the wrapper.

```bash
git clone https://github.com/sarapersson/matchday-backend.git
cd matchday-backend/backend
./mvnw spring-boot:run
```

The examples use bash syntax. On Windows PowerShell, use `.\mvnw.cmd` instead of `./mvnw` and set
environment variables with `$env:NAME = "value"`.

Spring Boot starts PostgreSQL from `compose.yaml` automatically. Database settings can be
overridden with `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` and `POSTGRES_PORT`.

Keep the backend running and check the public match list from another terminal:

```bash
curl --fail-with-body http://127.0.0.1:8080/api/v1/matches   # [] until matches are imported
```

### Local demo profile

The default configuration rejects every protected request because no production identity
adapter is included. To try the protected endpoints, stop the running backend (Ctrl+C) and start
it again from `backend/` with the `local` profile, which enables Basic Auth mapped to a configured
demo identity and binds the server to `127.0.0.1`:

```bash
SPRING_PROFILES_ACTIVE=local \
MATCHDAY_LOCAL_USERNAME=local-operator \
MATCHDAY_LOCAL_PASSWORD=choose-a-local-password \
MATCHDAY_LOCAL_USER_ID=f0e4e8a2-1767-4d50-9b08-a927ef15b8d1 \
MATCHDAY_LOCAL_PUBLIC_USERNAME=sara \
./mvnw spring-boot:run
```

Import a season from another terminal. `curl` prompts for the password on each call, and the
import can take more than a minute because 30 rounds are fetched sequentially, about two seconds
apart. TheSportsDB is called with its public test key `123`; set `THESPORTSDB_API_KEY` to use
another.

```bash
curl --fail-with-body --user local-operator \
  --header 'Content-Type: application/json' \
  --data '{"season": 2026}' \
  http://127.0.0.1:8080/api/v1/admin/match-imports
```

Then create a circle and post a message (the commands below use `jq`):

```bash
MATCH_ID=$(curl -s http://127.0.0.1:8080/api/v1/matches | jq -r '.[0].id')

curl --fail-with-body --user local-operator --request POST \
  http://127.0.0.1:8080/api/v1/matches/$MATCH_ID/circle

CIRCLE_ID=$(curl -s --user local-operator \
  http://127.0.0.1:8080/api/v1/matches/$MATCH_ID/circle | jq -r '.id')

curl --fail-with-body --user local-operator \
  --header 'Content-Type: application/json' \
  --data '{"content":"Hello from the demo"}' \
  http://127.0.0.1:8080/api/v1/circles/$CIRCLE_ID/messages
```

A Postman collection that runs the circle and message flow, including leaving, denied access and
rejoining, is in [`docs/postman/`](docs/postman/). Import a season first. Select the
`Matchday Local Demo` environment and set the **Current value** of `password` to the value of
`MATCHDAY_LOCAL_PASSWORD`; the other variables assume the usernames from the example above
(`local-operator` and `sara`).

The demo profile is for local use only and is not a substitute for real authentication.

## Verification

From `backend/`:

```bash
./mvnw --batch-mode --no-transfer-progress verify
```

This runs unit, integration (Testcontainers), API and concurrency tests, checks formatting,
enforces at least 70% line coverage and packages the application. To lint the API contract, run
this from the repository root (requires Node.js):

```bash
npx --yes @redocly/cli@2.45.0 lint docs/openapi.yaml
```

See [`docs/BACKEND_CI.md`](docs/BACKEND_CI.md) for how the CI and security workflows are set up.

## Documentation

- [API contract](docs/openapi.yaml) (OpenAPI 3.1)
- [CI and security checks](docs/BACKEND_CI.md)
- [Postman demo collection](docs/postman/)

## Known limitations

- No production authentication adapter is included; the default configuration is fail-closed.
- Rate limiting for the application's own endpoints is not implemented.
- Full user deletion (right to erasure) is not implemented.
- Deployment and monitoring were out of scope for the course assignment.

## Use of AI

AI assistance was used for exploring alternatives, test ideas, code review, refactoring and
documentation drafts. Suggestions were treated as proposals, not proof: changes were read,
checked against the architecture and verified through automated tests, quality gates and manual
demos.
