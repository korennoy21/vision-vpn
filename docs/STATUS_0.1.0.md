# VISION 0.1.0 — статус

## Готово

### VISION VPN Android
- новый интерфейс и бренд VISION;
- одна кнопка подключить/отключить;
- исправленный явный STOP сервиса;
- QR-сканирование;
- импорт по ссылке/файлу/тексту;
- рабочий существующий WSS transport;
- split tunneling по приложениям Android;
- экран профилей, маршрутов и настроек.

### VISION VPN iOS
- новый SwiftUI UI;
- одна кнопка подключить/отключить;
- QR-сканер через AVFoundation;
- импорт по ссылке/файлу/тексту;
- существующий Packet Tunnel transport сохранён;
- подготовленные экраны маршрутов и настроек.

### VISION Control Android
- отдельное приложение администратора;
- HTTPS-only оболочка панели;
- запрет обхода TLS ошибок;
- смена адреса панели в приложении.

### VISION Control Web
- новый адаптивный UI;
- dashboard;
- клиенты;
- серверы;
- журнал;
- политики;
- сохранение DNS/MTU/LAN/reconnect параметров в control DB;
- roadmap-разделы для protocols/routes/DNS/RBAC/API/backups/monitoring.

### Сервер
- transport core сохранён совместимым;
- control DB мигрируется без удаления существующих таблиц;
- внутренний идентификатор `sever1` временно сохранён ради wire compatibility.

## Следующий этап

1. Унифицированная модель импортированных конфигов.
2. WireGuard/AmneziaWG adapter на Android и iOS.
3. OpenVPN adapter.
4. Xray-family adapter для VLESS/VMess/Trojan там, где это технически и лицензионно допустимо.
5. Domain/IP/CIDR routing engine.
6. Стабильный Wi-Fi/LTE handover.
7. Node telemetry: CPU/RAM/disk/network/session counters.
8. QR-выдача профиля прямо из VISION Control.
9. RBAC / 2FA / API tokens / webhooks / backups.
10. Release signing и production hardening.

## Не заявлено как готовое

- полноценное подключение WireGuard/OpenVPN/VLESS/Trojan/Shadowsocks в 0.1.0;
- per-app routing на обычном iOS без управляемого устройства;
- production-подпись APK/IPA;
- гарантированная работа в любой фильтрующей сети.
