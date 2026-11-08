# ADR-0009: Database migrations as Fly.io's release command

- **Status:** Accepted
- **Date:** 2026-11-08
- **Supersedes:** how [ADR-0008](0008-deployment.md) applied migrations (its 2026-11-07 revision)

## Context
The deploy pipeline applied the Flyway migrations with the `flyway/flyway` container before
`flyctl deploy` (ADR-0008), and the app ran Flyway again at startup. Both read and write
`flyway_schema_history`, so the container had to run the Flyway version that Spring Boot manages
in the app (12.4.0 in Boot 4.1.2). Renovate updates the two separately and already proposed
Flyway 12.11 and 13 for the container: keeping them equal was a manual chore that nothing checked.
The pipeline also kept its own copy of the database credentials in GitHub.

## Decision
- **The image migrates the database itself.** `backend/fly.toml` sets
  `[deploy] release_command = "--spring.profiles.active=prod,migrate"`. On every deploy, before
  any machine is created or updated, Fly.io starts a temporary machine from the new image, with
  the app's secrets and `[env]`, and passes these arguments to the image's
  `java -jar moneytrail.jar`. The `migrate` profile starts the application without a web server,
  Flyway migrates while it starts, and `MoneyTrailApplication` exits. A non-zero exit stops the
  deploy, and the running machines keep serving the old version.
- **In production only the release command changes the schema.** The `api` and `worker`
  processes validate instead of migrating (`ProductionFlywayConfiguration`): they refuse to start
  if the database lacks a migration their image contains. Locally and in tests, Flyway still
  migrates at startup.
- `ReleaseCommandTests` runs the production jar with the arguments from `fly.toml`, in that order.

Considered and rejected:
- **Keep the container, bumped by hand together with Spring Boot:** still two copies of one
  version, and the pipeline still holds database credentials.
- **Migrate only at startup:** with one machine, a failed migration leaves it crashing after the
  old version has already stopped, instead of stopping the deploy; and every machine start tries
  to migrate.

## Consequences
- One Flyway version, Spring Boot's, which Renovate updates with Spring Boot. The `DATABASE_*`
  GitHub secrets are gone: the database credentials live only in Fly.io.
- Each deploy runs one more machine for a few seconds. It gets the size of the largest `api`
  machine (512 MB, which the JVM settings in `[env]` assume) and 5 minutes to finish
  (`release_command_timeout`).
- The deploy log shows whether the release command succeeded. Flyway's own lines are in
  `fly logs` and Grafana; flyctl copies them into the deploy log only when the command fails.
- A machine started with a new image outside `fly deploy` (or with `--skip-release-command`)
  finds the schema behind and refuses to start, instead of serving errors.
- Migrations must stay backward compatible (expand, then contract): the old version keeps serving
  between the release command and the machine update, and a rollback runs an older image against
  the newer schema. Flyway ignores migrations newer than the image, so that image still starts.
- The `migrate` profile must stay free of background work (scheduled jobs, event republication):
  the process exits as soon as it has started.
