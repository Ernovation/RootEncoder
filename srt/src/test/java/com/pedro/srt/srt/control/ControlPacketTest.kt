package com.pedro.srt.srt.control

import com.pedro.srt.srt.packets.ControlPacket
import com.pedro.srt.srt.packets.DataPacket
import com.pedro.srt.srt.packets.control.ControlType
import com.pedro.srt.srt.packets.control.Shutdown
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Created by pedro on 9/9/23.
 */
class ControlPacketTest {

  @Test(expected = IOException::class)
  fun `GIVEN a packet buffer WHEN get an invalid packet type reading buffer THEN crash with IOException`() {
    val buffer = byteArrayOf(0x00, 5, 0, 0, 0, 0, 0, 0, 0, 0, 9, -60, 0, 0, 0, 64, 0, 0, 0, 0)
    //packet control type is not really important because should crash reading header
    val packet = Shutdown()
    packet.read(ByteArrayInputStream(buffer))
  }

  @Test(expected = IOException::class)
  fun `GIVEN a packet buffer WHEN get an invalid packet sub type reading buffer THEN crash with IOException`() {
    val buffer = byteArrayOf(-128, 5, 0, 1, 0, 0, 0, 0, 0, 0, 9, -60, 0, 0, 0, 64, 0, 0, 0, 0)
    //packet control type is not really important because should crash reading header
    val packet = Shutdown()
    packet.read(ByteArrayInputStream(buffer))
  }

  @Test(expected = IOException::class)
  fun `GIVEN a packet buffer WHEN get invalid type reading buffer THEN crash with IOException`() {
    val buffer = byteArrayOf(-128, 11, 0, 0, 0, 0, 0, 0, 0, 0, 9, -60, 0, 0, 0, 64, 0, 0, 0, 0)
    ControlPacket.getType(ByteArrayInputStream(buffer))
  }

  @Test
  fun `GIVEN a packet buffer with type 5 WHEN get type reading buffer THEN get shutdown type`() {
    val buffer = byteArrayOf(-128, 5, 0, 0, 0, 0, 0, 0, 0, 0, 9, -60, 0, 0, 0, 64, 0, 0, 0, 0)
    val type = ControlPacket.getType(ByteArrayInputStream(buffer))
    assertEquals(type, ControlType.SHUTDOWN)
  }
}