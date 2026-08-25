package com.klischa.slowmocamera.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.klischa.slowmocamera.camera.HalCapabilityChecker
import com.klischa.slowmocamera.databinding.DialogHalDiagnosticsBinding

class HalDiagnosticsDialogFragment : DialogFragment() {

    private var _binding: DialogHalDiagnosticsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogHalDiagnosticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val halChecker = HalCapabilityChecker(requireContext())
        val report = halChecker.getDiagnosticReport()

        val summaryText = "${report.manufacturer} ${report.deviceModel} | SoC/Board: ${report.socBoard} (Android ${report.androidVersion})"
        binding.tvDeviceSummary.text = summaryText

        val builder = StringBuilder()
        builder.append("=== СИСТЕМНЫЙ ОТЧЁТ HAL CAMERA2 ===\n\n")
        builder.append("Устройство: ${report.manufacturer} ${report.deviceModel}\n")
        builder.append("Hardware: ${report.hardware} | Board: ${report.socBoard}\n")
        builder.append("Android: ${report.androidVersion} (API ${report.sdkInt})\n\n")

        builder.append("--- КАМЕРЫ УСТРОЙСТВА ---\n")
        for (cam in report.cameras) {
            builder.append("\n[ Камера ID: ${cam.cameraId} (${cam.facing}) ]\n")
            builder.append("• Аппаратный уровень: ${cam.hardwareLevel}\n")
            builder.append("• Сенсор: ${cam.sensorSize ?: "Н/Д"}\n")
            builder.append("• CONSTRAINED_HIGH_SPEED: ${if (cam.isHighSpeedSupported) "ДА (Поддерживается)" else "НЕТ (Ограничено/Отсутствует)"}\n")

            builder.append("• Поддерживаемые High-Speed профили:\n")
            if (cam.highSpeedProfiles.isNotEmpty()) {
                cam.highSpeedProfiles.forEach { p ->
                    builder.append("   - ${p.size.width}x${p.size.height} @ ${p.fps} fps (Диапазон: ${p.fpsRange})\n")
                }
            } else {
                builder.append("   - Нет доступных высокоскоростных профилей\n")
            }

            builder.append("• Стандартные FPS диапазоны:\n")
            cam.standardFpsRanges.forEach { r ->
                builder.append("   - [${r.lower}, ${r.upper}]\n")
            }

            if (cam.notes.isNotEmpty()) {
                builder.append("• Примечания вендора:\n")
                cam.notes.forEach { n ->
                    builder.append("   ! $n\n")
                }
            }
        }

        val reportString = builder.toString()
        binding.tvReportText.text = reportString

        binding.btnCopyReport.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Camera2 HAL Report", reportString)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), "Отчёт скопирован в буфер обмена", Toast.LENGTH_SHORT).show()
        }

        binding.btnCloseDialog.setOnClickListener {
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "HalDiagnosticsDialog"
        fun newInstance(): HalDiagnosticsDialogFragment = HalDiagnosticsDialogFragment()
    }
}
