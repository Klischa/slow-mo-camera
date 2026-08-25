package com.klischa.slowmocamera.ai.interpolation

/**
 * Режимы работы нейросетевого AI-сглаживания.
 */
enum class InterpolationMode(val displayName: String, val description: String) {
    QUALITY(
        displayName = "Качество (RIFE Neural Network)",
        description = "Нейросетевая интерполяция RIFE v4.6 с аппаратным ускорением GPU Vulkan (до 720p на Mali-G57)."
    ),
    SPEED(
        displayName = "Скорость (Fast Flow)",
        description = "Ускоренная интерполяция по векторам движения для быстрой обработки длинных видео."
    ),
    AUTO(
        displayName = "Авто (Адаптивный)",
        description = "Автоматический выбор движка на основе разрешения видео и температуры чипсета Helio G99."
    )
}
