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
package com.health.openscale.core.bluetooth.libs

/**
 * Parsers for the Beurer BF990 private packets on service FFFF.
 *
 * Standard Weight Scale (2A9D) and Body Composition (2A9C) packets are decoded by
 * StandardWeightProfileHandler. These functions stay free of Android dependencies so
 * captured packets can be asserted in plain JVM tests.
 */
object BeurerBf990Lib {

    /** One stored scale user from the FFF1 user list. */
    data class UserListEntry(
        val index: Int,
        /** Up to three letters, null when the slot carries none (`FF FF FF`). */
        val initials: String?,
        val birthYear: Int,
        val birthMonth: Int,
        val birthDay: Int,
        val heightCm: Int,
        val isFemale: Boolean,
        /** Beurer activity code, 1 (none) .. 5 (very high). */
        val activityLevel: Int,
    )

    sealed interface UserListEvent {
        data class Entry(val user: UserListEntry) : UserListEvent
        /** `01`: every stored user has been sent. */
        data object Complete : UserListEvent
        /** `02`: the scale stores no user. */
        data object Empty : UserListEvent
        data class Unrecognised(val data: ByteArray) : UserListEvent
    }

    /**
     * Decodes one FFF1 user-list notification.
     *
     * Entries are `00, index, initials[3], year LE, month, day, height…, gender, activity`.
     * The Wi-Fi models (BF880/BF980/BF990) send 13 instead of 12 bytes: their height is a
     * little-endian uint16, which pushes gender and activity one byte further.
     */
    fun parseUserListEvent(data: ByteArray): UserListEvent {
        if (data.size == 1) {
            return when (u8(data, 0)) {
                0x01 -> UserListEvent.Complete
                0x02 -> UserListEvent.Empty
                else -> UserListEvent.Unrecognised(data)
            }
        }
        if (u8(data, 0) != 0x00 || (data.size != 12 && data.size != 13)) {
            return UserListEvent.Unrecognised(data)
        }

        val wide = data.size == 13
        val height = if (wide) u16le(data, 9) else u8(data, 9)
        val genderOffset = if (wide) 11 else 10
        val rawInitials = data.copyOfRange(2, 5)
        val initials = if (rawInitials.all { it == 0xFF.toByte() }) {
            null
        } else {
            String(rawInitials, Charsets.US_ASCII).filter { it.isLetterOrDigit() }.take(3).ifEmpty { null }
        }

        val entry = UserListEntry(
            index = u8(data, 1),
            initials = initials,
            birthYear = u16le(data, 5),
            birthMonth = u8(data, 7),
            birthDay = u8(data, 8),
            heightCm = height,
            isFemale = u8(data, genderOffset) == 1,
            activityLevel = u8(data, genderOffset + 1),
        )
        val plausible = entry.index in 1..MAX_USER_INDEX &&
            entry.birthMonth in 1..12 && entry.birthDay in 1..31 &&
            entry.heightCm in 50..250
        return if (plausible) UserListEvent.Entry(entry) else UserListEvent.Unrecognised(data)
    }

    /** Highest user slot a Beurer BF600-family scale assigns. */
    const val MAX_USER_INDEX = 10

    // ---- Measurement frames ------------------------------------------------------------------
    //
    // Layouts as the Beurer HealthManager Pro app 1.22.1 decodes them for the BF990
    // (BTScaleBF600MeasurementListenerRepoImpl, BTScaleBF600AnalysisHeartRateExtendedModel and
    // BTScaleBF600AnalysisFullBodyModel). Every field is a little-endian int16 unless noted;
    // 0 means "not measured". A value that is not measured is null, never 0.

    /** FFF5, 20 bytes: the analysis the scale computes from the impedance and the profile. */
    data class BodyAnalysis(
        val pulseBpm: Int?,
        /** Beurer visceral-fat rating (dimensionless). */
        val visceralFat: Float?,
        val metabolicAgeYears: Int?,
        val bmrKcal: Float?,
        val amrKcal: Float?,
        val boneKg: Float?,
        val leanBodyMassKg: Float?,
        val proteinKg: Float?,
        val totalMuscleKg: Float?,
        val waterPercent: Float?,
    )

    /** Fat or muscle percentage per body segment. */
    data class Segments(
        val leftArm: Float?,
        val rightArm: Float?,
        val torso: Float?,
        val leftLeg: Float?,
        val rightLeg: Float?,
    )

    /** FFF7, 20 bytes: the 8-electrode segmental analysis. */
    data class SegmentalAnalysis(val fat: Segments, val muscle: Segments)

    /**
     * FFF5: `[0]` unused · `[1..2]` pulse (FF = none) · `[3..4]` visceral fat ×0.1 ·
     * `[5]` metabolic age (uint8, 1..99) · `[6..7]` BMR kJ · `[8..9]` AMR kJ ·
     * `[10..11]` bone · `[12..13]` lean body mass · `[14..15]` protein · `[16..17]` total
     * muscle (all ×0.005 kg) · `[18..19]` body water ×0.1 %.
     */
    fun parseBodyAnalysis(data: ByteArray): BodyAnalysis? {
        if (data.size != 20) return null
        val pulseAbsent = data[1] == 0xFF.toByte() || data[2] == 0xFF.toByte()
        return BodyAnalysis(
            pulseBpm = if (pulseAbsent) null else positive(data, 1)?.takeIf { it in 20..250 },
            visceralFat = positive(data, 3)?.times(0.1f),
            metabolicAgeYears = u8(data, 5).takeIf { it in 1..99 },
            bmrKcal = positive(data, 6)?.div(KJ_PER_KCAL),
            amrKcal = positive(data, 8)?.div(KJ_PER_KCAL),
            boneKg = positive(data, 10)?.times(MASS_RESOLUTION_KG),
            leanBodyMassKg = positive(data, 12)?.times(MASS_RESOLUTION_KG),
            proteinKg = positive(data, 14)?.times(MASS_RESOLUTION_KG),
            totalMuscleKg = positive(data, 16)?.times(MASS_RESOLUTION_KG),
            waterPercent = positive(data, 18)?.times(0.1f),
        )
    }

    /**
     * FFF7, ×0.1 %: fat of left leg, right leg, left arm, right arm, torso, then muscle in
     * the same order. Null when the hand unit was not held (no segmental fat or muscle).
     */
    fun parseSegmentalAnalysis(data: ByteArray): SegmentalAnalysis? {
        if (data.size != 20) return null
        val tenths = List(10) { positive(data, it * 2)?.times(0.1f) }
        if (tenths.subList(0, 5).all { it == null } || tenths.subList(5, 10).all { it == null }) return null
        fun segments(o: Int) = Segments(
            leftLeg = tenths[o], rightLeg = tenths[o + 1], leftArm = tenths[o + 2],
            rightArm = tenths[o + 3], torso = tenths[o + 4],
        )
        return SegmentalAnalysis(fat = segments(0), muscle = segments(5))
    }

    /**
     * FFF8, 6 bytes: true when the scale could not tell which of its users stood on it
     * (`[4..5]` > 0), null for an unexpected frame.
     */
    fun isUnrecognisedUser(data: ByteArray): Boolean? =
        if (data.size == 6) s16le(data, 4) > 0 else null

    private const val KJ_PER_KCAL = 4.1868f
    private const val MASS_RESOLUTION_KG = 0.005f

    private fun positive(data: ByteArray, offset: Int): Int? = s16le(data, offset).takeIf { it > 0 }

    private fun s16le(data: ByteArray, offset: Int): Int = u16le(data, offset).toShort().toInt()

    private fun u8(data: ByteArray, offset: Int): Int =
        data[offset].toInt() and 0xFF

    private fun u16le(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}
