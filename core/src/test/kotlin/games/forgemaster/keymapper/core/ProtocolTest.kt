package games.forgemaster.keymapper.core

import games.forgemaster.keymapper.core.model.ButtonControl
import games.forgemaster.keymapper.core.model.JoystickControl
import games.forgemaster.keymapper.core.model.KmJson
import games.forgemaster.keymapper.core.model.Phase
import games.forgemaster.keymapper.core.model.Profile
import games.forgemaster.keymapper.core.model.Button
import games.forgemaster.keymapper.core.model.sanitized
import games.forgemaster.keymapper.core.protocol.FrameCodec
import games.forgemaster.keymapper.core.protocol.Handshake
import games.forgemaster.keymapper.core.protocol.MsgType
import games.forgemaster.keymapper.core.protocol.ProtocolException
import games.forgemaster.keymapper.core.protocol.SetProfiles
import games.forgemaster.keymapper.core.protocol.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ProtocolTest {
    private val profile = Profile(
        "id1", "Game", listOf(
            Phase("main", listOf(
                ButtonControl("a", 0.2f, 0.3f, listOf(Button.A)),
                JoystickControl("j", 0.1f, 0.8f),
            )),
        ),
    )

    @Test
    fun framesRoundTrip() {
        val buf = ByteArrayOutputStream()
        val msg = SetProfiles(listOf(profile), mapOf("com.game" to "id1"))
        FrameCodec.write(buf, MsgType.SET_PROFILES, Wire.encode(SetProfiles.serializer(), msg))
        FrameCodec.write(buf, MsgType.PING, ByteArray(0))
        val input = ByteArrayInputStream(buf.toByteArray())
        val f1 = FrameCodec.read(input)!!
        assertEquals(MsgType.SET_PROFILES, f1.type)
        assertEquals(msg, Wire.decode(SetProfiles.serializer(), f1))
        assertEquals(MsgType.PING, FrameCodec.read(input)!!.type)
        assertNull(FrameCodec.read(input))
    }

    @Test(expected = ProtocolException::class)
    fun rejectsOversizedFrames() {
        FrameCodec.read(ByteArrayInputStream(byteArrayOf(0x7f, 0, 0, 0, 1)))
    }

    @Test
    fun handshakeIsMutualAndKeyed() {
        val key = Handshake.newKey()
        val nh = Handshake.newNonce()
        val nc = Handshake.newNonce()
        val client = Handshake.clientMac(key, nh, nc)
        assertTrue(Handshake.verify(Handshake.clientMac(key, nh, nc), client))
        assertFalse(Handshake.verify(Handshake.clientMac(Handshake.newKey(), nh, nc), client))
        assertFalse("helper and client MACs differ", Handshake.verify(Handshake.helperMac(key, nh, nc), client))
        assertTrue(Handshake.isValidSocketName(Handshake.newSocketName()))
        assertFalse(Handshake.isValidSocketName("keymapper.../x"))
    }

    @Test
    fun profileExportImport() {
        val text = KmJson.exportProfile(profile, listOf("com.game"))
        val back = KmJson.importProfile(text)
        assertEquals(profile, back.profile)
        assertEquals(listOf("com.game"), back.games)
        assertEquals(profile, KmJson.importProfile(KmJson.encodeProfile(profile)).profile) // bare object
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsForeignFormat() {
        KmJson.importProfile("""{"format":"something-else","profile":{"id":"x","name":"x"}}""")
    }

    @Test
    fun sanitizeClampsAndDedupes() {
        val bad = profile.copy(
            name = "",
            phases = listOf(Phase("p", listOf(
                ButtonControl("a", 3f, -1f, listOf(Button.A, Button.A, Button.B, Button.X, Button.Y), rapidHz = 500f),
                ButtonControl("a", 0.5f, 0.5f, listOf(Button.B)),
            ))),
        ).sanitized()
        val c = bad.phases.single().controls.single() as ButtonControl
        assertEquals(1f, c.x)
        assertEquals(0f, c.y)
        assertEquals(listOf(Button.A, Button.B, Button.X), c.buttons)
        assertEquals(ButtonControl.MAX_RAPID_HZ, c.rapidHz)
        assertEquals("Profile", bad.name)
    }
}
