# VISION VPN Suite 0.1.0

Первый этап нового семейства **VISION** поверх проверяемого транспортного ядра текущего SEVER-1/WSS сервера.

## Что входит

- `android-client/` — новый Android-клиент **VISION VPN**.
- `ios-client/` — новый iOS-клиент **VISION VPN** + Packet Tunnel Extension.
- `android-admin/` — отдельное Android-приложение **VISION Control** для защищённого доступа к панели.
- `control/` — новая веб-панель **VISION Control**.
- `sever/`, `ops/` — совместимый серверный transport core и deployment-конфигурация.
- `.github/workflows/verify.yml` — сборка двух Android APK, compile-check iOS и серверные тесты.

## Уже реализовано в 0.1.0

Android VISION VPN:
- одна кнопка подключения / отключения;
- явная STOP-команда сервису и `START_NOT_STICKY`, чтобы ручное отключение не превращалось в цикл автоперезапуска;
- импорт профиля по ссылке, JSON-файлу и вставкой текста;
- сканирование QR-кода камерой (ZXing Embedded);
- современный тёмный интерфейс VISION;
- рабочий VISION Secure / SEVER-1 WSS transport;
- функциональный split tunneling по приложениям Android: весь трафик / только выбранные через VPN / выбранные без VPN;
- Always-on и lockdown открываются через системные настройки Android;
- существующая логика обновления подписки и failover endpoint'ов сохранена.

VISION VPN iOS:
- новый SwiftUI-интерфейс;
- одна кнопка подключения / отключения;
- импорт ссылки / файла / текста;
- встроенный QR-сканер на AVFoundation;
- существующий Packet Tunnel / WSS transport сохранён;
- экран маршрутов и настроек подготовлен для следующего этапа.

VISION Control Web:
- полностью новая адаптивная панель;
- dashboard, клиенты, серверы, журнал;
- отдельный раздел политик;
- хранение базовых будущих политик DNS / MTU / local-network / reconnect;
- задел под несколько VPN-движков, DNS-фильтры, маршруты, роли, API, backups и телеметрию.

VISION Control Android:
- отдельное приложение администратора;
- HTTPS-only WebView;
- TLS-ошибки не обходятся;
- переходы на чужие домены не открываются внутри административного WebView;
- адрес панели можно изменить, начальное значение — `https://panel.korennoy-ay.com`.

## Важный статус multi-protocol

В 0.1.0 приложение **распознаёт** конфигурации WireGuard, OpenVPN, VLESS/VMess, Trojan и Shadowsocks и имеет архитектуру `EngineRegistry`, но рабочим встроенным VPN-движком пока остаётся VISION Secure (совместимый `sever1`).

Приложение специально не показывает неподключённый движок как рабочий. Следующий этап — встраивание и тестирование конкретных engines/adapters. Это будет делаться по одному движку с отдельными тестами подключения, DNS, маршрутизации, reconnect и утечек.

## Ограничение iOS по приложениям

Обычное iOS-приложение не может перечислять произвольные сторонние приложения и свободно назначать им per-app split tunneling так, как Android `VpnService`. Для корпоративных управляемых устройств per-app VPN реализуется системными средствами Apple/MDM. Для обычного iOS-клиента будут реализованы маршруты IP/CIDR, доменные политики там, где это корректно возможно внутри Packet Tunnel, и локальные исключения.

## Проверки

Серверный набор тестов:

```bash
python3 -m unittest discover -s tests -v
```

На момент упаковки: **43/43 server/protocol tests passed**.

## GitHub Actions

После загрузки репозитория на GitHub откройте **Actions → VISION build and verify → Run workflow**.

Артефакты:
- `vision-vpn-android-debug` → клиентский APK;
- `vision-control-android-debug` → административный APK.

Для iOS workflow выполняет compile-check без подписи. Установка на iPhone требует Apple Developer Team, provisioning profile и Network Extension entitlement.

## Что делать дальше

Следующий этап проекта: WireGuard/AmneziaWG engine adapter → OpenVPN adapter → Xray-family adapter → единая модель импортированных профилей → доменные/IP правила → расширенная телеметрия VISION Control → безопасное обновление существующего VPS без потери базы и клиентов.
