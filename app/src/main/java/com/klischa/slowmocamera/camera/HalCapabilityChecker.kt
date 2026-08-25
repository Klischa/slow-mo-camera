package com.klischa.slowmocamera.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Range
import android.util.Size
import com.klischa.slowmocamera.data.HighSpeedProfile

/**
 * Класс проверки и диагностики аппаратных возможностей Camera2 HAL.
 */
class HalCapabilityChecker(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    data class CameraHalInfo(
        val cameraId: String,
        val facing: String,
        val hardwareLevel: String,
        val isHighSpeedSupported: Boolean,
        val highSpeedProfiles: List<HighSpeedProfile>,
        val standardFpsRanges: List<Range<Int>>,
        val sensorSize: String?,
        val notes: List<String>
    )

    data class SystemDiagnosticReport(
        val deviceModel: String,
        val manufacturer: String,
        val hardware: String,
        val socBoard: String,
        val androidVersion: String,
        val sdkInt: Int,
        val cameras: List<CameraHalInfo>
    )

    fun getDiagnosticReport(): SystemDiagnosticReport {
        val cameraInfos = mutableListOf<CameraHalInfo>()

        for (id in cameraManager.cameraIdList) {
            try {
                val chars = cameraManager.getCameraCharacteristics(id)
                cameraInfos.add(inspectCamera(id, chars))
            } catch (e: Exception) {
                cameraInfos.add(
                    CameraHalInfo(
                        cameraId = id,
                        facing = "Unknown",
                        hardwareLevel = "Error: ${e.message}",
                        isHighSpeedSupported = false,
                        highSpeedProfiles = emptyList(),
                        standardFpsRanges = emptyList(),
                        sensorSize = null,
                        notes = listOf("Ошибка доступа к характеристикам камеры $id")
                    )
                )
            }
        }

        return SystemDiagnosticReport(
            deviceModel = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            hardware = Build.HARDWARE,
            socBoard = Build.BOARD,
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            cameras = cameraInfos
        )
    }

    fun getSupportedProfilesForCamera(cameraId: String): List<HighSpeedProfile> {
        return try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            inspectHighSpeedProfiles(characteristics)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun isHighSpeedConstrainedSupported(cameraId: String): Boolean {
        return try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)
        } catch (e: Exception) {
            false
        }
    }

    private fun inspectCamera(id: String, chars: CameraCharacteristics): CameraHalInfo {
        val facingInt = chars.get(CameraCharacteristics.LENS_FACING)
        val facing = when (facingInt) {
            CameraCharacteristics.LENS_FACING_BACK -> "Задняя (Back)"
            CameraCharacteristics.LENS_FACING_FRONT -> "Фронтальная (Front)"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "Внешняя (External)"
            else -> "Неизвестно"
        }

        val hwLevelInt = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        val hwLevel = when (hwLevelInt) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3 (Максимальный, RAW + YUV Reprocess)"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL (Полная поддержка Camera2)"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED (Ограниченный HAL)"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY (Устаревший Camera1 эмулятор)"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL (USB/UVC)"
            else -> "Неизвестный уровень ($hwLevelInt)"
        }

        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val hasHighSpeed = capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val profiles = inspectHighSpeedProfiles(chars)
        val standardFps = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: emptyList()

        val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val sensorSizeStr = sensorRect?.let { "${it.width()} x ${it.height()} px" }

        val notes = mutableListOf<String>()
        if (Build.MANUFACTURER.contains("Infinix", ignoreCase = true) ||
            Build.HARDWARE.contains("mt", ignoreCase = true) ||
            Build.BOARD.contains("mt", ignoreCase = true)
        ) {
            if (!hasHighSpeed) {
                notes.add("MediaTek / Transsion HAL: Высокоскоростной режим CONSTRAINED_HIGH_SPEED_VIDEO не открыт вендором для сторонних приложений (доступен только системной камере).")
            } else {
                notes.add("MediaTek HAL: Высокоскоростной режим заявлен в Camera2.")
            }
        }

        if (profiles.isEmpty() && hasHighSpeed) {
            notes.add("Камера заявила поддержку High-Speed, но не предоставила поддерживаемых размеров StreamConfigurationMap.")
        }

        return CameraHalInfo(
            cameraId = id,
            facing = facing,
            hardwareLevel = hwLevel,
            isHighSpeedSupported = hasHighSpeed,
            highSpeedProfiles = profiles,
            standardFpsRanges = standardFps,
            sensorSize = sensorSizeStr,
            notes = notes
        )
    }

    private fun inspectHighSpeedProfiles(chars: CameraCharacteristics): List<HighSpeedProfile> {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        val highSpeedSizes = try {
            map.highSpeedVideoSizes ?: emptyArray()
        } catch (e: Exception) {
            emptyArray()
        }

        val profileList = mutableListOf<HighSpeedProfile>()

        for (size in highSpeedSizes) {
            val ranges = try {
                map.getHighSpeedVideoFpsRangesFor(size) ?: emptyArray()
            } catch (e: Exception) {
                emptyArray()
            }

            for (range in ranges) {
                // Включаем диапазоны с фиксированной высокой частотой (120, 240 fps)
                if (range.upper >= 120 && range.lower == range.upper) {
                    profileList.add(
                        HighSpeedProfile(
                            size = size,
                            fpsRange = range,
                            isConstrainedSupported = true
                        )
                    )
                }
            }
        }

        // Если профили не найдены, создаем стандартные фолбэки для 720p/1080p если поддерживаются
        if (profileList.isEmpty()) {
            val standardRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
            val maxRange = standardRanges.maxByOrNull { it.upper } ?: Range(30, 30)
            val size720 = Size(1280, 720)
            profileList.add(
                HighSpeedProfile(
                    size = size720,
                    fpsRange = maxRange,
                    isConstrainedSupported = false
                )
            )
        }

        // Сортировка: сначала 240fps, затем 120fps, затем большее разрешение
        return profileList.distinctBy { "${it.size.width}x${it.size.height}@${it.fps}" }
            .sortedWith(
                compareByDescending<HighSpeedProfile> { it.fps }
                    .thenByDescending { it.size.width * it.size.height }
            )
    }
}
