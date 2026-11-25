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

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.bluetooth.ScaleCatalog.hex
import com.health.openscale.core.bluetooth.libs.BeurerBf990Lib.UserListEvent
import org.junit.Test

/**
 * Tests for the BF990 FFF1 user-list decoder. The 12-byte entry is the BF450 capture of
 * ha-beurer-scales §9.2 (anonymised there); the 13-byte entry is the same profile in the
 * Wi-Fi model layout, whose height is a little-endian uint16.
 */
class BeurerBf990LibTest {

    @Test
    fun `decodes the 13-byte Wi-Fi model user entry`() {
        val event = BeurerBf990Lib.parseUserListEvent(hex("00 02 4D 4F 4D C1 07 04 0C A5 00 01 03"))

        assertThat(event).isEqualTo(
            UserListEvent.Entry(
                BeurerBf990Lib.UserListEntry(
                    index = 2, initials = "MOM", birthYear = 1985, birthMonth = 4, birthDay = 12,
                    heightCm = 165, isFemale = true, activityLevel = 3,
                )
            )
        )
    }

    @Test
    fun `decodes the 12-byte entry and reads FF initials as none`() {
        val event = BeurerBf990Lib.parseUserListEvent(hex("00 01 FF FF FF C1 07 04 0C B4 00 03"))

        val entry = (event as UserListEvent.Entry).user
        assertThat(entry.index).isEqualTo(1)
        assertThat(entry.initials).isNull()
        assertThat(entry.heightCm).isEqualTo(180)
        assertThat(entry.isFemale).isFalse()
        assertThat(entry.activityLevel).isEqualTo(3)
    }

    @Test
    fun `recognises the list terminators`() {
        assertThat(BeurerBf990Lib.parseUserListEvent(hex("01"))).isEqualTo(UserListEvent.Complete)
        assertThat(BeurerBf990Lib.parseUserListEvent(hex("02"))).isEqualTo(UserListEvent.Empty)
    }

    @Test
    fun `rejects truncated and implausible entries instead of guessing`() {
        listOf(
            "03",                                    // unknown status
            "00 01 FF FF FF C1 07 04",               // truncated
            "00 00 FF FF FF C1 07 04 0C A5 00 01 03", // slot 0 does not exist
            "00 01 FF FF FF C1 07 0D 0C A5 00 01 03", // month 13
            "00 01 FF FF FF C1 07 04 0C FF FF 01 03", // height 65535 cm
        ).forEach { frame ->
            assertThat(BeurerBf990Lib.parseUserListEvent(hex(frame)))
                .isInstanceOf(UserListEvent.Unrecognised::class.java)
        }
    }

    // ---- Measurement frames ------------------------------------------------------------------

    @Test
    fun `a weigh-in without impedance carries only the profile-based energy figures`() {
        // Layout of a weigh-in without foot or hand contact (synthetic values): only BMR and
        // AMR, which the scale derives from the profile, are filled in.
        val a = BeurerBf990Lib.parseBodyAnalysis(hex("0E FF FF 00 00 00 A8 16 1A 1A 00 00 00 00 00 00 00 00 00 00"))!!

        assertThat(a.pulseBpm).isNull()
        assertThat(a.visceralFat).isNull()
        assertThat(a.metabolicAgeYears).isNull()
        assertThat(a.bmrKcal!!).isWithin(0.1f).of(1385.3f)  // 5800 kJ
        assertThat(a.amrKcal!!).isWithin(0.1f).of(1596.0f)  // 6682 kJ
        assertThat(a.boneKg).isNull()
        assertThat(a.leanBodyMassKg).isNull()
        assertThat(a.proteinKg).isNull()
        assertThat(a.totalMuscleKg).isNull()
        assertThat(a.waterPercent).isNull()
        assertThat(BeurerBf990Lib.parseSegmentalAnalysis(ByteArray(20))).isNull()
        assertThat(BeurerBf990Lib.isUnrecognisedUser(ByteArray(6))).isEqualTo(false)
    }

    @Test
    fun `decodes the full FFF5 analysis`() {
        // pulse 72, visceral 8.5, age 61, BMR 5800 kJ, AMR 8120 kJ, bone 2.4 kg, LBM 42.1 kg,
        // protein 8.6 kg, total muscle 39.9 kg, water 50.2 %
        val a = BeurerBf990Lib.parseBodyAnalysis(hex("0E 48 00 55 00 3D A8 16 B8 1F E0 01 E4 20 B8 06 2C 1F F6 01"))!!

        assertThat(a.pulseBpm).isEqualTo(72)
        assertThat(a.visceralFat!!).isWithin(1e-3f).of(8.5f)
        assertThat(a.metabolicAgeYears).isEqualTo(61)
        assertThat(a.bmrKcal!!).isWithin(0.1f).of(1385.3f)
        assertThat(a.amrKcal!!).isWithin(0.1f).of(1939.4f)
        assertThat(a.boneKg!!).isWithin(1e-3f).of(2.4f)
        assertThat(a.leanBodyMassKg!!).isWithin(1e-3f).of(42.1f)
        assertThat(a.proteinKg!!).isWithin(1e-3f).of(8.6f)
        assertThat(a.totalMuscleKg!!).isWithin(1e-3f).of(39.9f)
        assertThat(a.waterPercent!!).isWithin(1e-3f).of(50.2f)
    }

    @Test
    fun `decodes the FFF7 segments in the order the scale sends them`() {
        // fat: left leg 31.0, right leg 30.5, left arm 34.2, right arm 33.8, torso 29.1
        // muscle: left leg 7.2, right leg 7.3, left arm 2.1, right arm 2.2, torso 21.5
        val seg = BeurerBf990Lib.parseSegmentalAnalysis(hex("36 01 31 01 56 01 52 01 23 01 48 00 49 00 15 00 16 00 D7 00"))!!

        assertThat(seg.fat).isEqualTo(BeurerBf990Lib.Segments(leftArm = 34.2f, rightArm = 33.8f, torso = 29.1f, leftLeg = 31.0f, rightLeg = 30.5f))
        assertThat(seg.muscle.leftLeg!!).isWithin(1e-3f).of(7.2f)
        assertThat(seg.muscle.rightLeg!!).isWithin(1e-3f).of(7.3f)
        assertThat(seg.muscle.leftArm!!).isWithin(1e-3f).of(2.1f)
        assertThat(seg.muscle.rightArm!!).isWithin(1e-3f).of(2.2f)
        assertThat(seg.muscle.torso!!).isWithin(1e-3f).of(21.5f)
    }

    @Test
    fun `flags a reading the scale could not assign and rejects odd frame sizes`() {
        assertThat(BeurerBf990Lib.isUnrecognisedUser(hex("00 00 00 00 01 00"))).isEqualTo(true)
        assertThat(BeurerBf990Lib.isUnrecognisedUser(hex("00 00"))).isNull()
        assertThat(BeurerBf990Lib.parseBodyAnalysis(ByteArray(19))).isNull()
        assertThat(BeurerBf990Lib.parseSegmentalAnalysis(ByteArray(21))).isNull()
    }
}
