package com.klischa.slowmocamera.stabilization

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.klischa.slowmocamera.R
import com.klischa.slowmocamera.databinding.SheetStabilizationSettingsBinding

class StabilizationBottomSheet(
    private val params: StabilizationParams,
    private val onParamsChanged: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: SheetStabilizationSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetStabilizationSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.switchHardwareStab.isChecked = params.isHardwarePreviewStabilizationEnabled
        binding.switchHardwareStab.setOnCheckedChangeListener { _, isChecked ->
            params.isHardwarePreviewStabilizationEnabled = isChecked
            onParamsChanged()
        }

        when (params.mode) {
            StabilizationMode.AUTO -> binding.chipStabAuto.isChecked = true
            StabilizationMode.OPENCV_TRACKING -> binding.chipStabOpenCv.isChecked = true
            StabilizationMode.FFMPEG_VIDSTAB -> binding.chipStabVidStab.isChecked = true
            StabilizationMode.GYROFLOW -> binding.chipStabGyroflow.isChecked = true
            else -> binding.chipStabAuto.isChecked = true
        }

        binding.chipGroupStabMode.setOnCheckedStateChangeListener { _, checkedIds ->
            params.mode = when {
                checkedIds.contains(R.id.chipStabOpenCv) -> StabilizationMode.OPENCV_TRACKING
                checkedIds.contains(R.id.chipStabVidStab) -> StabilizationMode.FFMPEG_VIDSTAB
                checkedIds.contains(R.id.chipStabGyroflow) -> StabilizationMode.GYROFLOW
                else -> StabilizationMode.AUTO
            }
            onParamsChanged()
        }

        binding.sliderSmoothing.value = params.smoothing.toFloat()
        binding.sliderSmoothing.addOnChangeListener { _, value, _ ->
            params.smoothing = value.toInt()
            binding.tvSmoothingLabel.text = "Степень сглаживания (Smoothing): ${params.smoothing}"
            onParamsChanged()
        }

        binding.sliderShakiness.value = params.shakiness.toFloat()
        binding.sliderShakiness.addOnChangeListener { _, value, _ ->
            params.shakiness = value.toInt()
            binding.tvShakinessLabel.text = "Уровень тряски (Shakiness): ${params.shakiness}"
            onParamsChanged()
        }

        binding.btnCloseStab.setOnClickListener {
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "StabilizationBottomSheet"
    }
}
