# 🎥 Slow-Mo Camera (Android 14 / Camera2 API)

Нативное Android-приложение для высокоскоростной съёмки и создания Slow-Motion видео (120/240 FPS) с использованием **Camera2 Constrained High-Speed Capture Session**.

Специально оптимизировано для устройств на чипсетах **MediaTek Helio G99** (включая **Infinix Note 30**, камера 64MP) с расширенной HAL-диагностикой и обработкой вендорных ограничений.

---

## ✨ Ключевые возможности

1. **Минималистичный интерфейс:**
   - Быстрый доступ к настройкам через шестерёнку ⚙️ в левом верхнем углу (режимы HFR/HSR, FPS, кодек, диагностика).
   - **Управление зумом жестом щипка (Pinch-to-zoom):** разведение пальцев приближает, сведение — отдаляет, двойное нажатие сбрасывает зум на 1.0x.

2. **Встроенный Slow-Mo плеер и открытие из Галереи:**
   - Просмотр последнего записанного видео или **любого видеофайла из памяти телефона / Галереи**.
   - Регулировка скорости воспроизведения (0.125x, 0.25x, 0.5x, 1.0x, 2.0x).

3. **Обнаружение поддержки High-Speed HAL & Root Unlock:**
   - Проверка флага `REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO` в `CameraCharacteristics`.
   - Внедрение проприетарных MediaTek Vendor Tags (`MtkVendorTagHelper.kt`).
   - Готовый Magisk-модуль и root-скрипт для разблокировки 120/240 FPS на MediaTek Helio G99 / Infinix Note 30.

4. **Два режима сохранения видео:**
   - **HFR (High-Frame-Rate Slow-Mo):** Захват сенсором на 120/240 FPS, сохранение со скоростью **30 FPS**. Видео сразу замедлено в **4x / 8x** раз.
   - **HSR (High-Speed Recording):** Захват и сохранение с полной частотой (120/240 FPS) для плавного просмотра или монтажа.

5. **Выбор кодеков и контейнеров:**
   - **MP4:** Видеокодек H.264 (AVC) + AAC аудио.
   - **WebM:** Видеокодек VP9 / VP8 + Opus.

5. **Стабильная сессия Camera2:**
   - Инициализация сессии через `createConstrainedHighSpeedCaptureSession` / `SessionConfiguration.SESSION_HIGH_SPEED`.
   - Формирование пакетов запросов через `createHighSpeedRequestList()` и воспроизведение через `setRepeatingBurst()`.
   - Синхронизация `CONTROL_AE_TARGET_FPS_RANGE` для превью и записи.

6. **Встроенный Slow-Mo плеер:**
   - Просмотр последнего записанного видео на базе AndroidX Media3 (ExoPlayer).
   - Регулировка скорости воспроизведения (0.125x, 0.25x, 0.5x, 1.0x, 2.0x).

7. **Полная совместимость с Android 14 (API 34):**
   - Работа с MediaStore через Scoped Storage (`Movies/SlowMoCamera`).
   - Поддержка разрешений `READ_MEDIA_VIDEO`, `CAMERA`, `RECORD_AUDIO`.

---

## 📱 Особенности устройств Infinix и MediaTek Helio G99

На некоторых прошивках смартфонов Infinix / Tecno (Transsion) и процессорах MediaTek производитель ограничивает высокоскоростной режим HAL3 для сторонних приложений, открывая 120/240 FPS только стандартному системному приложению камеры.

Приложение включает систему защиты и информирования:
- При запуске проверяются аппаратные возможности и выводится бейдж статуса:
  - 🟢 **High-Speed HAL: Поддерживается** — полный доступ к Constrained High Speed сессиям.
  - 🟠 **High-Speed HAL: Ограничен вендором** — вывод предупреждения с деталями и переключение в режим совместимости.
- Кнопка **«HAL Диагностика»** формирует исчерпывающий отчёт по всем сенсорам устройства.

---

## 🚀 Автоматическая сборка и релизы (GitHub Actions)

В репозитории настроен рабочий процесс `.github/workflows/release.yml`.

### 1. Настройка секретов для подписи (Keystore)
В настройках GitHub: **Settings → Secrets and variables → Actions → New repository secret** добавьте:

| Название секрета | Описание |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Ключ подписи `.jks`, закодированный в Base64 (`base64 -w 0 my-release-key.jks > keystore.b64`) |
| `KEYSTORE_PASSWORD` | Пароль от Keystore |
| `KEY_ALIAS` | Alias ключа |
| `KEY_PASSWORD` | Пароль ключа |

> *Примечание:* Если секреты не заданы, GitHub Actions автоматически соберёт стандартный APK без падения сборки.

### 2. Создание релиза по тегу
Для автоматической сборки APK и публикации в раздел **Releases**:
```bash
git tag -a v1.0.0 -m "Release version 1.0.0"
git push origin v1.0.0
```
GitHub Actions автоматически соберёт APK и прикрепит его к релизу.

---

## 🛠 Локальная сборка проекта

### Требования:
- **JDK:** 17
- **Android SDK:** API 34
- **Gradle:** 8.5+

### Сборка через терминал:
```bash
# Клонирование
git clone https://github.com/Klischa/slow-mo-camera.git
cd slow-mo-camera

# Сборка Debug APK
./gradlew assembleDebug

# Сборка Release APK
./gradlew assembleRelease
```
Файл APK будет доступен в: `app/build/outputs/apk/`
