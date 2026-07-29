# Fool Legends

Залипательная мини-игра на реакцию и память «Джокер говорит» (вариация Simon Says
с обманом). Нативный Android на **Kotlin**, всё рисуется кодом на `Canvas` — из
ассетов только лица Джокера и арт лоадинг-экрана.

- **Package / applicationId:** `com.legendfool.foollegends`
- **minSdk:** 30, **targetSdk / compileSdk:** 36
- **Ориентация:** лоадинг и веб-режим — портрет и ландшафт, сама игра — только портрет.

## Как играть

Джокер показывает команду (например, «НАЖМИ КРАСНУЮ») — игрок жмёт нужный цвет.
Кнопки 2×2:

```
КРАСНАЯ   СИНЯЯ
ЗЕЛЁНАЯ   ЖЁЛТАЯ
```

Прогрессия сложности (каждый успех = +1 уровень):

| Уровни | Что добавляется |
|--------|-----------------|
| 1–5    | обычные одиночные команды |
| 6–10   | ложь: «Я ВРУ! НЕ ЖМИ …» → жми любой цвет, кроме названного |
| 11–20  | таймер 3 секунды на ответ |
| 21+    | серии цветов; при «ДЖОКЕР ВРЁТ» жми противоположные (красный↔синий, зелёный↔жёлтый), серии длиннее и быстрее — бесконечно |

Ошибка → Джокер смеётся, экран трясётся, забег начинается заново. Рекорд (лучший
уровень) хранится локально.

## Структура

```
app/src/main/java/com/legendfool/foollegends/
  ignition/GateKeeper.kt  — лаунчер: сплэш + выбор режима (веб / игра)
  ignition/JesterApp.kt   — Application: Firebase, App Check, AppsFlyer
  stage/CanvasStage.kt    — полноэкранный WebView-шелл
  stage/HeraldStage.kt    — экран разрешения на уведомления
  stage/VoidStage.kt      — экран «нет интернета»
  stage/StageChrome.kt    — иммерсив, safe area, переходы
  courier/                — AppsFlyer-атрибуция, клиент конфига, User-Agent
  beacon/                 — FCM-сервис и передача push-URL в живой WebView
  strongbox/              — хранилище состояния и XOR-маскировка секретов
  pulse/PulseMeter.kt     — состояние сети
  charter/                — конфиг и модель ответа бэкенда
  LoadingView.kt          — арт + "Loading..." + прогресс-бар (есть indeterminate)
  MainActivity.kt         — меню игры
  GameActivity.kt         — портретный хост игры
  GameView.kt             — вся отрисовка: кнопки, джокер, подсветка, тряска, таймер
  GameEngine.kt           — чистая логика: цвета, генерация уровней, правила лжи
  Fullscreen.kt           — иммерсивный полноэкранный режим
app/src/main/res/drawable-nodpi/  — joker_*.png, loading_*, gray_* (WebP)
app/src/main/res/layout{,-land}/  — разметка серых экранов
assets/gray/          — исходники арта серых экранов
web/                  — privacy-policy.html, support.html (для хостинга)
tools/make_icons.ps1  — генерация иконок из лица Джокера
```

## Сборка

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"   # JDK 17–21
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease bundleRelease   # нужен keystore/keystore.properties
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.
Либо просто открыть папку как проект в Android Studio и нажать Run.

Установка на устройство с заблокированным экраном — только потоковая:
`adb install -r app/build/outputs/apk/debug/app-debug.apk`.

Логи режима: `adb logcat -v time -s GateKeeper TraceCourier ScoutCourier CanvasStage BeaconService`.

## Privacy / Support

- Privacy Policy: https://foollegends.com/privacy-policy.html
- Support: https://foollegends.com/support.html

Исходники страниц — в папке `web/`.
