/*
 * Copyright (C) 2023 pedroSG94.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pedro.library.srt

import android.content.Context
import android.media.MediaCodec
import android.os.Build
import androidx.annotation.RequiresApi
import com.pedro.library.base.StreamBase
import com.pedro.library.util.VideoCodec
import com.pedro.library.util.sources.AudioManager
import com.pedro.library.util.sources.VideoManager
import com.pedro.library.util.streamclient.SrtStreamClient
import com.pedro.library.util.streamclient.StreamClientListener
import com.pedro.srt.srt.SrtClient
import com.pedro.srt.utils.ConnectCheckerSrt
import java.nio.ByteBuffer

/**
 * Created by pedro on 8/9/23.
 *
 * If you use VideoManager.Source.SCREEN/AudioManager.Source.INTERNAL. Call
 * changeVideoSourceScreen/changeAudioSourceInternal is necessary to start it.
 */

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class SrtStream(
  context: Context, connectCheckerRtmp: ConnectCheckerSrt, videoSource: VideoManager.Source,
  audioSource: AudioManager.Source
): StreamBase(context, videoSource, audioSource) {

  private val srtClient = SrtClient(connectCheckerRtmp)
  private val streamClientListener = object: StreamClientListener {
    override fun onRequestKeyframe() {
      requestKeyframe()
    }
  }
  override fun getStreamClient(): SrtStreamClient = SrtStreamClient(srtClient, streamClientListener)

  constructor(context: Context, connectCheckerRtmp: ConnectCheckerSrt):
      this(context, connectCheckerRtmp, VideoManager.Source.CAMERA2, AudioManager.Source.MICROPHONE)

  override fun setVideoCodecImp(codec: VideoCodec) {
    srtClient.setVideoCodec(if (codec === VideoCodec.H264) com.pedro.srt.srt.VideoCodec.H264 else com.pedro.srt.srt.VideoCodec.H265)
  }

  override fun audioInfo(sampleRate: Int, isStereo: Boolean) {
    srtClient.setAudioInfo(sampleRate, isStereo)
  }

  override fun rtpStartStream(endPoint: String) {
    srtClient.connect(endPoint)
  }

  override fun rtpStopStream() {
    srtClient.disconnect()
  }

  override fun onSpsPpsVpsRtp(sps: ByteBuffer, pps: ByteBuffer, vps: ByteBuffer?) {
    srtClient.setVideoInfo(sps, pps, vps)
  }

  override fun getH264DataRtp(h264Buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    srtClient.sendVideo(h264Buffer, info)
  }

  override fun getAacDataRtp(aacBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    srtClient.sendAudio(aacBuffer, info)
  }
}