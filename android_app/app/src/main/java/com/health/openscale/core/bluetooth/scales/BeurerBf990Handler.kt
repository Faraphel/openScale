/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.bluetooth.scales

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.BeurerBf990Lib
import com.health.openscale.core.bluetooth.libs.BeurerBf990Lib.UserListEvent
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.Bpm
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeIcon
import com.health.openscale.core.data.Percent
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.UUID

/**
 * Beurer BF990 (Wi-Fi/Bluetooth 8-electrode diagnostic scale, firmware V02.13 observed).
 *
 * The scale is a member of Beurer's UDS-based "BF600" family: weight (2A9D) and body
 * composition (2A9C) use the Bluetooth SIG profiles, users are registered and selected
 * through the User Data Service with a consent code, and Beurer-private characteristics on
 * service FFFF carry the user list (FFF1), activity level (FFF2), measurement start/status
 * (FFF4) and three analysis frames: FFF5 (pulse, visceral fat, metabolic age, bone, lean
 * mass, protein, total muscle), FFF7 (segmental fat and muscle) and FFF8 (unrecognised-user
 * flag). Their layouts are decoded in [BeurerBf990Lib].
 *
 * One reading arrives as 2A9D → 2A9C → FFF5/FFF7/FFF8; stored readings are replayed in that
 * order right after consent. The frames are assembled here into one measurement, published
 * once the set is complete, when the next reading starts, when the scale reports the
 * measurement finished (FFF4 `01`), after a quiet period, or on disconnect, whichever is
 * first. Readings of another scale slot are never published.
 *
 * Protocol sources: live BF990 observations (firmware V02.09 and V02.13), the Beurer
 * HealthManager Pro 1.22.1 measurement listener, github.com/ewanold/beurer-bf990 and
 * github.com/PixelShober/ha-beurer-scales.
 */
class BeurerBf990Handler : StandardWeightProfileHandler() {

    // ---- GATT --------------------------------------------------------------------------------

    private val svcCustom = uuid16(0xFFFF)
    private val chrUserList = uuid16(0xFFF1)
    private val chrActivity = uuid16(0xFFF2)
    private val chrTakeMeasurement = uuid16(0xFFF4)
    private val chrExtraA = uuid16(0xFFF5)
    private val chrExtraB = uuid16(0xFFF7)
    private val chrExtraC = uuid16(0xFFF8)
    private val extraFrames = setOf(chrExtraA, chrExtraB, chrExtraC)

    private val svcDisplaySettings = uuid16(0xFFCF)
    private val chrFactoryReset = uuid16(0xFFC2)

    private val svcUserData = uuid16(0x181C)
    private val chrWeight = uuid16(0x2A9D)
    private val chrBodyComposition = uuid16(0x2A9C)

    // ---- Reading assembly --------------------------------------------------------------------

    /** The frames of one weigh-in, keyed by the weight frame that opened it. */
    private class Reading(val weight: ScaleMeasurement) {
        var body: ScaleMeasurement? = null
        val extras = LinkedHashMap<UUID, ByteArray>()
    }

    private val lock = Any()
    private var openReading: Reading? = null
    /** True while the frames of a reading that belongs to another scale slot are arriving. */
    private var discardingForeignReading = false
    private var quietFlushJob: Job? = null
    /** Set while the scale has not yet acknowledged our FFF4 measurement request. */
    private var measurementRequestPending = false

    // ---- User list ---------------------------------------------------------------------------

    private val listedUsers = linkedMapOf<Int, ScaleUser>()
    private var userListAnswered = true
    private var userListTimeoutJob: Job? = null

    // ---- Device detection --------------------------------------------------------------------

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        if (!device.name.contains("bf990", ignoreCase = true)) return null
        val capabilities = setOf(
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.TIME_SYNC,
            DeviceCapability.USER_SYNC,
            DeviceCapability.HISTORY_READ,
            DeviceCapability.BATTERY_LEVEL,
        )
        return DeviceSupport(
            displayName = "Beurer BF990",
            capabilities = capabilities,
            implemented = capabilities,
            linkMode = LinkMode.CONNECT_GATT,
        )
    }

    // ---- Connection --------------------------------------------------------------------------

    override fun onConnected(user: ScaleUser) {
        if (pendingAction() == ACTION_FACTORY_RESET) {
            performFactoryReset()
            return
        }

        // Stored readings follow the consent response within a fraction of a second, so every
        // measurement characteristic is subscribed before the base sends the consent.
        extraFrames.forEach { setNotifyOn(svcCustom, it) }
        setNotifyOn(svcCustom, chrTakeMeasurement)
        setNotifyOn(svcCustom, chrUserList)

        super.onConnected(user)
    }

    override fun onDisconnected() {
        measurementRequestPending = false
        synchronized(lock) {
            quietFlushJob?.cancel()
            flushOpenReading("disconnect")
        }
        userListTimeoutJob?.cancel()
        super.onDisconnected()
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        when (characteristic) {
            chrWeight -> onWeightFrame(data)
            chrBodyComposition -> onBodyFrame(data)
            in extraFrames -> onExtraFrame(characteristic, data)
            chrTakeMeasurement -> onMeasurementStatus(data)
            chrUserList -> onUserListEvent(BeurerBf990Lib.parseUserListEvent(data), user)
            CHR_USER_CONTROL_POINT ->
                if (isDeleteUserResponse(data)) onDeleteUserResponse(data)
                else super.onNotification(characteristic, data, user)
            else -> super.onNotification(characteristic, data, user)
        }
    }

    /** Runs after a successful consent (and after the profile writes of a new registration). */
    override fun onRequestMeasurement() {
        if (pendingAction() == ACTION_DELETE_USER) {
            logI("Deleting the consented scale user as requested")
            writeTo(svcUserData, CHR_USER_CONTROL_POINT, byteArrayOf(UCP_DELETE_USER_DATA.toByte()))
            return
        }

        val user = currentAppUser()
        val index = findKnownScaleIndexForAppUser(user.id)
        if (index != null && settingsGetString(profileKey(index)) != profileFingerprint(user)) {
            logI("Profile of appUserId=${user.id} changed since the last sync, updating scale slot $index")
            writeUserDataToScale()
        }

        measurementRequestPending = true
        writeTo(svcCustom, chrTakeMeasurement, byteArrayOf(0x00))
    }

    override fun writeUserDataToScale() {
        super.writeUserDataToScale()
        val user = currentAppUser()
        writeTo(svcCustom, chrActivity, byteArrayOf(beurerActivityCode(user.activityLevel).toByte()))
        findKnownScaleIndexForAppUser(user.id)?.let { settingsPutString(profileKey(it), profileFingerprint(user)) }
    }

    // ---- Frames ------------------------------------------------------------------------------

    private fun onWeightFrame(data: ByteArray) {
        logD("BF990 2A9D ${data.toHexPreview(32)}")
        if (data.size < weightFrameLength(data)) {
            logW("Truncated weight frame (${data.size} bytes), dropped")
            return
        }
        val weight = parseWeightToMeasurement(data) ?: return
        synchronized(lock) {
            flushOpenReading("next reading")
            val slot = scaleUserIndexOf(data)
            val owner = slot?.let { loadUserIdForScaleIndex(it) }
            if (slot != null && owner != currentAppUser().id) {
                logW("Reading of scale slot $slot (app user $owner) is not for appUserId=${currentAppUser().id}, dropped")
                discardingForeignReading = true
                return
            }
            discardingForeignReading = false
            openReading = Reading(weight)
            armQuietFlush()
        }
    }

    private fun onBodyFrame(data: ByteArray) {
        logD("BF990 2A9C ${data.toHexPreview(32)}")
        synchronized(lock) {
            if (discardingForeignReading) return
            val reading = openReading ?: run {
                logW("Body composition without a weight frame, dropped")
                return
            }
            if (reading.body != null) {
                logW("Second body composition frame for one reading, ignored")
                return
            }
            if (data.size < bodyFrameLength(data)) {
                logW("Truncated body composition frame (${data.size} bytes), dropped")
                return
            }
            reading.body = parseBodyCompToMeasurement(data, reading.weight[MeasurementType.WEIGHT]?.value)
            publishIfComplete(reading)
        }
    }

    private fun onExtraFrame(characteristic: UUID, data: ByteArray) {
        // Logged in full: these frames are what a future decoder is checked against.
        logD("BF990 ${characteristic.shortId()} len=${data.size} ${data.toHexPreview(32)}")
        synchronized(lock) {
            if (discardingForeignReading) return
            val reading = openReading ?: run {
                logW("${characteristic.shortId()} frame without a weight frame, dropped")
                return
            }
            if (reading.extras.putIfAbsent(characteristic, data.copyOf()) != null) {
                logW("Second ${characteristic.shortId()} frame for one reading, ignored")
                return
            }
            publishIfComplete(reading)
        }
    }

    private fun onMeasurementStatus(data: ByteArray) {
        val status = data.firstOrNull()?.toInt()?.and(0xFF) ?: return
        // The scale answers our request with `01` once it will give the next weigh-in to the
        // consented user, and sends `01` again whenever a weigh-in is complete.
        if (measurementRequestPending) {
            measurementRequestPending = false
            if (status == 0x01) {
                logI("FFF4: scale ready for the consented user's weigh-in")
                userInfo(R.string.bt_info_scale_ready)
                return
            }
        }
        when (status) {
            0x01 -> synchronized(lock) { flushOpenReading("measurement finished") }
            0x02 -> logW("FFF4: measurement timed out on the scale")
            0x03 -> logW("FFF4: measurement failed on the scale")
            0x04 -> logW("FFF4: no scale user selected")
            else -> logD("FFF4: status 0x${status.toString(16)}")
        }
    }

    /** Caller holds [lock]. */
    private fun publishIfComplete(reading: Reading) {
        if (reading.body != null && reading.extras.keys.containsAll(extraFrames)) {
            flushOpenReading("all frames received")
        } else {
            armQuietFlush()
        }
    }

    /** Caller holds [lock]. */
    private fun flushOpenReading(reason: String) {
        val reading = openReading ?: return
        openReading = null
        quietFlushJob?.cancel()

        val measurement = reading.weight.snapshot()
        reading.body?.let { measurement.mergeWith(it) }
        if (reading.extras.keys != extraFrames) {
            logD("Reading published without ${(extraFrames - reading.extras.keys).joinToString { it.shortId() }}")
        }
        val published = transformBeforePublish(measurement)
        applyAnalysisFrames(published, reading.extras)
        logI("Publishing BF990 reading ($reason): $published")
        publish(published)
    }

    /**
     * Adds what the private frames carry. The scale's own bone and lean-mass figures replace
     * the base's estimate from soft lean mass; masses the app stores as a share of body
     * weight (protein, total muscle) are converted with the reading's weight.
     */
    private fun applyAnalysisFrames(m: ScaleMeasurement, extras: Map<UUID, ByteArray>) {
        val weightKg = m[MeasurementType.WEIGHT]?.value?.takeIf { it > 0f }
        fun share(kg: Float?): Percent? = if (kg != null && weightKg != null) Percent(kg / weightKg * 100f) else null

        extras[chrExtraA]?.let { BeurerBf990Lib.parseBodyAnalysis(it) }?.let { a ->
            logD("BF990 analysis: $a")
            a.pulseBpm?.let { m[MeasurementType.HEART_RATE] = Bpm(it) }
            a.visceralFat?.let { m[MeasurementType.VISCERAL_FAT] = it }
            a.metabolicAgeYears?.let { m[METABOLIC_AGE] = it }
            a.boneKg?.let { m[MeasurementType.BONE] = Kg(it) }
            a.leanBodyMassKg?.let { m[MeasurementType.LBM] = Kg(it) }
            share(a.proteinKg)?.let { m[MeasurementType.PROTEIN] = it }
            share(a.totalMuscleKg)?.let { m[TOTAL_MUSCLE] = it }
            if (MeasurementType.WATER !in m) a.waterPercent?.let { m[MeasurementType.WATER] = Percent(it) }
        }

        extras[chrExtraB]?.let { BeurerBf990Lib.parseSegmentalAnalysis(it) }?.let { seg ->
            logD("BF990 segments: $seg")
            fun put(key: MeasurementType.Key<Percent>, value: Float?) { value?.let { m[key] = Percent(it) } }
            put(SegmentalTypes.FAT_LEFT_ARM, seg.fat.leftArm)
            put(SegmentalTypes.FAT_RIGHT_ARM, seg.fat.rightArm)
            put(SegmentalTypes.FAT_TORSO, seg.fat.torso)
            put(SegmentalTypes.FAT_LEFT_LEG, seg.fat.leftLeg)
            put(SegmentalTypes.FAT_RIGHT_LEG, seg.fat.rightLeg)
            put(SegmentalTypes.MUSCLE_LEFT_ARM, seg.muscle.leftArm)
            put(SegmentalTypes.MUSCLE_RIGHT_ARM, seg.muscle.rightArm)
            put(SegmentalTypes.MUSCLE_TORSO, seg.muscle.torso)
            put(SegmentalTypes.MUSCLE_LEFT_LEG, seg.muscle.leftLeg)
            put(SegmentalTypes.MUSCLE_RIGHT_LEG, seg.muscle.rightLeg)
        }

        extras[chrExtraC]?.let { BeurerBf990Lib.isUnrecognisedUser(it) }?.takeIf { it }?.let {
            logW("The scale did not recognise who stood on it; this reading was stored under the consented slot")
        }
    }

    private fun armQuietFlush() {
        quietFlushJob?.cancel()
        val reading = openReading ?: return
        quietFlushJob = scope.launch {
            delay(QUIET_FLUSH_MS)
            synchronized(lock) {
                if (openReading === reading) flushOpenReading("quiet period")
            }
        }
    }

    /** Bytes a Weight Measurement (2A9D) must have for the fields its flags announce. */
    private fun weightFrameLength(data: ByteArray): Int {
        if (data.isEmpty()) return 1
        val flags = data[0].toInt() and 0xFF
        return 3 + (if (flags and 0x02 != 0) 7 else 0) + (if (flags and 0x04 != 0) 1 else 0) +
            (if (flags and 0x08 != 0) 4 else 0)
    }

    /** Bytes a Body Composition Measurement (2A9C) must have for the fields its flags announce. */
    private fun bodyFrameLength(data: ByteArray): Int {
        if (data.size < 2) return 4
        val flags = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        val optionalUint16Fields = (3..11).count { bit -> flags and (1 shl bit) != 0 }
        return 4 + (if (flags and 0x02 != 0) 7 else 0) + (if (flags and 0x04 != 0) 1 else 0) +
            2 * optionalUint16Fields
    }

    /** The scale slot a Weight Measurement names, or null when its flags carry none. */
    private fun scaleUserIndexOf(data: ByteArray): Int? {
        if (data.isEmpty()) return null
        val flags = data[0].toInt() and 0xFF
        if (flags and 0x04 == 0) return null
        val offset = 3 + if (flags and 0x02 != 0) 7 else 0
        return if (offset < data.size) data[offset].toInt() and 0xFF else null
    }

    // ---- User list (FFF1) --------------------------------------------------------------------

    override fun requestScaleUserList() {
        listedUsers.clear()
        userListAnswered = false
        writeTo(svcCustom, chrUserList, byteArrayOf(0x00))

        userListTimeoutJob?.cancel()
        userListTimeoutJob = scope.launch {
            delay(USER_LIST_TIMEOUT_MS)
            if (!userListAnswered) {
                logW("FFF1 user list did not complete, offering to create a scale user")
                answerUserList(emptyList())
            }
        }
    }

    private fun onUserListEvent(event: UserListEvent, user: ScaleUser) {
        when (event) {
            is UserListEvent.Entry -> {
                val e = event.user
                listedUsers[e.index] = ScaleUser(
                    id = e.index,
                    userName = e.initials ?: "U%d".format(e.index),
                    birthday = GregorianCalendar(e.birthYear, e.birthMonth - 1, e.birthDay).time,
                    bodyHeight = e.heightCm.toFloat(),
                    gender = if (e.isFemale) GenderType.FEMALE else GenderType.MALE,
                    activityLevel = ActivityLevel.fromInt((e.activityLevel - 1).coerceIn(0, 4)),
                )
                logD("FFF1 user: $e")
            }
            UserListEvent.Complete -> answerUserList(listedUsers.values.sortedBy { it.id })
            UserListEvent.Empty -> {
                findKnownScaleIndexForAppUser(user.id)?.let { forgetScaleUser(it) }
                answerUserList(emptyList())
            }
            is UserListEvent.Unrecognised -> logW("FFF1: unrecognised ${event.data.toHexPreview(32)}")
        }
    }

    private fun answerUserList(users: List<ScaleUser>) {
        synchronized(lock) {
            if (userListAnswered) return
            userListAnswered = true
            userListTimeoutJob?.cancel()
        }

        val index = findKnownScaleIndexForAppUser(currentAppUser().id)
        if (index != null && loadConsentForScaleIndex(index) != -1) return

        if (users.isEmpty()) presentCreateOnlyChoice() else presentChooseFromUsers(users)
    }

    // ---- Maintenance: delete user / factory reset --------------------------------------------

    private fun isDeleteUserResponse(data: ByteArray): Boolean =
        data.size >= 3 && (data[0].toInt() and 0xFF) == UCP_RESPONSE && (data[1].toInt() and 0xFF) == UCP_DELETE_USER_DATA

    private fun onDeleteUserResponse(data: ByteArray) {
        val result = data[2].toInt() and 0xFF
        setPendingAction(ACTION_NONE)
        if (result == UCP_SUCCESS) {
            findKnownScaleIndexForAppUser(currentAppUser().id)?.let { forgetScaleUser(it) }
            userInfo(R.string.beurer_bf990_info_user_deleted)
        } else {
            userWarn(R.string.beurer_bf990_warn_user_delete_failed, result)
        }
        requestDisconnect()
    }

    private fun performFactoryReset() {
        setPendingAction(ACTION_NONE)
        if (!hasCharacteristic(svcDisplaySettings, chrFactoryReset)) {
            userError(R.string.beurer_bf990_error_factory_reset_unavailable)
            requestDisconnect()
            return
        }
        logI("Factory reset: writing 00 to FFCF/FFC2")
        writeTo(svcDisplaySettings, chrFactoryReset, byteArrayOf(0x00))
        for (index in 0..255) {
            if (loadUserIdForScaleIndex(index) != -1 || loadConsentForScaleIndex(index) != -1) forgetScaleUser(index)
        }
        userInfo(R.string.beurer_bf990_info_factory_reset_sent)
        // The scale ends the link itself once it has wiped its users. A disconnect issued now
        // would cancel the queued write, so only fall back to it if the scale keeps the link.
        scope.launch {
            delay(RESET_DISCONNECT_FALLBACK_MS)
            requestDisconnect()
        }
    }

    private fun forgetScaleUser(index: Int) {
        logD("Forgetting scale slot $index")
        saveUserIdForScaleIndex(index, -1)
        saveConsentForScaleIndex(index, -1)
        settingsPutString(profileKey(index), "")
    }

    private fun pendingAction(): Int = settingsGetInt(KEY_PENDING_ACTION, ACTION_NONE)

    private fun setPendingAction(action: Int) = settingsPutInt(KEY_PENDING_ACTION, action)

    // ---- Profile sync ------------------------------------------------------------------------

    private fun profileKey(index: Int) = "bf990/profile/$index"

    /** What the scale's body-composition calculation depends on. */
    private fun profileFingerprint(user: ScaleUser): String {
        val birth = Calendar.getInstance().apply { time = user.birthday }
        return listOf(
            birth.get(Calendar.YEAR), birth.get(Calendar.MONTH) + 1, birth.get(Calendar.DAY_OF_MONTH),
            user.bodyHeight.toInt(), if (user.gender.isMale()) 0 else 1, beurerActivityCode(user.activityLevel),
        ).joinToString("|")
    }

    private fun beurerActivityCode(level: ActivityLevel): Int = (level.toInt() + 1).coerceIn(1, 5)

    private fun UUID.shortId(): String = "%04X".format((mostSignificantBits shr 32) and 0xFFFF)

    // ---- Settings UI -------------------------------------------------------------------------

    @Composable
    override fun DeviceConfigurationUi() {
        var pending by remember { mutableIntStateOf(pendingAction()) }

        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Note(stringResource(R.string.beurer_bf990_config_description))
            HorizontalDivider()
            Text(
                stringResource(R.string.beurer_bf990_section_maintenance),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            if (pending == ACTION_NONE) {
                SettingAction(
                    title = stringResource(R.string.beurer_bf990_delete_user),
                    description = stringResource(R.string.beurer_bf990_delete_user_desc),
                    action = stringResource(R.string.beurer_bf990_action_delete),
                ) {
                    pending = ACTION_DELETE_USER
                    setPendingAction(ACTION_DELETE_USER)
                }
                SettingAction(
                    title = stringResource(R.string.beurer_bf990_factory_reset),
                    description = stringResource(R.string.beurer_bf990_factory_reset_desc),
                    action = stringResource(R.string.qardio_action_reset),
                ) {
                    pending = ACTION_FACTORY_RESET
                    setPendingAction(ACTION_FACTORY_RESET)
                }
            } else {
                SettingAction(
                    title = stringResource(
                        if (pending == ACTION_FACTORY_RESET) R.string.beurer_bf990_factory_reset
                        else R.string.beurer_bf990_delete_user
                    ),
                    description = stringResource(R.string.beurer_bf990_action_pending),
                    descriptionColor = MaterialTheme.colorScheme.error,
                    action = stringResource(R.string.cancel_button),
                ) {
                    pending = ACTION_NONE
                    setPendingAction(ACTION_NONE)
                }
            }
        }
    }

    @Composable
    private fun Note(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }

    @Composable
    private fun SettingAction(
        title: String,
        description: String,
        action: String,
        descriptionColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
        onClick: () -> Unit,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Note(description, descriptionColor)
            }
            OutlinedButton(onClick = onClick) { Text(action) }
        }
    }

    private companion object {
        // Same identities as the other scales reporting these, so the history is shared.
        val METABOLIC_AGE = MeasurementType.deviceInt(
            "metabolic_age", R.string.measurement_type_metabolic_age,
            icon = MeasurementTypeIcon.IC_M_TIMER, color = 0xFF795548.toInt()
        )
        val TOTAL_MUSCLE = MeasurementType.devicePercent(
            "total_muscle", R.string.measurement_type_total_muscle,
            icon = MeasurementTypeIcon.IC_M_WORKOUT, color = 0xFF2E7D32.toInt()
        )

        const val KEY_PENDING_ACTION = "bf990/pendingAction"
        const val ACTION_NONE = 0
        const val ACTION_DELETE_USER = 1
        const val ACTION_FACTORY_RESET = 2

        const val UCP_DELETE_USER_DATA = 0x03
        const val UCP_RESPONSE = 0x20
        const val UCP_SUCCESS = 0x01

        /** Frames of one reading arrive well under a second apart; stored ones ~2 s apart. */
        const val QUIET_FLUSH_MS = 5_000L
        const val USER_LIST_TIMEOUT_MS = 8_000L
        const val RESET_DISCONNECT_FALLBACK_MS = 5_000L
    }
}
