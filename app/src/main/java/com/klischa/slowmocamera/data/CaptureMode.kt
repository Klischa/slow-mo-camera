package com.klischa.slowmocamera.data

/**
 * Режимы работы камеры:
 * - SLOW_MO_VIDEO: высокоскоростное замедленное видео
 * - PHOTO: фотосъёмка высокого разрешения
 * - TIMELAPSE: ускоренная интервальная съёмка
 * - PRE_RECORD_BUFFER: непрерывная кольцевая предзапись (буферизация 3-10 сек)
 */
enum class CaptureMode(val displayName: String, val description: String) {
    SLOW_MO_VIDEO(
        displayName = "SLOW-MO",
        description = "Высокоскоростная съёмка 120/240 FPS с переменной скоростью"
    ),
    PHOTO(
        displayName = "ФОТО",
        description = "Полноразмерная фотосъёмка высокого разрешения"
    ),
    TIMELAPSE(
        displayName = "ТАЙМЛАПС",
        description = "Интервальная ускоренная съёмка с настраиваемым шагом кадров"
    ),
    PRE_RECORD_BUFFER(
        displayName = "БУФЕР",
        description = "Запись в прошлое: буферизация последних секунд, сохранение по клику"
    )
}
