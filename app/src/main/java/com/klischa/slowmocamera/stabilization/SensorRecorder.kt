package com.klischa.slowmocamera.stabilization

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter

/**
 * Синхронный регистратор данных гироскопа и акселерометра во время видеосъёмки.
 * Генерирует файл телеметрии для гироскопической стабилизации (Gyroflow).
 */
class SensorRecorder(private val context: Context) : SensorEventListener {

    private val tag = "SensorRecorder"
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    data class ImuSample(
        val timestampNs: Long,
        val gyroX: Float,
        val gyroY: Float,
        val gyroZ: Float,
        val accelX: Float,
        val accelY: Float,
        val accelZ: Float
    )

    private val samples = mutableListOf<ImuSample>()
    private var isRecording = false
    private var recordStartTimestampNs = 0L

    private var latestGyroX = 0f
    private var latestGyroY = 0f
    private var latestGyroZ = 0f

    private var latestAccelX = 0f
    private var latestAccelY = 0f
    private var latestAccelZ = 0f

    fun startRecording() {
        if (sensorManager == null || gyroscope == null) {
            Log.w(tag, "Гироскоп недоступен на данном устройстве")
            return
        }

        samples.clear()
        recordStartTimestampNs = SystemClock.elapsedRealtimeNanos()
        isRecording = true

        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_FASTEST)
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        Log.i(tag, "Запись телеметрии гироскопа начата (SENSOR_DELAY_FASTEST)")
    }

    fun stopRecording(targetTelemetryFile: File): File? {
        if (!isRecording) return null
        isRecording = false
        sensorManager?.unregisterListener(this)

        return try {
            PrintWriter(FileOutputStream(targetTelemetryFile)).use { writer ->
                writer.println("timestamp_ms,gyro_x,gyro_y,gyro_z,accel_x,accel_y,accel_z")
                for (s in samples) {
                    val relMs = (s.timestampNs - recordStartTimestampNs) / 1_000_000L
                    writer.println("$relMs,${s.gyroX},${s.gyroY},${s.gyroZ},${s.accelX},${s.accelY},${s.accelZ}")
                }
            }
            Log.i(tag, "Сохранено ${samples.size} семплов телеметрии в ${targetTelemetryFile.name}")
            targetTelemetryFile
        } catch (e: Exception) {
            Log.e(tag, "Ошибка сохранения телеметрии: ${e.message}")
            null
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isRecording || event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                latestGyroX = event.values[0]
                latestGyroY = event.values[1]
                latestGyroZ = event.values[2]

                samples.add(
                    ImuSample(
                        timestampNs = event.timestamp,
                        gyroX = latestGyroX,
                        gyroY = latestGyroY,
                        gyroZ = latestGyroZ,
                        accelX = latestAccelX,
                        accelY = latestAccelY,
                        accelZ = latestAccelZ
                    )
                )
            }
            Sensor.TYPE_ACCELEROMETER -> {
                latestAccelX = event.values[0]
                latestAccelY = event.values[1]
                latestAccelZ = event.values[2]
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
