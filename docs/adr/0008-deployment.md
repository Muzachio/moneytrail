# ADR-0008: Deploy on Fly.io, Neon and Cloudflare Pages

- **Status:** Accepted
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
