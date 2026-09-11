# Upgrade VISION Control 1.1.x → 1.2.0

## Что меняется

1. Обновляется VISION Control Web и SQLite-схема.
2. Обновляется VISION Node: он начинает собирать DNS/flow metadata и отправлять её панели.
3. Caddy, TLS-сертификаты, `panel.env`, `node.env`, `node.json`, клиентские токены и существующие профили не меняются.

Перед обновлением обязательно сохраните согласованную копию `control.sqlite3` и `master.key`.

## Порядок обновления

На сервере проекта:

```bash
cd /opt/sever/sever-vpn
```

Скопируйте поверх проекта файлы из `vision-control-v1.2.0-activity-patch.zip`.

Проверка исходников:

```bash
cat CONTROL_VERSION
python3 -m py_compile control/*.py sever/*.py
node --check control/static/app.js
```

Ожидаемая версия: `1.2.0`.

Сначала пересоберите общий image:

```bash
docker compose \
  --env-file panel.env \
  --env-file node.env \
  -f ops/compose.panel.yml \
  -f ops/compose.combined.yml \
  build panel
```

Переключите панель:

```bash
docker compose \
  --env-file panel.env \
  --env-file node.env \
  -f ops/compose.panel.yml \
  -f ops/compose.combined.yml \
  up -d --no-deps --force-recreate panel
```

После `healthy` обновите node. Это кратко разорвёт текущую VISION Secure VPN-сессию, после чего клиент должен переподключиться:

```bash
docker compose \
  --env-file panel.env \
  --env-file node.env \
  -f ops/compose.panel.yml \
  -f ops/compose.combined.yml \
  up -d --no-deps --force-recreate node
```

Caddy не пересоздавайте.

## Проверка

```bash
curl -fsS https://panel.korennoy-ay.com/healthz

docker compose \
  --env-file panel.env \
  --env-file node.env \
  -f ops/compose.panel.yml \
  -f ops/compose.combined.yml \
  ps
```

В панели появится раздел **«Сайты / активность»**. Подключите VISION VPN, откройте несколько сайтов и подождите 5–15 секунд.

## Приватность

VISION 1.2.0 не выполняет TLS interception. Панель не получает содержимое HTTPS и полные URL. Домены определяются по обычному DNS и сопоставлению DNS A-ответов с последующими потоками. DoH/DoT/ECH могут уменьшить точность определения домена.
