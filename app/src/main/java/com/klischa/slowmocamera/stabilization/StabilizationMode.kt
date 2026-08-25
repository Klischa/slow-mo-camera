package com.klischa.slowmocamera.stabilization

/**
 * Доступные режимы стабилизации видео.
 */
enum class StabilizationMode(val displayName: String, val description: String) {
    AUTO(
        displayName = "Авто (Рекомендуемый)",
        description = "Автоматический выбор наилучшего метода стабилизации для вашего устройства."
    ),
    HARDWARE_OIS_EIS(
        displayName = "Аппаратная (OIS / EIS)",
        description = "Встроенная оптическая/электронная стабилизация сенсора Camera2 в реальном времени при съёмке."
    ),
    OPENCV_TRACKING(
        displayName = "Постобработка (OpenCV Tracking)",
        description = "Анализ траектории движения, трекинг оптических точек и сглаживание смещений каждого кадра."
    ),
    FFMPEG_VIDSTAB(
        displayName = "Постобработка (FFmpeg + vid.stab)",
        description = "Двухпроходная глубокая стабилизация с фильтрацией тряски и умной компенсацией краев."
    ),
    GYROFLOW(
        displayName = "Гироскопическая (Gyroflow / IMU)",
        description = "Стабилизация на основе синхронных данных гироскопа и акселерометра смартфона."
    )
}
