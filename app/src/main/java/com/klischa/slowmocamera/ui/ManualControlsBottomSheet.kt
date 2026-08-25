package com.klischa.slowmocamera.ui

import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.klischa.slowmocamera.R
import com.klischa.slowmocamera.camera.CameraManualControls
import com.klischa.slowmocamera.databinding.SheetManualControlsBinding

class ManualControlsBottomSheet(
    private val manualControls: CameraManualControls,
    private val onControlsUpdated: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: SheetManualControlsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetManualControlsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupIsoControls()
        setupShutterControls()
        setupFocusControls()
        setupWbControls()

        binding.btnResetAuto.setOnClickListener {
            manualControls.isAutoIso = true
            manualControls.isAutoExposure = true
            manualControls.isAutoFocus = true
            manualControls.whiteBalanceMode = CaptureRequest.CONTROL_AWB_MODE_AUTO
            onControlsUpdated()
            dismiss()
        }
    }

    private fun setupIsoControls() {
        binding.switchAutoIso.isChecked = manualControls.isAutoIso
        binding.sliderIso.isEnabled = !manualControls.isAutoIso

        binding.switchAutoIso.setOnCheckedChangeListener { _, isChecked ->
            manualControls.isAutoIso = isChecked
            binding.sliderIso.isEnabled = !isChecked
            binding.tvIsoLabel.text = if (isChecked) "Чувствительность (ISO): Авто" else "Чувствительность (ISO): ${manualControls.manualIso}"
            onControlsUpdated()
        }

        binding.sliderIso.addOnChangeListener { _, value, _ ->
            manualControls.manualIso = value.toInt()
            binding.tvIsoLabel.text = "Чувствительность (ISO): ${manualControls.manualIso}"
            onControlsUpdated()
        }
    }

    private fun setupShutterControls() {
        binding.switchAutoShutter.isChecked = manualControls.isAutoExposure
        binding.sliderShutter.isEnabled = !manualControls.isAutoExposure

        binding.switchAutoShutter.setOnCheckedChangeListener { _, isChecked ->
            manualControls.isAutoExposure = isChecked
            binding.sliderShutter.isEnabled = !isChecked
            binding.tvShutterLabel.text = if (isChecked) "Выдержка (Shutter): Авто" else "Выдержка: 1/${binding.sliderShutter.value.toInt()}s"
            onControlsUpdated()
        }

        binding.sliderShutter.addOnChangeListener { _, value, _ ->
            val denominator = value.toInt()
            manualControls.manualExposureTimeNs = 1_000_000_000L / denominator
            binding.tvShutterLabel.text = "Выдержка: 1/${denominator}s"
            onControlsUpdated()
        }
    }

    private fun setupFocusControls() {
        binding.switchAutoFocus.isChecked = manualControls.isAutoFocus
        binding.sliderFocus.isEnabled = !manualControls.isAutoFocus

        binding.switchAutoFocus.setOnCheckedChangeListener { _, isChecked ->
            manualControls.isAutoFocus = isChecked
            binding.sliderFocus.isEnabled = !isChecked
            binding.tvFocusLabel.text = if (isChecked) "Фокус: Автофокус" else "Фокус: Ручной (${String.format(java.util.Locale.US, "%.1f", manualControls.manualFocusDistance)})"
            onControlsUpdated()
        }

        binding.sliderFocus.addOnChangeListener { _, value, _ ->
            manualControls.manualFocusDistance = value
            binding.tvFocusLabel.text = "Фокус: Ручной (${String.format(java.util.Locale.US, "%.1f", value)})"
            onControlsUpdated()
        }
    }

    private fun setupWbControls() {
        when (manualControls.whiteBalanceMode) {
            CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT -> binding.chipWbDaylight.isChecked = true
            CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> binding.chipWbCloudy.isChecked = true
            CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT -> binding.chipWbIncandescent.isChecked = true
            CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT -> binding.chipWbFluorescent.isChecked = true
            else -> binding.chipWbAuto.isChecked = true
        }

        binding.chipGroupWb.setOnCheckedStateChangeListener { _, checkedIds ->
            manualControls.whiteBalanceMode = when {
                checkedIds.contains(R.id.chipWbDaylight) -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
                checkedIds.contains(R.id.chipWbCloudy) -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
                checkedIds.contains(R.id.chipWbIncandescent) -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
                checkedIds.contains(R.id.chipWbFluorescent) -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
                else -> CaptureRequest.CONTROL_AWB_MODE_AUTO
            }
            onControlsUpdated()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ManualControlsBottomSheet"
    }
}
