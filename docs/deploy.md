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
| Telemetry | Grafana Cloud stack in EU Germany (`eu-west-4`): traces, metrics and logs over OTLP | https://orangewalnut306.grafana.net |

## What a deploy does

Every push to `main` runs CI. When CI passes, `.github/workflows/deploy.yml`:

1. builds the backend image and pushes `ghcr.io/muzachio/moneytrail-api:<commit>`;
2. scans that image with Trivy (fails on fixable HIGH/CRITICAL);
3. deploys the image to Fly.io. Fly.io first runs the `release_command` of `backend/fly.toml`
   (ADR-0009): a temporary machine starts the new image with the `migrate` profile, applies the
   Flyway migrations to Neon and exits. A failing migration stops the deploy before any machine
   changes, and the running version keeps serving. Then the first deploy creates the machines and
   scales the `worker` group to 0; later deploys only update the existing machines. The `api`
   process checks at startup that every migration was applied;
4. checks `GET /v1/health` and that `GET /v1/me` answers 401;
5. builds the frontend with `VITE_API_BASE_URL=https://api.moneytrail.muzachio.dev` and uploads it to
   Cloudflare Pages (creating the project on the first run);
6. checks that the app's page loads.

A failed run can be re-run from the Actions tab once the cause is fixed. When the release command
succeeds, the deploy log only says so; when it fails, flyctl tries to add its last 100 log lines.
Either way, Flyway's own lines (`Successfully applied …`, or `Schema … is up to date`) are in
`fly logs --app moneytrail-api` and in Grafana.

## Who can sign in

Production is invite-only: only a Google account whose email is in the Fly secret
`MONEYTRAIL_INVITED_EMAILS` gets in. Anyone else goes back to the sign-in page, which says in
English or Portuguese that the app is open to invited people only. They get no user, no ledger
and no session, and the address they used appears in no URL and no log line.

- **Why a list in the app, not Google's "Testing" mode.** Testing mode would also keep strangers
  out, but Google shows its own error page and the rule would live only in the Google console.
  With the list, the app shows its own message in both languages, the rule is covered by tests
  (`InvitationsTests`, `InvitedSignInTests`, `SignInFailureHandlerTests`), and it sits with the
  app's other secrets.
- **Why a secret.** The emails are personal data and this repository is public: they live only in
  Fly.io, never in the code, the tests, the logs or a URL.
- **An empty or missing list lets nobody in.** It must never mean "everyone": a forgotten secret
  closes the app instead of opening it to every Google account. Unlike the secrets above, a missing
  list does not stop the app from starting, so a deploy still succeeds, and the startup log says so:
  `Sign-in is invite-only and no email is invited` (a warning), or `Sign-in is invite-only: 3 emails
  invited`. The log shows the count, never the emails.
- **Only verified emails count**, and case does not matter. An email must be the Google account's
  own address, the one Google shows when choosing an account: an address outside Gmail only works
  if a Google account was created with it.
- **Locally anyone with a verified Google email can sign in**, so a fresh clone works without a
  list. To try the list locally, put `moneytrail.sign-in.invite-only=true` and
  `moneytrail.sign-in.invited-emails=<email>,<email>` in `backend/.env`.

### Adding or removing someone

In the dashboard, **moneytrail-api → Secrets**, replace `MONEYTRAIL_INVITED_EMAILS` with the whole
new list (`<email 1>,<email 2>,…`). The app reads the list when it starts, so the change applies
once the machine restarts with it: `fly secrets set` restarts it by itself; after a change in the
dashboard, if the startup log still shows the old count, run `fly secrets deploy --app
moneytrail-api`. Typing the value in the dashboard, rather than in `fly secrets set`, keeps the
emails out of the shell history.

Removing an email stops new sign-ins at once. A session the person already has ends after 30
minutes without use. Their data stays until it is deleted by hand (export and deletion are M4).

The secret must exist before a deploy that brings this rule for the first time, or nobody,
the maintainer included, can sign in.

### How many people use it

Counts only, in Neon's SQL editor, so no name, email or amount is shown:

```sql
SELECT
  (SELECT count(*) FROM users) AS users,
  (SELECT count(DISTINCT l.owner_id)
     FROM ledgers l JOIN accounts a ON a.ledger_id = l.id
    WHERE l.owner_type = 'USER' AND a.kind IN ('ASSET', 'LIABILITY')) AS users_with_accounts,
  -- Transactions only: neither an opening balance (it posts to EQUITY) nor a reversal.
  (SELECT count(DISTINCT e.created_by)
     FROM journal_entries e
    WHERE e.reverses_entry_id IS NULL
      AND NOT EXISTS (SELECT 1 FROM postings p JOIN accounts a ON a.id = p.account_id
                       WHERE p.entry_id = e.id AND a.kind = 'EQUITY')) AS users_with_transactions;
```

## One-time setup

Done once, in this order, before the first deploy.

### 1. Neon

- Project `moneytrail`, region AWS Europe Central 1 (Frankfurt), Postgres 18.
- In **Connect**, choose the **direct** connection (not the pooled one; Flyway needs a direct one).
  Neon shows `postgresql://<user>:<password>@<host>/<database>?sslmode=require&channel_binding=require`.
  The app, and the Flyway inside it, need the JDBC form, **without** user and password (they are
  separate secrets): `jdbc:postgresql://<host>/<database>?sslmode=require`. Neon's own form is not
  a JDBC URL.

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
| `GRAFANA_OTLP_AUTHORIZATION` | `Basic <…>`: Grafana Cloud → OpenTelemetry → *View connection details* → generate a token; take the `OTEL_EXPORTER_OTLP_HEADERS` value after `Authorization=`, with `%20` turned into a space |
| `MONEYTRAIL_INVITED_EMAILS` | the emails of the Google accounts that may sign in, separated by commas ([Who can sign in](#who-can-sign-in)) |

The release command migrates with the same `SPRING_DATASOURCE_*` secrets, so the database
credentials live only here.
`MONEYTRAIL_APP_URL`, `MONEYTRAIL_COOKIE_DOMAIN` and `GRAFANA_OTLP_ENDPOINT` (the stack's OTLP
gateway) are not secret; they are in `backend/fly.toml`.
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
- Its secrets: `FLY_API_TOKEN`, `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`. Paste the values
  in the environment's page (pencil → *Value* → *Update secret*), or pass them with `--body` /
  standard input. Not through the hidden prompt of a bare `gh secret set` on Windows: pasting
  there stored empty values, which only showed up when the deploy failed.
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
