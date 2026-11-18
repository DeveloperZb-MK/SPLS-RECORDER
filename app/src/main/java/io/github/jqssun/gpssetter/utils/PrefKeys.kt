package io.github.jqssun.gpssetter.utils

/** Preference keys shared by the Android app settings and SPLS recorder. */
object PrefKeys {
    const val START = "start"
    const val LATITUDE = "latitude"
    const val LONGITUDE = "longitude"

    const val GPS_ACC = "gps_accuracy"
    const val GPS_ACC_RAND = "gps_accuracy_rand"
    const val NET_ACC = "network_accuracy"
    const val NET_ACC_RAND = "network_accuracy_rand"
    const val FUSED_ACC = "fused_accuracy"
    const val FUSED_ACC_RAND = "fused_accuracy_rand"
    const val ALT = "altitude"
    const val ALT_RAND = "altitude_rand"
    const val SPEED = "speed_ms"
    const val SPEED_RAND = "speed_ms_rand"
    const val H_ACC = "horizontal_accuracy"
    const val V_ACC = "vertical_accuracy"
    const val MSL = "msl_altitude"
    const val MSL_ACC = "msl_accuracy"
    const val RADIUS = "random_radius"

    // custom interval (hours / minutes / seconds)
    const val INT_H = "random_interval_hours"
    const val INT_M = "random_interval_minutes"
    const val INT_S = "random_interval_seconds"
    // randomize (+/-) of that interval (hours / minutes / seconds)
    const val INT_RH = "random_interval_rand_hours"
    const val INT_RM = "random_interval_rand_minutes"
    const val INT_RS = "random_interval_rand_seconds"

    // second fields (randomize +/-) for horizontal and vertical accuracy
    const val H_ACC_RAND = "horizontal_accuracy_rand"
    const val V_ACC_RAND = "vertical_accuracy_rand"

    // toggles (stored as the text "true" / "false")
    const val GPS_DRIFT = "gps_accuracy_drift"
    const val NET_DRIFT = "network_accuracy_drift"
    const val FUSED_DRIFT = "fused_accuracy_drift"
    const val H_NET_SYNC = "horizontal_network_sync"
    const val V_NET_LINK = "vertical_network_linked"
    const val SPEED_NET_LINK = "speed_network_linked"
    const val ALT_NET_LINK = "altitude_network_linked"
    const val MSL_NET_LINK = "msl_network_linked"

    // SPLS record counters (written by the SPLS recorder)
    const val SPLS_N_GPS = "spls_n_gps"
    const val SPLS_N_NETWORK = "spls_n_network"
    const val SPLS_N_FUSED = "spls_n_fused"

    // SPLS state
    const val SPLS_ENABLED = "spls_enabled" // active recording session
    const val SPLS_FEATURE_ENABLED = "spls_feature_enabled" // user-controlled Enable SPLS recording switch
    const val SPLS_MODEL = "spls_model"
    // samples per accuracy range of each provider (12 numbers, comma separated)
    const val SPLS_BINS_GPS = "spls_bins_gps"
    const val SPLS_BINS_NETWORK = "spls_bins_network"
    const val SPLS_BINS_FUSED = "spls_bins_fused"

    // SPLS recording session and locations
    const val SPLS_PAUSED = "spls_paused"
    const val SPLS_PAUSED_RECORD_IDS = "spls_paused_record_ids"
    const val SPLS_AUTO_RESUME_BLOCKED_IDS = "spls_auto_resume_blocked_ids"
    const val SPLS_STOP_WARNING_HIDDEN = "spls_stop_warning_hidden"
    const val SPLS_READY_MESSAGE_HIDDEN = "spls_ready_message_hidden"
    const val SPLS_ACTIVE_ID = "spls_active_id"
    const val SPLS_SELECT_ON = "spls_select_on"
    const val SPLS_SELECTED_ID = "spls_selected_id"
    const val SPLS_PICK_PENDING = "spls_pick_pending"

    // SPLS learned models of the activated record (JSON text)
    const val SPLS_ACC_MODEL = "spls_acc_model"
    const val SPLS_MOTION_MODEL = "spls_motion_model"

    // "SPLS learned behaviour deployment" toggles of the three accuracy dialogs
    const val NET_LEARNED = "network_accuracy_learned"
    const val GPS_LEARNED = "gps_accuracy_learned"
    const val FUSED_LEARNED = "fused_accuracy_learned"

    // bearing, bearing accuracy, position jitter, position drift
    const val BEARING_MODE = "bearing_mode_fixed" // "true" = Fixed tab; empty = Auto from movement
    const val BEARING_FIXED = "bearing_fixed"
    const val BEARING_LINK = "bearing_spls_linked"
    const val BEARING_ACC = "bearing_accuracy"
    const val BEARING_ACC_RAND = "bearing_accuracy_rand"
    const val BEARING_ACC_LINK = "bearing_accuracy_linked"
    const val JITTER_LINK = "position_jitter_linked"
    const val DRIFT_LINK = "position_drift_linked"

    /** Every linked toggle. They all switch off when no record is activated or the data is not enough. */
    val LINKED_ALL = listOf(
        V_NET_LINK, SPEED_NET_LINK, ALT_NET_LINK, MSL_NET_LINK,
        NET_LEARNED, GPS_LEARNED, FUSED_LEARNED,
        BEARING_LINK, BEARING_ACC_LINK, JITTER_LINK, DRIFT_LINK
    )
}
