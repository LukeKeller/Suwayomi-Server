# Deploying this Suwayomi-Server fork (agent runbook)

This is a deterministic, agent-runnable deploy process for the `LukeKeller/Suwayomi-Server`
fork. The server is a single fat jar; deployment = **build the jar from this checkout →
ship it to the host → restart the systemd service → health-check → roll back on failure.**

`scripts/deploy.sh` performs all of it. An agent should drive that script rather than
re-deriving the steps. Exit code is the contract: **0 = deployed and healthy**, non-zero =
nothing changed or the previous jar was restored.

---

## 1. Inputs the agent needs

Set these as environment variables before invoking the script. Only `DEPLOY_HOST` is
mandatory; the rest default to this project's reference packaging.

| Var | Required | Default | Meaning |
|---|---|---|---|
| `DEPLOY_HOST` | yes | — | ssh target, e.g. `deploy@manga.example.com` |
| `REMOTE_JAR_PATH` | no | `/opt/suwayomi/Suwayomi-Server.jar` | jar the service runs |
| `SERVICE_NAME` | no | `suwayomi-server` | systemd unit |
| `SERVICE_USER` | no | `suwayomi-server` | owner of the deployed jar |
| `SERVER_PORT` | no | `4567` | port the server listens on |
| `HEALTH_PATH` | no | `/` | path for the health probe |
| `REMOTE_SUDO` | no | `sudo` | privilege prefix; set to `""` if the ssh user is root |
| `HEALTH_TIMEOUT` | no | `90` | seconds to wait for the service to come up |
| `KEEP_BACKUPS` | no | `5` | how many `*.bak.<stamp>` jars to retain |

**If the agent does not know these values**, discover them on the host before deploying:
- service name: `systemctl list-units --type=service | grep -i suwayomi`
- jar path + run user: `systemctl cat <service>` (look at `ExecStart` and `User=`)
- port: `grep -i port <rootDir>/server.conf` or `ss -ltnp | grep java`

Record the discovered values; they are stable across deploys.

## 2. Prerequisites

- **Local (build) box:** JDK **21** and this checkout. Memory is handled by the committed
  `gradle.properties` (`kotlin.daemon.jvmargs=-Xmx6g`); no extra flags needed.
- **Remote (run) box:** JDK 21 available to the service, a systemd unit, and an account the
  agent can ssh into with `sudo` (or root). Outbound network from the host to the Stackwise
  instance if the Stackwise tracker is in use.

## 3. One-command deploy

```bash
DEPLOY_HOST=deploy@manga.example.com scripts/deploy.sh deploy
```

This runs, in order: `build → preflight → ship (with backup) → restart → verify`. On a failed
health check it **automatically rolls back** to the previous jar and exits non-zero. On
success it prunes old backups and exits 0.

Sub-commands (for partial / recovery flows):

```bash
scripts/deploy.sh build      # just produce server/build/Suwayomi-Server-*.jar
scripts/deploy.sh verify     # health-check the currently running service
scripts/deploy.sh rollback   # restore the most recent backup jar and restart
```

## 4. What "success" looks like (agent acceptance criteria)

The deploy is complete **only** when all hold:
1. `scripts/deploy.sh deploy` exits `0`.
2. Final log line is `Deploy complete and healthy.`
3. `scripts/deploy.sh verify` independently returns `0` (service `active` + any HTTP
   response on `:$SERVER_PORT$HEALTH_PATH`).

Any HTTP status code (200, 401, 403, even 404) means the app is serving on the port and is
treated as healthy — the probe hits Suwayomi directly on localhost, so the code comes from
the app, not a proxy. Only a connection failure / timeout / non-`active` unit is a failure.

## 5. Failure handling

`deploy` self-heals: on a bad health check it restores the latest `*.bak.*` jar, restarts,
and re-verifies before exiting non-zero. If `deploy` itself dies mid-flight (e.g. ssh drop),
run `scripts/deploy.sh rollback` then `scripts/deploy.sh verify`. The script dumps the last
40 journald lines (`journalctl -u <service>`) on any health failure — read those before
retrying; a config/port mistake will not be fixed by re-running.

## 6. First-time setup (no service yet)

If `systemctl status <service>` shows the unit is missing, create it once (manual, root):

```bash
# user + data dir
sudo useradd --system --home /var/lib/suwayomi --shell /usr/sbin/nologin suwayomi-server || true
sudo mkdir -p /opt/suwayomi /var/lib/suwayomi
sudo chown -R suwayomi-server:suwayomi-server /var/lib/suwayomi

# unit (adapt the reference at scripts/resources/pkg/systemd/suwayomi-server.service)
sudo tee /etc/systemd/system/suwayomi-server.service >/dev/null <<'UNIT'
[Unit]
Description=Suwayomi-Server (LukeKeller fork)
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
User=suwayomi-server
Group=suwayomi-server
ExecStart=/usr/bin/java -jar /opt/suwayomi/Suwayomi-Server.jar
Environment=SUWAYOMI_TACHIDESK_CONFIG_SERVER_ROOTDIR=/var/lib/suwayomi
Restart=on-failure

[Install]
WantedBy=multi-user.target
UNIT

sudo systemctl daemon-reload
sudo systemctl enable suwayomi-server
```

Then run the normal `scripts/deploy.sh deploy` (it ships the jar and starts the service).

## 7. Configuring the Stackwise tracker after deploy

The Stackwise tracker needs the instance URL set once. It lives in the server config under
the data `rootDir` (`<rootDir>/server.conf`, HOCON):

```hocon
server.stackwiseTrackerUrl = "https://stackwise.example.com"   # include the sub-path if any
```

Equivalent system property on the launch command:
`-Dsuwayomi.tachidesk.config.server.stackwiseTrackerUrl=https://stackwise.example.com`.

Restart the service after changing it (`scripts/deploy.sh verify` to confirm). Then in the
WebUI: Tracking → Stackwise → log in, pasting the Stackwise API token as the **password**.

## 8. Docker alternative (optional, not wired up)

The current model is systemd + a bare jar. If you later move to docker-compose, the same jar
is the artifact: base image `eclipse-temurin:21-jre`, `COPY` the built
`server/build/Suwayomi-Server-*.jar`, `ENTRYPOINT ["java","-jar","/app/server.jar"]`, mount a
volume at the configured `rootDir`, and publish `4567`. The `deploy` flow would then become
`build image → push → compose pull && up -d` — swappable into `scripts/deploy.sh` without
touching the build step. Left as a follow-up.
