# ADR-0008: Deploy on Fly.io, Neon and Cloudflare Pages

- **Status:** Accepted. How migrations are applied is superseded by
  [ADR-0009](0009-migrations-as-release-command.md)
- **Date:** 2026-10-14

## Context
The app must be reachable by friends over HTTPS, cost under ~€10/month, and deploy
automatically from `main`. Running and backing up my own database is not where the
learning value is at this stage.

## Decision
| Piece | Service |
|---|---|
| Backend (`api` and `worker` process groups, one image) | **Fly.io**, region `mad` or `cdg`, 1 GB shared-CPU machine |
| PostgreSQL | **Neon**, EU region, free tier, point-in-time restore |
| Frontend | **Cloudflare Pages** |
| DNS and domain | Cloudflare (`app.<domain>`, `api.<domain>`) |
| Images | GitHub Container Registry |
| Secrets | Fly secrets and GitHub Actions secrets. Nothing in the repository |
| Email | Resend free tier |
| Telemetry | Grafana Cloud free tier |

The GitHub Actions pipeline on `main` runs build and tests, builds the image, scans it,
applies Flyway migrations, deploys with `flyctl deploy`, then runs a smoke test.

## Consequences
- Roughly €3–8/month plus the domain.
- Some vendor coupling. Everything is a plain container and plain Postgres, so moving
  later is easy.
- **Planned follow-up:** a separate ADR may move to a Hetzner VPS with Terraform, k3s,
  Helm and Argo CD (GitOps) once the app is stable.

## Revision 2026-11-07 (first deploy)
Details settled while setting it up (#10); the decision above stands. Setup: `docs/deploy.md`.

- **Hosting re-checked:** compared with a Hetzner VPS (+ Coolify) and DigitalOcean after their
  2026 price rises. A VPS was no longer clearly cheaper and would make backups and OS updates our
  job; DigitalOcean costs more per GB of memory. Review again around January 2027 with real usage.
- **Region `fra`, not `mad`/`cdg`:** the Neon database is in AWS eu-central-1 (Frankfurt), and
  every request runs several queries; app and database sit in the same place.
- **512 MB to start, not 1 GB:** ~$4.26/month in `fra`, under the $5 below which Fly.io waives the
  invoice. The JVM is tuned for it in `backend/fly.toml`; move to 1 GB if metrics show pressure.
- **`worker` defined, 0 machines:** there is no background work before M1, and a second machine
  would double the cost.
- **Migrations** run in the pipeline with the Flyway container (same version as the app) before
  `flyctl deploy`, so a failing migration stops the deploy while the old version keeps serving.
  Superseded by [ADR-0009](0009-migrations-as-release-command.md): Fly.io's release command.
- **Frontend** is built in the pipeline and uploaded with `wrangler pages deploy`, rather than
  through Cloudflare Pages' Git integration: the same commit as the API, and no extra GitHub app.
- **Domain** `muzachio.dev` (Cloudflare Registrar): app `moneytrail.muzachio.dev`, API
  `api.moneytrail.muzachio.dev`.
- **Cost:** ~$4.26/month for Fly.io (likely waived) plus $12.20/year for the domain.
