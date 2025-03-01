package com.pedro.rtsp.rtcp

import com.pedro.rtsp.Utils
import com.pedro.rtsp.rtsp.Protocol
import com.pedro.rtsp.rtsp.RtpFrame
import com.pedro.rtsp.utils.RtpConstants
import com.pedro.rtsp.utils.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.MulticastSocket

/**
 * Created by pedro on 9/9/23.
 */
@RunWith(MockitoJUnitRunner::class)
class RtcpReportTest {

  @Mock
  private lateinit var multicastSocketMocked: MulticastSocket
  @Mock
  private lateinit var outputMocked: OutputStream
  private val timeUtilsMocked = Mockito.mockStatic(TimeUtils::class.java)
  private var fakeTime = 7502849023L

  @Before
  fun setup() {
    timeUtilsMocked.`when`<Long>(TimeUtils::getCurrentTimeMillis).then { fakeTime }
  }

  @After
  fun teardown() {
    fakeTime = 7502849023L
  }

  @Test
  fun `GIVEN multiple video or audio rtp frames WHEN update rtcp tcp send THEN send only 1 of video and 1 of audio each 3 seconds`() = runTest {
    Utils.useStatics(listOf(timeUtilsMocked)) {
      val senderReportTcp = BaseSenderReport.getInstance(Protocol.TCP, 0, 1)
      senderReportTcp.setDataStream(outputMocked, "127.0.0.1")
      senderReportTcp.setSSRC(0, 1)
      val fakeFrameVideo = RtpFrame(byteArrayOf(0x00, 0x00, 0x00), 0, 3, 0, 0, RtpConstants.trackVideo)
      val fakeFrameAudio = RtpFrame(byteArrayOf(0x00, 0x00, 0x00), 0, 3, 0, 0, RtpConstants.trackAudio)

      (0..10).forEach { value ->
        val frame = if (value % 2 == 0) fakeFrameVideo else fakeFrameAudio
        senderReportTcp.update(frame, false)
      }
      val resultValue = argumentCaptor<ByteArray>()
      withContext(Dispatchers.IO) {
        verify(outputMocked, times((2))).write(resultValue.capture())
      }
      fakeTime += 3_000 //wait until next interval
      (0..10).forEach { value ->
        val frame = if (value % 2 == 0) fakeFrameVideo else fakeFrameAudio
        senderReportTcp.update(frame, false)
      }
      withContext(Dispatchers.IO) {
        verify(outputMocked, times((4))).write(resultValue.capture())
      }
    }
  }

  @Test
  fun `GIVEN multiple video or audio rtp frames WHEN update rtcp udp send THEN send only 1 of video and 1 of audio each 3 seconds`() = runTest {
    Utils.useStatics(listOf(timeUtilsMocked)) {
      val senderReportUdp = SenderReportUdp(11111, 11112, multicastSocketMocked, multicastSocketMocked)
      senderReportUdp.setDataStream(outputMocked, "127.0.0.1")
      senderReportUdp.setSSRC(0, 1)
      val fakeFrameVideo = RtpFrame(byteArrayOf(0x00, 0x00, 0x00), 0, 3, 0, 0, RtpConstants.trackVideo)
      val fakeFrameAudio = RtpFrame(byteArrayOf(0x00, 0x00, 0x00), 0, 3, 0, 0, RtpConstants.trackAudio)
      (0..10).forEach { value ->
        val frame = if (value % 2 == 0) fakeFrameVideo else fakeFrameAudio
        senderReportUdp.update(frame, false)
      }
      val resultValue = argumentCaptor<DatagramPacket>()
      withContext(Dispatchers.IO) {
        verify(multicastSocketMocked, times((2))).send(resultValue.capture())
      }
      fakeTime += 3_000 //wait until next interval
      (0..10).forEach { value ->
        val frame = if (value % 2 == 0) fakeFrameVideo else fakeFrameAudio
        senderReportUdp.update(frame, false)
      }
      withContext(Dispatchers.IO) {
        verify(multicastSocketMocked, times((4))).send(resultValue.capture())
      }
    }
  }
}