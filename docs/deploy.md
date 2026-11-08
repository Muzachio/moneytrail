# Deployment

How `main` reaches production (ADR-0008), and the one-time setup behind it. Secret values are
typed straight into Fly.io, GitHub or Cloudflare; they never go into this repository or a chat.

## Production

| Piece | Where | Address |
|---|---|---|
| App (frontend) | Cloudflare Pages project `moneytrail` (`moneytrail-63c.pages.dev`; `moneytrail.pages.dev` belongs to someone else) | https://moneytrail.muzachio.dev |
| API (backend) | Fly.io app `moneytrail-api`, region `fra`, one `shared-cpu-1x` machine with 512 MB | https://api.moneytrail.muzachio.dev |
| Database | Neon project `moneytrail`, AWS Europe Central 1 (Frankfurt), Postgres 18, branch `production` | not public |
| Images | `ghcr.io/muzachio/moneytrail-api`, public | |
| DNS and domain | Cloudflare, zone `muzachio.dev` | |

## What a deploy does

Every push to `main` runs CI. When CI passes, `.github/workflows/deploy.yml`:

1. builds the backend image and pushes `ghcr.io/muzachio/moneytrail-api:<commit>`;
2. scans that image with Trivy (fails on fixable HIGH/CRITICAL);
3. applies Flyway migrations to Neon; a failing migration stops the deploy while the running
   version keeps serving;
4. deploys the image to Fly.io (the first deploy creates the machines and scales the `worker`
   group to 0; later deploys only update the existing machines);
5. checks `GET /v1/health` and that `GET /v1/me` answers 401;
6. builds the frontend with `VITE_API_BASE_URL=https://api.moneytrail.muzachio.dev` and uploads it to
   Cloudflare Pages (creating the project on the first run);
7. checks that the app's page loads.

A failed run can be re-run from the Actions tab once the cause is fixed.

## One-time setup

Done once, in this order, before the first deploy.

### 1. Neon

- Project `moneytrail`, region AWS Europe Central 1 (Frankfurt), Postgres 18.
- In **Connect**, choose the **direct** connection (not the pooled one; Flyway needs a direct one).
  Neon shows `postgresql://<user>:<password>@<host>/<database>?sslmode=require&channel_binding=require`.
  Java and Flyway need the JDBC form, **without** user and password (they are separate secrets):
  `jdbc:postgresql://<host>/<database>?sslmode=require`. Neon's own form fails with "No Flyway
  database plugin found".

### 2. Fly.io

With [`flyctl`](https://fly.io/docs/flyctl/install/), after `fly auth login`:

```
fly apps create moneytrail-api --org personal
fly tokens create deploy --app moneytrail-api --expiry 8760h   # → GitHub secret FLY_API_TOKEN
fly certs add api.moneytrail.muzachio.dev --app moneytrail-api
```

Secrets, in the dashboard (**moneytrail-api → Secrets**), so the values never reach a shell history:

| Secret | Value |
|---|---|
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | the Google OAuth client |
| `SPRING_DATASOURCE_URL` | the Neon JDBC URL above |
| `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | the Neon user and password |

`MONEYTRAIL_APP_URL` and `MONEYTRAIL_COOKIE_DOMAIN` are not secret; they are in `backend/fly.toml`.
The deploy token expires after a year: renew it and update the GitHub secret.

### 3. Cloudflare

- **DNS** (zone `muzachio.dev`), the records `fly certs setup api.moneytrail.muzachio.dev` prints,
  all with proxy status **DNS only** (Fly.io terminates HTTPS and issues the certificate itself):

  | Type | Name | Target |
  |---|---|---|
  | `CNAME` | `api.moneytrail` | `oq21go5.moneytrail-api.fly.dev` |
  | `CNAME` | `_acme-challenge.api.moneytrail` | `api.moneytrail.muzachio.dev.oq21go5.flydns.net` |
  | `TXT` | `_fly-ownership.api.moneytrail` | `app-oq21go5` |

  The ACME record lets Fly.io issue the certificate before the first deploy; the TXT record
  proves ownership while the app has no IPv6 address yet.
- **API token** (My Profile → API Tokens → Create Token → Custom): permission
  *Account · Cloudflare Pages · Edit*, for your account only. → GitHub secret `CLOUDFLARE_API_TOKEN`.
- **Account ID** (shown on the account's overview page) → GitHub secret `CLOUDFLARE_ACCOUNT_ID`.
- After the first deploy created the Pages project: **Workers & Pages → moneytrail → Custom
  domains → Set up a custom domain** → `moneytrail.muzachio.dev`. Cloudflare adds the DNS record.

### 4. Google

On the OAuth client (Google Auth Platform):

- **Clients → MoneyTrail web → Authorized redirect URIs**: add
  `https://api.moneytrail.muzachio.dev/login/oauth2/code/google`; keep the local one.
- **Branding → Authorized domains**: add `muzachio.dev`.

### 5. GitHub

- **Settings → Environments → New environment** `production`, deployment branches limited to
  `main`.
- Its secrets: `FLY_API_TOKEN`, `DATABASE_URL` (the Neon **JDBC** URL), `DATABASE_USERNAME`,
  `DATABASE_PASSWORD`, `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`. Paste the values in the
  environment's page (pencil → *Value* → *Update secret*), or pass them with `--body` /
  standard input. Not through the hidden prompt of a bare `gh secret set` on Windows: pasting
  there stored empty values, which only showed up as an empty `FLYWAY_URL` in the deploy log.
  The Fly.io token never needs to be seen:
  `fly tokens create deploy --app moneytrail-api --expiry 8760h | gh secret set FLY_API_TOKEN --env production`.

### 6. First deploy

1. Merge to `main`. The image package inherits the public visibility of the repository, so Fly.io
   can pull it without credentials (an anonymous pull of the manifest answers 200).
2. The `app` job publishes the site and creates the Pages project, but its smoke test fails until
   the custom domain exists: add it (step 3), wait for **Active**, and re-run the failed job.
3. After that, every green CI run on `main` deploys on its own.

Testing right after adding a DNS name: a resolver that looked the name up before it existed keeps
the "does not exist" answer for up to 30 minutes (the zone's negative TTL), so a home router may
show `DNS_PROBE_FINISHED_NXDOMAIN` while the rest of the internet already sees the site. Try
another network (a phone on mobile data) or wait.
