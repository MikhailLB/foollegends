# Fool Legends

Залипательная мини-игра на реакцию и память «Джокер говорит» (вариация Simon Says
с обманом). Нативный Android на **Kotlin**, всё рисуется кодом на `Canvas` — из
ассетов только лица Джокера и арт лоадинг-экрана.

- **Package / applicationId:** `com.legendfool.foollegends`
- **minSdk:** 24, **targetSdk / compileSdk:** 35
- **Ориентация:** лоадинг — портрет и ландшафт, сама игра — только портрет.

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
  LoadingActivity.kt   — сплэш (две ориентации) → запуск игры
  LoadingView.kt       — арт + "Loading..." + анимированный прогресс-бар (код)
  GameActivity.kt      — портретный хост игры
  GameView.kt          — вся отрисовка: кнопки, джокер, подсветка, тряска, таймер
  GameEngine.kt        — чистая логика: цвета, генерация уровней, правила лжи
  Fullscreen.kt        — иммерсивный полноэкранный режим
app/src/main/res/drawable-nodpi/  — joker_*.png, loading_portrait/landscape.jpg
web/                  — privacy-policy.html, support.html (для хостинга)
tools/make_assets.ps1 — лоадинг-арт и иконки (адаптивные + legacy) из assets/
```

## Сборка

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"   # JDK 17–21
.\gradlew.bat assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.
Либо просто открыть папку как проект в Android Studio и нажать Run.

## Privacy / Support

- Privacy Policy: https://foollegends.com/privacy-policy.html
- Support: https://foollegends.com/support.html

Исходники страниц — в папке `web/`.
