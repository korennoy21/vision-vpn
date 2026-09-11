# Сборка Android и iOS

Архив 0.3.0 добавляет obfs4-адаптер только для Linux. Мобильные исходники остаются
на версии 0.2.0/WSS и отклоняют профили `transport=obfs4`. Нативный PT-движок,
его сокеты и жизненный цикл ещё требуется интегрировать на обеих платформах.

В архиве исходники. Здесь не выполнялись Gradle/Xcode-сборки и не создавались APK/IPA.
Полный набор условий приёмки находится в [ACCEPTANCE.md](ACCEPTANCE.md).

Версия 0.2.0 использует профиль **v2 / transport=wss**. Старые профили преобразуйте
по MIGRATION.md. Android использует OkHttp 4.12.0, iOS — системный
URLSessionWebSocketTask. При сборке Android понадобится загрузить OkHttp и его
транзитивные зависимости. Межъязыковой JDK-тест не проверяет Android/OkHttp.

## Android / APK

Фиксированная конфигурация проекта: JDK 17, Gradle 8.11.1, Android Gradle Plugin 8.9.2,
SDK platform 35, Build Tools 35.0.0; телефон Android 10 или новее.
Совместимость инструментов подтверждается [таблицей Android AGP 8.9](https://developer.android.com/build/releases/agp-8-9-0-release-notes).

1. Установите Android Studio и через SDK Manager платформу 35 и Build Tools 35.0.0.
2. Распакуйте архив. В Android Studio откройте **папку `sever-vpn/android`**.
3. В настройках Gradle выберите JDK 17.
4. Wrapper jar не включён в исходный пакет. При установленном Gradle 8.11.1 выполните
   из папки `android`:

```bash
gradle wrapper --gradle-version 8.11.1 --distribution-type bin
```

В Windows, когда wrapper создан:

```powershell
.\gradlew.bat assembleDebug
```

В Linux/macOS:

```bash
./gradlew assembleDebug
```

Результат успешной сборки: `android/app/build/outputs/apk/debug/app-debug.apk`.
Debug APK предназначен для стенда. Release-подпись, иконка для публикации,
store listing и публикация не подготовлены.

Без локального Gradle можно загрузить проект в свой GitHub-репозиторий и запустить
вложенный workflow. Он устанавливает указанную версию Gradle и сохраняет APK как
artifact `sever-android-debug`. Этот workflow ещё не запускался, так что успешная
облачная сборка не подтверждена. Из этого сеанса ничего в GitHub не опубликовано.

На телефоне:

1. Установите APK и откройте «Север».
2. «Импортировать JSON-файл» → профиль, созданный `tools/provision.py`.
3. «Подключиться» → подтвердить системный запрос VPN.
4. Статус должен смениться на «Подключено • Север-1», счётчики — изменяться.
5. Для режима без выхода мимо VPN включите в системных настройках Android
   «Постоянный VPN» и «Блокировать соединения без VPN». Проверьте это отключением сети.

Профиль хранится в AES-256-GCM, ключ в Android Keystore; резервное копирование
приложения отключено. Импортированный исходный JSON вне приложения остаётся
секретом пользователя. Скриншоты окна Android запрещены FLAG_SECURE.

Клиент использует [VpnService](https://developer.android.com/develop/connectivity/vpn):
защищает транспортный сокет от маршрутизации в свой TUN, просит разрешение системы,
создаёт интерфейс и запускает foreground service. Системные особенности и ограничения
производителей телефона всё равно требуют проверки.

## iOS / iPhone

Нужны Mac, Xcode с iOS SDK, XcodeGen и Apple signing/provisioning для приложения
и Packet Tunnel Extension. Установка на iPhone требует профиля подписи с нужными
entitlements; один исходный архив не является устанавливаемым приложением.

```bash
brew install xcodegen
cd sever-vpn/ios
xcodegen generate
open Sever.xcodeproj
```

1. В Xcode выберите Team для **Sever** и **Tunnel**.
2. Если bundle identifiers заняты, смените `dev.sever.vpn` и `dev.sever.vpn.tunnel`
   в `project.yml` и соответствующую строку `providerBundleIdentifier` в
   `Sever/SeverApp.swift`. Снова выполните `xcodegen generate`.
3. Проверьте Network Extensions / Packet Tunnel и общий Keychain access group
   для приложения и extension. Prefix группы формируется из вашей команды.
4. Подключите физический iPhone, выберите схему Sever и выполните Run.
5. Импортируйте отдельный профиль и разрешите добавление VPN-конфигурации.

Для проверки компиляции на Mac без подписи:

```bash
xcodebuild -project Sever.xcodeproj -scheme Sever -sdk iphoneos \
  -configuration Debug CODE_SIGNING_ALLOWED=NO build
```

Это **не** создаёт устанавливаемую подписанную IPA. Для распространения нужны
Archive/Export или TestFlight с вашей подписью и доступом Apple.

Клиент использует [NEPacketTunnelProvider](https://developer.apple.com/documentation/networkextension/nepackettunnelprovider).
В профиле NETunnelProviderManager хранится ссылка Keychain, а секретный JSON —
в общей для приложения и extension группе Keychain. Включён includeAllNetworks,
но поведение системных исключений и отключения VPN на конкретной iOS не проверено.

## Резервные серверы

В обоих клиентах профиль принимает до 16 адресов:

```json
{
  "version": 2,
  "protocol": "sever1",
  "transport": "wss",
  "name": "Мой телефон",
  "endpoints": [
    {"host": "vpn1.example.com", "port": 443, "path": "/api/session"},
    {"host": "vpn2.example.com", "port": 443, "path": "/api/session"}
  ],
  "user": "device-01",
  "token": "ЗАМЕНИТЬ_ТОКЕНОМ_ИЗ_PROVISION"
}
```

Это иллюстрация, не рабочий профиль. На резервных серверах должны совпадать
user, хеш токена и IP-адрес устройства. Автозагрузка нового списка серверов пока
не реализована: после изменения списка импортируйте обновлённый профиль.

QR-сканер в этой версии отсутствует; разрешение камеры не запрашивается.

Обязательные дополнительные проверки 0.2.0: WebSocket через nginx, защита
всех сокетов OkHttp от попадания в VPN, маршрутизация URLSession внутри Packet
Tunnel Extension при includeAllNetworks, отмена WSS при STOP, смена Wi-Fi/LTE,
отказ от HTTP-redirect без передачи Authorization. iOS-код этих путей не собран
и не проверен на устройстве; его поведение нельзя считать подтверждённым.

## Выпуск 0.4.0

Текущие мобильные исходники обновлены до 0.4.0: добавлены HTTPS-ссылка и обновление
профиля перед подключением. Основная актуальная инструкция — `../INSTALL_RU.md`,
разделы 9–10. Предшествующие замечания о 0.2.0/0.3.0 относятся к истории. WSS
остаётся единственным интегрированным мобильным транспортом.
