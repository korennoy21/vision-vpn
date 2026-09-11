# Linux: версия 0.2.0 с HTTPS/WebSocket

Для первого запуска используйте отдельный тестовый Ubuntu 24.04 VPS. Эта инструкция не выполнялась на вашем сервере. Существующие Amnezia/3x-ui сервисы она автоматически не изменяет. nginx-конфигурация пока не проверена исполняемым nginx в среде разработки.

## Подготовка

Распакуйте проект в `/opt/sever-vpn`. Нужны DNS-имя, действующий сертификат на него, `/dev/net/tun` и права CAP_NET_ADMIN.

```bash
sudo apt update
sudo apt install -y python3 python3-venv iproute2 nftables openssl nginx-full
cd /opt/sever-vpn
sudo python3 -m venv .venv
sudo .venv/bin/python -m pip install -r requirements.txt
test -c /dev/net/tun
ip -4 route show default
sudo install -d -m 700 /etc/sever
```

Копирование вашего сертификата — замените `/ПУТЬ/`:

```bash
sudo install -m 600 /ПУТЬ/fullchain.pem /etc/sever/fullchain.pem
sudo install -m 600 /ПУТЬ/privkey.pem /etc/sever/privkey.pem
```

Самоподписанный сертификат мобильные клиенты не примут. Linux `--ca` предназначен для стендового CA и не отключает проверку имени. ACME и обновление сертификатов не автоматизированы.

## Профиль устройства

```bash
sudo .venv/bin/python tools/provision.py \
  --users /etc/sever/users.json \
  --out /etc/sever/alex-android.profile.json \
  --user alex-android --host vpn.example.com --port 443 --path /api/session
```

Укажите реальное DNS-имя. Отдельный профиль нужен каждому устройству; JSON содержит секрет. На сервере хранится хеш. После добавления/отзыва пользователя перезапустите backend; это прервёт существующие сессии. Утилита выдачи профилей рассчитана на последовательные запуски, а не параллельное редактирование users.json.

## TUN и firewall

Сохраните исходные настройки, затем выберите WAN-интерфейс из default route. В примере ниже `ens3` обязательно заменить своим.

```bash
sysctl net.ipv4.ip_forward
sudo nft list ruleset
.venv/bin/python tools/firewall.py --wan ens3
sudo sysctl -w net.ipv4.ip_forward=1
sudo .venv/bin/python tools/firewall.py --wan ens3 --apply
```

Утилита создаёт только таблицы `sever_filter` и `sever_nat`, не очищая остальной ruleset. Повторное применение при существующих таблицах отклоняется. UFW/firewalld и firewall провайдера должны отдельно разрешать TCP 443, `sever0 → WAN` и обратный established/related. Существующие drop-правила могут перекрывать разрешения. **Порт 8787 снаружи не открывать.**

nft-правила и sysctl из этих команд временные; постоянную настройку включите в ваше управление сетью после проверки. При конфликте сети `10.77.0.0/24` нужен отдельный проект изменения адресации — она сейчас фиксирована.

## Backend

```bash
sudo install -m 644 deploy/sever.service /etc/systemd/system/sever.service
sudo systemctl daemon-reload
sudo systemctl enable --now sever
sudo systemctl status sever --no-pager
sudo journalctl -u sever -n 50 --no-pager
```

Backend слушает `127.0.0.1:8787`, создаёт TUN и ждёт запросов nginx. Прямой публичный запуск raw TLS из версии 0.1.0 удалён. CLI запрещает bind backend на нелокальный IP.

## nginx и обычный сайт

Публичная точка — настоящий HTTPS-сервер. Подготовьте ваш обычный статический сайт в `/var/www/html` либо измените root. При отсутствии сайта эффект «обычный веб-сервер с содержимым» не обеспечивается одним конфигом.

Проверьте наличие auth_request:

```bash
sudo nginx -V 2>&1
```

В выводе нужен `--with-http_auth_request_module`. Файл `deploy/nginx-sever.conf` включается внутри контекста `http {}`. На отдельном VPS его можно поставить так:

```bash
sudo install -m 644 deploy/nginx-sever.conf /etc/nginx/conf.d/sever.conf
sudo nano /etc/nginx/conf.d/sever.conf
sudo nginx -t
sudo systemctl reload nginx
```

Перед `nginx -t` замените `server_name vpn.example.com`, пути сертификата и root. Согласуйте занятый TCP 443 и другие виртуальные хосты. Не заменяйте целиком чужой `nginx.conf`.

Для смены `/api/session` измените одновременно профиль, `--path` в systemd и location в nginx. `/_sever_auth` объявлен internal; backend `/_auth` не должен быть доступен напрямую снаружи.

Ожидания после развёртывания:

- `https://ВАШ_ДОМЕН/` открывает ваш сайт.
- Неавторизованный запрос по VPN-пути обрабатывается как ресурс сайта, обычно 404.
- Действующий профиль проходит HTTPS-авторизацию и WebSocket Upgrade.
- Прямое внешнее подключение к 8787 недоступно.

Это ещё нужно проверить на реальном nginx, включая неверные/дублирующиеся заголовки, методы, состояние backend, поведение POST/HEAD и WebSocket через reverse proxy.

## Linux-клиент

На другом Linux-узле установите зависимости и запустите:

```bash
sudo .venv/bin/python -m sever.client /путь/устройство.profile.json
```

Клиент создаёт `severc0`, но не меняет default route и DNS. Для узкого стендового теста, если нет конфликтующего маршрута:

```bash
ip -4 route show 1.1.1.1/32
sudo ip route add 1.1.1.1/32 dev severc0
ping -c 3 1.1.1.1
sudo ip route del 1.1.1.1/32 dev severc0
```

ICMP должен разрешаться сетью. Full tunnel, DNS, IPv6 и kill switch у Linux-клиента не автоматизированы.

## Откат

Остановите `sever`, удалите только добавленный nginx-конфиг после сохранения нужного сайта, выполните `nginx -t` и reload. Для удаления обеих созданных nft-таблиц:

```bash
sudo .venv/bin/python tools/firewall.py --wan ens3 --remove --apply
```

Верните forwarding к сохранённому исходному значению, если он не нужен другим сервисам. При возврате версии 0.1.0 потребуется её прежний unit и профили; такой возврат возвращает и обнаруженные признаки.
