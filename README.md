# Расписание МОУ СОШ №12 им. В.Ф. Суханова (Android 16 + GitHub Actions)

Автономная система отслеживания школьного расписания без платного сервера:
- **Serverless-парсер (`parser/`)**: каждые 30 минут в учебное время (пн–сб, 03:00–17:00 UTC) скачивает актуальные ученические PDF со страницы [nsportal.ru](https://nsportal.ru/user/1608303/page/izmenenie-raspisaniya), игнорируя расписания учителей, парсит таблицы всех 25 классов и обновляет `docs/schedule.json`.
- **Android 16 приложение (`app/`)**:
  - `compileSdk = 36`, `targetSdk = 36`, Jetpack Compose + Material Design 3 + Dynamic Color (Material You).
  - Умное переключение «Сегодня / Завтра» по часовому поясу `Europe/Saratov` (UTC+4).
  - **Live Updates** на официальном API `Notification.ProgressStyle` с нативным системным таймером обратного отсчёта (`setChronometerCountDown(true)`) и точными будильниками `AlarmManager.setExactAndAllowWhileIdle()`.
  - Адаптивный виджет рабочего стола на **Jetpack Glance** в стиле Material You.
  - Автоматическая сборка Debug APK в GitHub Actions (`.github/workflows/build_apk.yml`).

## Настройка в GitHub
1. Откройте **Settings → Actions → General → Workflow permissions**, выберите **Read and write permissions** и сохраните (нужно для коммита `docs/schedule.json`).
2. Перейдите во вкладку **Actions** и при желании запустите вручную **Update School Schedule** и **Build Android APK**.
3. Скачайте собранный артефакт `school12-schedule-debug-apk` из последнего успешного запуска **Build Android APK**.
