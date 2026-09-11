# Upgrade VISION Control 1.0.0 → 1.1.0

This update changes only the management plane and the Android administration app. It does not replace VPN client profiles, `panel.env`, `node.env`, `node.json`, Caddy certificates, or the live `data/` directory.

## Before update

On the VPS, from the current project directory:

```bash
cd /opt/sever/sever-vpn
mkdir -p /root/vision-backups
cp -a control /root/vision-backups/control-before-1.1.0-$(date +%Y%m%d-%H%M%S)
cp -a data/control.sqlite3 data/master.key /root/vision-backups/ 2>/dev/null || true
```

If the current panel supports the CLI backup command, also create a consistent SQLite copy:

```bash
docker compose --env-file panel.env --env-file node.env \
  -f ops/compose.panel.yml -f ops/compose.combined.yml \
  exec panel python -m control.app backup --data /data --output /tmp/control-before-1.1.0.sqlite3
```

The `/tmp` copy above is inside the container; use the host-side `data/control.sqlite3` + `data/master.key` backup as the durable rollback set unless you explicitly copy the container file out.

## Apply source patch

Extract `vision-control-v1.1.0-patch.zip` on a workstation and upload the contained files over the existing project tree, or extract it directly into `/opt/sever/sever-vpn` after making the backup above. The patch does not contain `data/`, `panel.env`, `node.env`, `node.json`, `caddy-data/` or `caddy-config/`.

Then rebuild only the panel image and recreate only the panel service:

```bash
cd /opt/sever/sever-vpn

docker compose --env-file panel.env --env-file node.env \
  -f ops/compose.panel.yml -f ops/compose.combined.yml \
  build panel

docker compose --env-file panel.env --env-file node.env \
  -f ops/compose.panel.yml -f ops/compose.combined.yml \
  up -d --force-recreate panel

sleep 5
curl -fsS https://panel.korennoy-ay.com/healthz
docker compose --env-file panel.env --env-file node.env \
  -f ops/compose.panel.yml -f ops/compose.combined.yml \
  logs --since=2m panel
```

Do not recreate Caddy or the VPN node for this Control-only update unless another change requires it.

## Database migration

The migration is automatic and additive. On first 1.1.0 startup VISION Control creates the engine/routing/DNS/API tables and adds role/session columns. Existing clients, nodes, tokens, telemetry and assignments are retained.

## Rollback

Stop the panel, restore the pre-update `control/` directory and the matching `control.sqlite3` + `master.key`, then recreate only the panel service. Database and key must always be restored as a pair.
