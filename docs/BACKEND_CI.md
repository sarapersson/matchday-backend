# Backend CI and security checks

This document describes the backend's automated quality and security checks. It is written for
developers working in the codebase and for reviewers who need to understand how changes are
verified before they are integrated into `main`.

## Overview

The backend uses two separate GitHub Actions workflows:

| Workflow | Purpose | Triggers |
| --- | --- | --- |
| `Backend CI` | Build, tests, formatting, coverage and the OpenAPI contract | Pull request to `main`, push to `main`, manual run |
| `Backend Security` | Vulnerability analysis of Maven dependencies | Pull request to `main`, push to `main`, Mondays 06:00 UTC, manual run |

The vulnerability analysis runs in its own workflow because it needs access to the NVD and an API
key.

## Backend CI

Configuration: [`.github/workflows/backend.yml`](../.github/workflows/backend.yml)

The workflow has two independent jobs, so a broken API contract can be told apart from a
compilation or test failure.

### Backend verification

The job uses Temurin JDK 25 and runs the following command from `backend/`:

```bash
./mvnw --batch-mode --no-transfer-progress verify
```

Verification covers:

- compilation and packaging
- unit tests and Testcontainers-based integration tests against PostgreSQL
- formatting with Spotless and Google Java Format
- a JaCoCo report and a quality gate of at least 70% line coverage

The following reports are uploaded as GitHub Actions artifacts, even when the job fails:

- `backend/target/surefire-reports/`
- `backend/target/site/jacoco/`

Reports are kept for seven days.

### OpenAPI lint

The job lints [`docs/openapi.yaml`](openapi.yaml) with Redocly CLI, checking the OpenAPI
structure, schema references and Redocly's recommended rule set. Redocly is used only in the
development and CI flow and does not affect the Spring Boot application's runtime dependencies.

The workflow uses Node.js 24 and an exact Redocly CLI version to limit variation between runs.
The local equivalent is run from the repository root:

```bash
REDOCLY_TELEMETRY=off \
REDOCLY_SUPPRESS_UPDATE_NOTICE=true \
npx --yes @redocly/cli@2.45.0 lint docs/openapi.yaml
```

## Backend Security

Configuration: [`.github/workflows/backend-security.yml`](../.github/workflows/backend-security.yml)

The `Backend dependency audit` job uses OWASP Dependency-Check to analyze Maven dependencies
against the National Vulnerability Database. The job fails when a dependency scores CVSS 7 or
higher. The weekly scheduled run means new vulnerabilities are detected even when the dependency
files have not changed.

The job invokes the Dependency-Check goals directly and does not run the test suite, which is
already covered by `Backend verification`. An HTML report is uploaded as a GitHub Actions
artifact and kept for seven days:

```text
backend/target/dependency-check-report.html
```

### NVD API key

The workflow reads the GitHub Actions repository secret `NVD_API_KEY`. OWASP Dependency-Check
uses the key for calls to the National Vulnerability Database; it is never
stored in version control. If the key is missing, the job fails at the `Verify NVD API key` step.

A free key can be requested from the
[National Vulnerability Database](https://nvd.nist.gov/developers/request-an-api-key). Add it as a
repository secret under **Settings > Secrets and variables > Actions** in GitHub, named exactly
`NVD_API_KEY`.

The NVD data cache is keyed by ISO week. If no compatible cache exists, the database is seeded
from the Dependency-Check project's data feed before being updated against the NVD API with the
repository's `NVD_API_KEY`. The update and the dependency analysis run as separate steps, so a
fully updated database can be cached even if the analysis then finds a vulnerability and fails the
build. The cache version follows Dependency-Check's major version and must be changed on an
incompatible upgrade.

### Execution context

The workflow is designed for branches in the same GitHub repository. GitHub does not pass
repository secrets to runs from forks or Dependabot, so such runs fail at the
`Verify NVD API key` step. The workflow does not use `pull_request_target`.

## Least privilege and supply chain

Both workflows have only `contents: read`. The checkout step uses `persist-credentials: false`,
which prevents the GitHub token from being stored in the local Git configuration after checkout.

Every GitHub Action is pinned to a full commit SHA. The release version is noted in a comment next
to each SHA so the version choice can be reviewed.

The Maven Wrapper pins the Maven version and verifies the downloaded distribution with SHA-256.
The version and checksum are in `backend/.mvn/wrapper/maven-wrapper.properties`.

## Status checks

The workflows publish the following status checks:

- `Backend verification`
- `OpenAPI lint`
- `Backend dependency audit`

Together they report backend verification, the API contract and dependency risk separately on
every pull request to `main`.
