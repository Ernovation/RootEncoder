package com.pedro.rtmp.rtmp.message

import com.pedro.rtmp.utils.CommandSessionHistory
import com.pedro.rtmp.utils.RtmpConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Created by pedro on 10/9/23.
 */
class WindowAcknowledgementSizeTest {

  private val commandSessionHistory = CommandSessionHistory()

  @Test
  fun `GIVEN a buffer WHEN read rtmp message THEN get expected window acknowledgement size packet`() {
    val buffer = byteArrayOf(2, 18, -42, -121, 0, 0, 4, 5, 0, 0, 0, 0, 0, 0, 1, 0)
    val windowAcknowledgementSize = WindowAcknowledgementSize(256, 1234567)

    val message = RtmpMessage.getRtmpMessage(ByteArrayInputStream(buffer), RtmpConfig.DEFAULT_CHUNK_SIZE, commandSessionHistory)

    assertTrue(message is WindowAcknowledgementSize)
    assertEquals(windowAcknowledgementSize.toString(), (message as WindowAcknowledgementSize).toString())
  }

  @Test
  fun `GIVEN a window acknowledgement size packet WHEN write into a buffer THEN get expected buffer`() {
    val expectedBuffer = byteArrayOf(2, 18, -42, -121, 0, 0, 4, 5, 0, 0, 0, 0, 0, 0, 1, 0)
    val output = ByteArrayOutputStream()

    val windowAcknowledgementSize = WindowAcknowledgementSize(256, 1234567)
    windowAcknowledgementSize.writeHeader(output)
    windowAcknowledgementSize.writeBody(output)

    assertArrayEquals(expectedBuffer, output.toByteArray())
  }
}