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

import com.google.common.truth.Truth.assertThat
import com.health.openscale.R
import com.health.openscale.core.bluetooth.BluetoothEvent.UserInteractionType
import com.health.openscale.core.bluetooth.ScaleCatalog.device
import com.health.openscale.core.bluetooth.ScaleCatalog.hex
import com.health.openscale.core.bluetooth.ScaleCatalog.uuid16
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.Percent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.UUID

/**
 * Drives the BF990 handler through the frame sequences the scale sends.
 *
 * Frame layouts are the ones verified on a BF990 (firmware V02.13) in
 * github.com/ewanold/beurer-bf990 docs/BF990-protocol.md: 2A9D flags 0E / 15 bytes,
 * 2A9C flags 0398 / 14 bytes. The measured values are synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BeurerBf990HandlerTest {

    private val svcCustom = uuid16(0xFFFF)
    private val svcUserData = uuid16(0x181C)
    private val chrWeight = uuid16(0x2A9D)
    private val chrBody = uuid16(0x2A9C)
    private val chrUcp = uuid16(0x2A9F)
    private val chrUserList = uuid16(0xFFF1)
    private val chrActivity = uuid16(0xFFF2)
    private val chrTakeMeasurement = uuid16(0xFFF4)
    private val chrFff5 = uuid16(0xFFF5)
    private val chrFff7 = uuid16(0xFFF7)
    private val chrFff8 = uuid16(0xFFF8)
    private val svcDisplay = uuid16(0xFFCF)
    private val chrFactoryReset = uuid16(0xFFC2)

    // ---- Detection ---------------------------------------------------------------------------

    @Test
    fun `claims the BF990 by its advertised name only`() {
        val handler = BeurerBf990Handler()
        val support = handler.supportFor(device("BF990"))

        assertThat(support).isNotNull()
        assertThat(support!!.displayName).isEqualTo("Beurer BF990")
        assertThat(support.implemented).contains(DeviceCapability.HISTORY_READ)
        assertThat(support.linkMode).isEqualTo(LinkMode.CONNECT_GATT)
        listOf("BF980", "BF950", "BF1000", "BF99").forEach {
            assertThat(handler.supportFor(device(it))).isNull()
        }
    }

    // ---- Reading assembly --------------------------------------------------------------------

    @Test
    fun `a complete frame set publishes one measurement without waiting for disconnect`() {
        val s = setup()
        s.sendReading(Synthetic.reading)

        assertThat(s.callbacks.published).hasSize(1)
        val m = s.callbacks.published.single()
        assertThat(m.takenAt()).isEqualTo(listOf(2026, 11, 25, 8, 12, 30))
        assertThat(m[MeasurementType.WEIGHT]!!.value).isWithin(0.001f).of(64.3f)
        assertThat(m[MeasurementType.BODY_FAT]!!.value).isWithin(0.01f).of(31.2f)
        assertThat(m[MeasurementType.MUSCLE]!!.value).isWithin(0.01f).of(33.4f)
        assertThat(m[MeasurementType.WATER]!!.value).isWithin(0.01f).of(46.81f)
        assertThat(m[MeasurementType.IMPEDANCE]!!.value).isWithin(0.01f).of(512.0f)
        assertThat(m[MeasurementType.LBM]!!.value).isWithin(0.01f).of(44.24f)
        assertThat(m[MeasurementType.BONE]!!.value).isWithin(0.01f).of(3.24f)

        s.handler.handleDisconnected()
        assertThat(s.callbacks.published).hasSize(1)
    }

    @Test
    fun `the analysis frames add heart rate, visceral fat, segments and the scale's own masses`() {
        val s = setup()
        s.sendReading(
            Synthetic.reading,
            Triple(
                hex("0E 48 00 55 00 3D A8 16 B8 1F E0 01 E4 20 B8 06 2C 1F F6 01"),
                hex("36 01 31 01 56 01 52 01 23 01 48 00 49 00 15 00 16 00 D7 00"),
                ByteArray(6),
            ),
        )

        val m = s.callbacks.published.single()
        assertThat(m[MeasurementType.HEART_RATE]!!.value).isEqualTo(72)
        assertThat(m[MeasurementType.VISCERAL_FAT]!!).isWithin(1e-3f).of(8.5f)
        // The scale's figures replace the estimate derived from soft lean mass (44.24 / 3.24).
        assertThat(m[MeasurementType.LBM]!!.value).isWithin(1e-3f).of(42.1f)
        assertThat(m[MeasurementType.BONE]!!.value).isWithin(1e-3f).of(2.4f)
        assertThat(m[MeasurementType.PROTEIN]!!.value).isWithin(0.01f).of(8.6f / 64.3f * 100f)
        // Water stays the 2A9C value, converted from mass exactly once.
        assertThat(m[MeasurementType.WATER]!!.value).isWithin(0.01f).of(46.81f)

        val byIdentity = m.values.mapKeys { it.key.identity }
        assertThat(byIdentity["ble.metabolic_age"]).isEqualTo(61)
        assertThat((byIdentity["ble.total_muscle"] as Percent).value).isWithin(0.01f).of(39.9f / 64.3f * 100f)
        val segments = byIdentity.filterKeys { it.startsWith("ble.segmental.") }.mapValues { (it.value as Percent).value }
        val expected = mapOf(
                "ble.segmental.fat.left_leg" to 31.0f, "ble.segmental.fat.right_leg" to 30.5f,
                "ble.segmental.fat.left_arm" to 34.2f, "ble.segmental.fat.right_arm" to 33.8f,
                "ble.segmental.fat.torso" to 29.1f,
                "ble.segmental.muscle.left_leg" to 7.2f, "ble.segmental.muscle.right_leg" to 7.3f,
                "ble.segmental.muscle.left_arm" to 2.1f, "ble.segmental.muscle.right_arm" to 2.2f,
                "ble.segmental.muscle.torso" to 21.5f,
        )
        assertThat(segments.keys.sorted()).isEqualTo(expected.keys.sorted())
        expected.forEach { (identity, percent) -> assertThat(segments.getValue(identity)).isWithin(1e-3f).of(percent) }
    }

    @Test
    fun `stored readings replayed back to back stay separate measurements`() {
        val s = setup()
        s.sendReading(Synthetic.reading.copy(second = 1))
        s.sendReading(Synthetic.reading.copy(second = 2, weightKg = 64.0f))
        s.sendReading(Synthetic.reading.copy(second = 3, weightKg = 63.8f))

        assertThat(s.callbacks.published.map { it.takenAt()[5] }).containsExactly(1, 2, 3).inOrder()
        listOf(64.3f, 64.0f, 63.8f).forEachIndexed { i, kg ->
            assertThat(s.callbacks.published[i][MeasurementType.WEIGHT]!!.value).isWithin(0.001f).of(kg)
        }
    }

    @Test
    fun `a partial reading is published when the next one starts or the scale reports done`() {
        val s = setup()
        s.handler.handleNotification(chrWeight, Synthetic.reading.weightFrame())
        s.handler.handleNotification(chrBody, Synthetic.reading.bodyFrame())
        s.handler.handleNotification(chrWeight, Synthetic.reading.copy(second = 40).weightFrame())
        assertThat(s.callbacks.published).hasSize(1)

        s.handler.handleNotification(chrTakeMeasurement, hex("01"))
        assertThat(s.callbacks.published).hasSize(2)
        assertThat(s.callbacks.published[1].takenAt()[5]).isEqualTo(40)
    }

    @Test
    fun `a weight-only reading without foot contact keeps the weight`() {
        val s = setup()
        s.handler.handleNotification(chrWeight, Synthetic.reading.weightFrame())
        s.handler.handleNotification(chrBody, hex("98 03 00 00 A8 16 00 00 00 00 00 00 00 00"))
        s.handler.handleDisconnected()

        val m = s.callbacks.published.single()
        assertThat(m[MeasurementType.WEIGHT]!!.value).isWithin(0.001f).of(64.3f)
        assertThat(MeasurementType.BODY_FAT in m).isFalse()
        assertThat(MeasurementType.WATER in m).isFalse()
    }

    @Test
    fun `readings of another scale slot are dropped with all their frames`() {
        val s = setup()
        s.sendReading(Synthetic.reading.copy(slot = 2, second = 10))
        s.sendReading(Synthetic.reading.copy(slot = 1, second = 20))
        s.handler.handleDisconnected()

        assertThat(s.callbacks.published.map { it.takenAt()[5] }).containsExactly(20)
    }

    @Test
    fun `frames without an opening weight frame are not attached to the next reading`() {
        val s = setup()
        s.handler.handleNotification(chrBody, Synthetic.reading.bodyFrame())
        s.handler.handleNotification(chrWeight, Synthetic.reading.weightFrame())
        s.handler.handleDisconnected()

        val m = s.callbacks.published.single()
        assertThat(MeasurementType.BODY_FAT in m).isFalse()
    }

    @Test
    fun `truncated frames are dropped without breaking the reading`() {
        val s = setup()
        s.handler.handleNotification(chrWeight, Synthetic.reading.weightFrame().copyOf(9))
        assertThat(s.callbacks.published).isEmpty()

        s.handler.handleNotification(chrWeight, Synthetic.reading.weightFrame())
        s.handler.handleNotification(chrBody, Synthetic.reading.bodyFrame().copyOf(10))
        s.handler.handleNotification(chrBody, Synthetic.reading.bodyFrame())
        s.handler.handleDisconnected()

        val m = s.callbacks.published.single()
        assertThat(m[MeasurementType.BODY_FAT]!!.value).isWithin(0.01f).of(31.2f)
    }

    // ---- Connection flow ---------------------------------------------------------------------

    @Test
    fun `subscribes every measurement frame before sending the consent`() {
        val s = setup()
        s.handler.handleConnected(s.user)

        val consentAt = s.transport.ops.indexOfFirst { it is Op.Write && it.chr == chrUcp }
        assertThat(consentAt).isAtLeast(0)
        listOf(chrFff5, chrFff7, chrFff8, chrTakeMeasurement, chrWeight, chrBody).forEach { chr ->
            val subscribedAt = s.transport.ops.indexOfFirst { it is Op.Notify && it.chr == chr }
            assertThat(subscribedAt).isAtLeast(0)
            assertThat(subscribedAt).isLessThan(consentAt)
        }
        assertThat((s.transport.ops[consentAt] as Op.Write).payload.toList())
            .isEqualTo(hex("02 01 D2 04").toList())
    }

    @Test
    fun `after consent the measurement is started on FFF4`() {
        val s = setup()
        s.handler.handleConnected(s.user)
        s.handler.handleNotification(chrUcp, hex("20 02 01"))

        assertThat(s.transport.writesTo(chrTakeMeasurement)).containsExactly(listOf<Byte>(0x00))
    }

    @Test
    fun `the scale's acknowledgement of the request tells the user to step on, once`() {
        val s = setup()
        s.handler.handleConnected(s.user)
        s.handler.handleNotification(chrUcp, hex("20 02 01"))
        assertThat(s.callbacks.infos).doesNotContain(R.string.bt_info_scale_ready)

        s.handler.handleNotification(chrTakeMeasurement, hex("01"))
        assertThat(s.callbacks.infos.count { it == R.string.bt_info_scale_ready }).isEqualTo(1)

        // The next `01` closes a weigh-in instead of announcing readiness again.
        s.handler.handleNotification(chrWeight, Synthetic.reading.weightFrame())
        s.handler.handleNotification(chrTakeMeasurement, hex("01"))
        assertThat(s.callbacks.published).hasSize(1)
        assertThat(s.callbacks.infos.count { it == R.string.bt_info_scale_ready }).isEqualTo(1)
    }

    @Test
    fun `the acknowledgement does not cut a reading that is still arriving`() {
        val s = setup()
        s.handler.handleConnected(s.user)
        s.handler.handleNotification(chrUcp, hex("20 02 01"))
        s.handler.handleNotification(chrWeight, Synthetic.reading.weightFrame())
        s.handler.handleNotification(chrTakeMeasurement, hex("01"))
        assertThat(s.callbacks.published).isEmpty()

        s.handler.handleNotification(chrBody, Synthetic.reading.bodyFrame())
        s.handler.handleDisconnected()
        assertThat(s.callbacks.published.single()[MeasurementType.BODY_FAT]!!.value).isWithin(0.01f).of(31.2f)
    }

    @Test
    fun `an unlinked phone lists the scale users over FFF1 and offers them`() {
        val s = setup(linked = false)
        s.handler.handleConnected(s.user)

        assertThat(s.transport.writesTo(chrUserList)).containsExactly(listOf<Byte>(0x00))
        assertThat(s.transport.writesTo(chrUcp)).isEmpty() // no UDS LIST_ALL_USERS

        s.handler.handleNotification(chrUserList, hex("00 02 4D 4F 4D C1 07 04 0C A5 00 01 03"))
        s.handler.handleNotification(chrUserList, hex("00 01 46 4F 4F BA 07 01 02 B4 00 00 02"))
        s.handler.handleNotification(chrUserList, hex("01"))

        val (type, data) = s.callbacks.interactions.single()
        assertThat(type).isEqualTo(UserInteractionType.CHOOSE_USER)
        @Suppress("UNCHECKED_CAST")
        val ids = (data as Pair<Array<CharSequence>, IntArray>).second
        assertThat(ids.toList()).containsExactly(1, 2, -1).inOrder()
    }

    @Test
    fun `an empty scale offers only to create a scale user`() {
        val s = setup(linked = false)
        s.handler.handleConnected(s.user)
        s.handler.handleNotification(chrUserList, hex("02"))

        @Suppress("UNCHECKED_CAST")
        val ids = (s.callbacks.interactions.single().second as Pair<Array<CharSequence>, IntArray>).second
        assertThat(ids.toList()).containsExactly(-1)
    }

    @Test
    fun `a new registration writes the profile and the activity level`() {
        val s = setup(linked = false)
        s.handler.handleConnected(s.user)
        kotlinx.coroutines.runBlocking {
            s.handler.onUserInteractionFeedback(UserInteractionType.CHOOSE_USER, s.user.id, -1)
        }
        val register = s.transport.writesTo(chrUcp).single()
        assertThat(register[0]).isEqualTo(0x01.toByte())

        s.handler.handleNotification(chrUcp, byteArrayOf(0x20, 0x01, 0x01, 0x03))
        s.handler.handleNotification(chrUcp, hex("20 02 01"))

        assertThat(s.transport.writesTo(uuid16(0x2A8E))).containsExactly(listOf<Byte>(0xA5.toByte(), 0x00))
        assertThat(s.transport.writesTo(uuid16(0x2A8C))).containsExactly(listOf<Byte>(0x01))
        assertThat(s.transport.writesTo(chrActivity)).containsExactly(listOf<Byte>(0x03))
        assertThat(s.transport.writesTo(chrTakeMeasurement)).hasSize(1)
    }

    @Test
    fun `a profile change is pushed to the scale once at the next consent`() {
        val s = setup()
        s.handler.handleConnected(s.user)
        s.handler.handleNotification(chrUcp, hex("20 02 01"))
        assertThat(s.transport.writesTo(uuid16(0x2A8E))).hasSize(1) // first sync after linking

        s.handler.handleNotification(chrUcp, hex("20 02 01"))
        assertThat(s.transport.writesTo(uuid16(0x2A8E))).hasSize(1) // unchanged: nothing written

        s.user.bodyHeight = 166f
        s.handler.handleNotification(chrUcp, hex("20 02 01"))
        assertThat(s.transport.writesTo(uuid16(0x2A8E)).last()).isEqualTo(listOf<Byte>(0xA6.toByte(), 0x00))
    }

    // ---- Maintenance -------------------------------------------------------------------------

    @Test
    fun `delete my user runs after consent and forgets the link`() {
        val s = setup()
        s.settings.putInt("bf990/pendingAction", 1)
        s.handler.handleConnected(s.user)
        s.handler.handleNotification(chrUcp, hex("20 02 01"))

        assertThat(s.transport.writesTo(chrUcp).last()).isEqualTo(listOf<Byte>(0x03))
        assertThat(s.transport.writesTo(chrTakeMeasurement)).isEmpty()

        s.handler.handleNotification(chrUcp, hex("20 03 01"))
        assertThat(s.settings.getInt("userMap/userIdByIndex/1", -1)).isEqualTo(-1)
        assertThat(s.settings.getInt("userMap/consentByIndex/1", -1)).isEqualTo(-1)
        assertThat(s.settings.getInt("bf990/pendingAction", 0)).isEqualTo(0)
        assertThat(s.transport.disconnects).isEqualTo(1)
    }

    @Test
    fun `factory reset writes only the reset command and forgets every link`() {
        val s = setup()
        s.settings.putInt("bf990/pendingAction", 2)
        s.handler.handleConnected(s.user)

        val writes = s.transport.ops.filterIsInstance<Op.Write>()
        assertThat(writes.map { it.svc to it.chr }).containsExactly(svcDisplay to chrFactoryReset)
        assertThat(writes.single().payload.toList()).containsExactly(0x00.toByte())
        assertThat(s.settings.getInt("userMap/userIdByIndex/1", -1)).isEqualTo(-1)
        assertThat(s.settings.getInt("bf990/pendingAction", 0)).isEqualTo(0)
        // An immediate disconnect would cancel the queued write; the scale ends the link.
        assertThat(s.transport.disconnects).isEqualTo(0)
    }

    @Test
    fun `factory reset is not attempted where the reset characteristic is missing`() {
        val s = setup(available = { svc, chr -> !(svc == svcDisplay && chr == chrFactoryReset) })
        s.settings.putInt("bf990/pendingAction", 2)
        s.handler.handleConnected(s.user)

        assertThat(s.transport.ops.filterIsInstance<Op.Write>()).isEmpty()
        assertThat(s.settings.getInt("userMap/userIdByIndex/1", -1)).isEqualTo(1)
        assertThat(s.transport.disconnects).isEqualTo(1)
    }

    // ---- Fixtures ----------------------------------------------------------------------------

    private data class Synthetic(
        val slot: Int = 1,
        val second: Int = 30,
        val weightKg: Float = 64.3f,
    ) {
        fun weightFrame(): ByteArray = bytes(0x0E) + u16(weightKg / 0.005f) + u16(2026f) +
            bytes(11, 25, 8, 12, second, slot) + u16(236f) + u16(1650f)

        fun bodyFrame(): ByteArray = bytes(0x98, 0x03) + u16(312f) + u16(5800f) + u16(334f) +
            u16(41.0f / 0.005f) + u16(30.1f / 0.005f) + u16(5120f)

        companion object {
            val reading = Synthetic()
            private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
            private fun u16(v: Float): ByteArray {
                val raw = Math.round(v)
                return bytes(raw and 0xFF, (raw shr 8) and 0xFF)
            }
        }
    }

    /** FFF5/FFF7/FFF8 laid out as the scale sends them when no body analysis ran (synthetic). */
    private val noAnalysis = Triple(
        hex("0E FF FF 00 00 00 A8 16 1A 1A 00 00 00 00 00 00 00 00 00 00"), ByteArray(20), ByteArray(6),
    )

    private fun Setup.sendReading(r: Synthetic, extras: Triple<ByteArray, ByteArray, ByteArray> = noAnalysis) {
        handler.handleNotification(chrWeight, r.weightFrame())
        handler.handleNotification(chrBody, r.bodyFrame())
        handler.handleNotification(chrFff5, extras.first)
        handler.handleNotification(chrFff7, extras.second)
        handler.handleNotification(chrFff8, extras.third)
    }

    private class Setup(
        val handler: BeurerBf990Handler,
        val transport: RecordingTransport,
        val callbacks: RecordingCallbacks,
        val settings: InMemorySettings,
        val user: ScaleUser,
    )

    private fun setup(
        linked: Boolean = true,
        available: (UUID, UUID) -> Boolean = { _, _ -> true },
    ): Setup {
        val user = ScaleUser(
            id = 1,
            userName = "Mom",
            birthday = Calendar.getInstance().apply { clear(); set(1958, Calendar.MARCH, 14) }.time,
            bodyHeight = 165f,
            gender = GenderType.FEMALE,
            activityLevel = ActivityLevel.MODERATE,
        )
        val settings = InMemorySettings()
        if (linked) {
            settings.putInt("userMap/userIdByIndex/1", user.id)
            settings.putInt("userMap/consentByIndex/1", 1234)
        }
        val handler = BeurerBf990Handler()
        assertThat(handler.supportFor(device("BF990"))).isNotNull()
        val transport = RecordingTransport(available)
        val callbacks = RecordingCallbacks()
        handler.attach(transport, callbacks, settings, FixedDataProvider(user), CoroutineScope(Dispatchers.Unconfined))
        return Setup(handler, transport, callbacks, settings, user)
    }

    private sealed interface Op {
        data class Notify(val svc: UUID, val chr: UUID) : Op
        class Write(val svc: UUID, val chr: UUID, val payload: ByteArray) : Op
    }

    private class RecordingTransport(private val available: (UUID, UUID) -> Boolean) : ScaleDeviceHandler.Transport {
        val ops = mutableListOf<Op>()
        var disconnects = 0
        override fun setNotifyOn(service: UUID, characteristic: UUID) { ops += Op.Notify(service, characteristic) }
        override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) {
            ops += Op.Write(service, characteristic, payload.copyOf())
        }
        override fun read(service: UUID, characteristic: UUID) = Unit
        override fun disconnect() { disconnects++ }
        override fun hasCharacteristic(service: UUID, characteristic: UUID) = available(service, characteristic)
        fun writesTo(chr: UUID): List<List<Byte>> =
            ops.filterIsInstance<Op.Write>().filter { it.chr == chr }.map { it.payload.toList() }
    }

    private class RecordingCallbacks : ScaleDeviceHandler.Callbacks {
        val published = mutableListOf<ScaleMeasurement>()
        val interactions = mutableListOf<Pair<UserInteractionType, Any?>>()
        val infos = mutableListOf<Int>()
        override fun onInfo(resId: Int, vararg args: Any) { infos += resId }
        override fun onPublish(measurement: ScaleMeasurement) { published += measurement.snapshot() }
        override fun onUserInteractionRequired(interactionType: UserInteractionType, data: Any?) {
            interactions += interactionType to data
        }
        override fun resolveString(resId: Int, vararg args: Any) = "res:$resId"
    }

    private class InMemorySettings : ScaleDeviceHandler.DriverSettings {
        private val ints = mutableMapOf<String, Int>()
        private val strings = mutableMapOf<String, String>()
        override fun getInt(key: String, default: Int) = ints[key] ?: default
        override fun putInt(key: String, value: Int) { ints[key] = value }
        override fun getString(key: String, default: String?) = strings[key] ?: default
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun remove(key: String) { ints.remove(key); strings.remove(key) }
    }

    private class FixedDataProvider(private val user: ScaleUser) : ScaleDeviceHandler.DataProvider {
        override fun currentUser() = user
        override fun usersForDevice() = listOf(user)
        override fun lastMeasurementFor(userId: Int): ScaleMeasurement? = null
    }

    private fun ScaleMeasurement.takenAt(): List<Int> {
        val cal = Calendar.getInstance().apply { time = requireNotNull(dateTime) }
        return listOf(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND),
        )
    }
}
