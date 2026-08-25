package com.klischa.slowmocamera.ai.interpolation

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * Мониторинг термального состояния чипсета Helio G99 и GPU Mali-G57 MP2.
 * Предотвращает перегрев и термальный троттлинг при тяжёлом нейросетевом инференсе.
 */
class ThermalMonitor(private val context: Context) {

    private val tag = "ThermalMonitor"
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    enum class ThermalStatus(val label: String, val isSafeForAi: Boolean) {
        NORMAL("Нормальная", true),
        LIGHT("Умеренный нагрев", true),
        MODERATE("Повышенный нагрев", true),
        SEVERE("Критический нагрев (Троттлинг)", false),
        CRITICAL("Перегрев! Остановка инференса", false),
        EMERGENCY("Аварийное охлаждение", false),
        SHUTDOWN("Отключение", false),
        UNKNOWN("Неизвестно", true)
    }

    fun getCurrentThermalStatus(): ThermalStatus {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            return when (powerManager.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> ThermalStatus.NORMAL
                PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.LIGHT
                PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.MODERATE
                PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.SEVERE
                PowerManager.THERMAL_STATUS_CRITICAL -> ThermalStatus.CRITICAL
                PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalStatus.EMERGENCY
                PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalStatus.SHUTDOWN
                else -> ThermalStatus.UNKNOWN
            }
        }
        return ThermalStatus.NORMAL
    }

    fun isSafeToRunNeuralInference(): Boolean {
        val status = getCurrentThermalStatus()
        Log.d(tag, "Текущее термальное состояние: ${status.label}")
        return status.isSafeForAi
    }
}
