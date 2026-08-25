package com.klischa.slowmocamera.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.klischa.slowmocamera.R
import com.klischa.slowmocamera.data.HighSpeedProfile
import com.klischa.slowmocamera.data.OutputFormatType
import com.klischa.slowmocamera.data.RecordingMode
import com.klischa.slowmocamera.databinding.SheetCameraSettingsBinding

class CameraSettingsBottomSheet(
    private var currentMode: RecordingMode,
    private var currentFormat: OutputFormatType,
    private var currentProfile: HighSpeedProfile?,
    private val availableProfiles: List<HighSpeedProfile>,
    private val onConfigChanged: (mode: RecordingMode, format: OutputFormatType, profile: HighSpeedProfile) -> Unit,
    private val onOpenDiagnostics: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: SheetCameraSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetCameraSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupModeChips()
        setupProfileChips()
        setupCodecChips()

        binding.btnCloseSettings.setOnClickListener {
            dismiss()
        }

        binding.btnOpenDiagnostics.setOnClickListener {
            dismiss()
            onOpenDiagnostics()
        }
    }

    private fun setupModeChips() {
        if (currentMode == RecordingMode.HSR) {
            binding.chipModeHsrSheet.isChecked = true
            binding.tvModeDescription.text = getString(R.string.mode_hsr_desc)
        } else {
            binding.chipModeHfrSheet.isChecked = true
            binding.tvModeDescription.text = getString(R.string.mode_hfr_desc)
        }

        binding.chipGroupModeSheet.setOnCheckedStateChangeListener { _, checkedIds ->
            currentMode = if (checkedIds.contains(R.id.chipModeHsrSheet)) {
                binding.tvModeDescription.text = getString(R.string.mode_hsr_desc)
                RecordingMode.HSR
            } else {
                binding.tvModeDescription.text = getString(R.string.mode_hfr_desc)
                RecordingMode.HFR
            }
            notifyChange()
        }
    }

    private fun setupProfileChips() {
        binding.chipGroupProfilesSheet.removeAllViews()

        for (profile in availableProfiles) {
            val chip = Chip(requireContext()).apply {
                text = profile.label
                isCheckable = true
                isChecked = profile == currentProfile
                setOnClickListener {
                    currentProfile = profile
                    notifyChange()
                }
            }
            binding.chipGroupProfilesSheet.addView(chip)
        }
    }

    private fun setupCodecChips() {
        if (currentFormat == OutputFormatType.WEBM_VP9) {
            binding.chipCodecWebmSheet.isChecked = true
        } else {
            binding.chipCodecMp4Sheet.isChecked = true
        }

        binding.chipGroupCodecSheet.setOnCheckedStateChangeListener { _, checkedIds ->
            currentFormat = if (checkedIds.contains(R.id.chipCodecWebmSheet)) {
                OutputFormatType.WEBM_VP9
            } else {
                OutputFormatType.MP4_H264
            }
            notifyChange()
        }
    }

    private fun notifyChange() {
        val profile = currentProfile ?: availableProfiles.firstOrNull() ?: return
        onConfigChanged(currentMode, currentFormat, profile)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "CameraSettingsBottomSheet"
    }
}
