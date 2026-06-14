package com.incident201.poseguard.ui

import androidx.compose.runtime.Composable
import com.incident201.poseguard.R
import com.incident201.poseguard.intiface.IntifaceMessage
import com.incident201.poseguard.intiface.IntifaceUiMessage
import com.incident201.poseguard.viewmodel.AppLanguage

@Composable
internal fun localizedIntifaceMessage(
    language: AppLanguage,
    message: IntifaceUiMessage
): String = when (message.message) {
    IntifaceMessage.OnlineOnly -> localizedString(language, R.string.intiface_online_only)
    IntifaceMessage.Connecting -> localizedString(language, R.string.intiface_connecting)
    IntifaceMessage.Connected -> localizedString(language, R.string.intiface_connected)
    IntifaceMessage.Scanning -> localizedString(language, R.string.intiface_scanning)
    IntifaceMessage.NoVibrateDevices -> localizedString(language, R.string.intiface_no_vibrate_devices)
    IntifaceMessage.FoundDevices -> localizedFormatString(
        language,
        R.string.intiface_found_devices,
        message.args.firstOrNull()?.toIntOrNull() ?: 0
    )
    IntifaceMessage.SelectedDevice -> localizedFormatString(
        language,
        R.string.intiface_selected_device,
        message.args.firstOrNull().orEmpty()
    )
    IntifaceMessage.Disconnected -> localizedString(language, R.string.intiface_disconnected)
    IntifaceMessage.InvalidUrl -> localizedString(language, R.string.intiface_invalid_url)
    IntifaceMessage.ServerError -> localizedString(language, R.string.intiface_server_error)
    IntifaceMessage.UnableToConnect -> localizedString(language, R.string.intiface_unable_to_connect)
    IntifaceMessage.UnableToConnectDetail -> localizedFormatString(
        language,
        R.string.intiface_unable_to_connect_detail,
        message.args.firstOrNull().orEmpty()
    )
    IntifaceMessage.TestVibration -> localizedString(language, R.string.intiface_test_vibration_status)
    IntifaceMessage.TestVibrationDone -> localizedString(language, R.string.intiface_test_vibration_done)
    IntifaceMessage.SelectDeviceFirst -> localizedString(language, R.string.intiface_select_device_first)
    IntifaceMessage.SelectedDeviceMissing -> localizedString(language, R.string.intiface_selected_device_missing)
    IntifaceMessage.NoVibrateCapability -> localizedString(language, R.string.intiface_no_vibrate_capability)
    IntifaceMessage.TestVibrationFailed -> localizedString(language, R.string.intiface_test_vibration_failed)
    IntifaceMessage.TestVibrationFailedDetail -> localizedFormatString(
        language,
        R.string.intiface_test_vibration_failed_detail,
        message.args.firstOrNull().orEmpty()
    )
    IntifaceMessage.ScanRejected -> localizedString(language, R.string.intiface_scan_rejected)
    IntifaceMessage.CommandRejected -> localizedString(language, R.string.intiface_command_rejected)
    IntifaceMessage.CommandRejectedDetail -> localizedFormatString(
        language,
        R.string.intiface_command_rejected_detail,
        message.args.firstOrNull().orEmpty()
    )
    IntifaceMessage.SavedDeviceNotFound -> localizedString(language, R.string.intiface_saved_device_not_found)
}
