package com.klischa.slowmocamera.data

/**
 * Режим работы камеры: высокоскоростное видео (Slow-Mo) или фотосъемка.
 */
enum class CaptureMode(val displayName: String) {
    SLOW_MO_VIDEO("SLOW-MO"),
    PHOTO("ФОТО")
}
