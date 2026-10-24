# Development setup (Windows)

## 1. Tools

Install these with `winget` in PowerShell. Package IDs can change over time:
`winget search <name>` finds the current ones.

```powershell
winget install --id Git.Git
winget install --id GitHub.cli
winget install --id EclipseAdoptium.Temurin.25.JDK
winget install --id OpenJS.NodeJS.LTS
winget install --id Docker.DockerDesktop
winget install --id JetBrains.IntelliJIDEA.Community
```

Docker Desktop needs WSL 2. It is enabled during installation and may require a reboot.

Optional: `winget install --id Fly-io.flyctl`, plus VS Code for the frontend.

Then:

```powershell
git config --global user.name "Pedro Castanheira"
git config --global user.email "<an email linked to your GitHub account>"
gh auth login
```

The commit email must belong to your GitHub account, or commits don't count on your
contribution graph. GitHub's `<id>+Muzachio@users.noreply.github.com` address works if
you'd rather not expose your personal email.

## 2. Accounts and services

| Service | What for | When |
|---|---|---|
| GitHub | Code, CI, container registry | M0 |
| Google Cloud Console | OAuth client ID (web app) for sign-in | M0 |
| Fly.io | Backend hosting (card required, pay as you go) | M0 |
| Neon | Managed Postgres (free tier) | M0 |
| Cloudflare | Domain DNS + Pages for the frontend | M0 |
| A domain (~€10/year) | Same-site cookies, nicer link for the CV | M0 |
| Grafana Cloud | Logs, metrics, traces (free tier) | M0 |
| Resend | Budget alert emails (free tier) | M3 |

## 3. Running locally (once M0 exists)

Docker Desktop must be running: `bootRun` starts Postgres in it, and the tests start
their own throwaway Postgres with Testcontainers.

```powershell
cd backend; ./gradlew bootRun      # starts Postgres through compose.yaml automatically
cd frontend; npm install; npm run dev
```
