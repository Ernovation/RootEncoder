/*
 * Copyright (C) 2021 pedroSG94.
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

package com.pedro.rtsp.rtsp

import android.media.MediaCodec
import android.util.Log
import com.pedro.rtsp.rtsp.commands.CommandsManager
import com.pedro.rtsp.rtsp.commands.Method
import com.pedro.rtsp.utils.ConnectCheckerRtsp
import com.pedro.rtsp.utils.CreateSSLSocket.createSSlSocket
import com.pedro.rtsp.utils.RtpConstants
import com.pedro.rtsp.utils.onMainThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.regex.Pattern

/**
 * Created by pedro on 10/02/17.
 */
class RtspClient(private val connectCheckerRtsp: ConnectCheckerRtsp) {

  private val TAG = "RtspClient"
  private val rtspUrlPattern = Pattern.compile("^rtsps?://([^/:]+)(?::(\\d+))*/([^/]+)/?([^*]*)$")

  //sockets objects
  private var connectionSocket: Socket? = null
  private var reader: BufferedReader? = null
  private var writer: BufferedWriter? = null
  private var scope = CoroutineScope(Dispatchers.IO)
  private var scopeRetry = CoroutineScope(Dispatchers.IO)
  private var job: Job? = null
  private var jobRetry: Job? = null
  private var mutex = Mutex(locked = true)

  @Volatile
  var isStreaming = false
    private set

  //for secure transport
  private var tlsEnabled = false
  private val rtspSender: RtspSender = RtspSender(connectCheckerRtsp)
  private var url: String? = null
  private val commandsManager: CommandsManager = CommandsManager()
  private var doingRetry = false
  private var numRetry = 0
  private var reTries = 0
  private var checkServerAlive = false

  val droppedAudioFrames: Long
    get() = rtspSender.droppedAudioFrames
  val droppedVideoFrames: Long
    get() = rtspSender.droppedVideoFrames

  val cacheSize: Int
    get() = rtspSender.getCacheSize()
  val sentAudioFrames: Long
    get() = rtspSender.getSentAudioFrames()
  val sentVideoFrames: Long
    get() = rtspSender.getSentVideoFrames()

  /**
   * Check periodically if server is alive using Echo protocol.
   */
  fun setCheckServerAlive(enabled: Boolean) {
    checkServerAlive = enabled
  }

  /**
   * Must be called before connect
   */
  fun setOnlyAudio(onlyAudio: Boolean) {
    if (onlyAudio) {
      RtpConstants.trackAudio = 0
      RtpConstants.trackVideo = 1
    } else {
      RtpConstants.trackVideo = 0
      RtpConstants.trackAudio = 1
    }
    commandsManager.audioDisabled = false
    commandsManager.videoDisabled = onlyAudio
  }

  /**
   * Must be called before connect
   */
  fun setOnlyVideo(onlyVideo: Boolean) {
    RtpConstants.trackVideo = 0
    RtpConstants.trackAudio = 1
    commandsManager.videoDisabled = false
    commandsManager.audioDisabled = onlyVideo
  }

  fun setProtocol(protocol: Protocol) {
    commandsManager.protocol = protocol
  }

  fun setAuthorization(user: String?, password: String?) {
    commandsManager.setAuth(user, password)
  }

  fun setReTries(reTries: Int) {
    numRetry = reTries
    this.reTries = reTries
  }

  fun shouldRetry(reason: String): Boolean {
    val validReason = doingRetry && !reason.contains("Endpoint malformed")
    return validReason && reTries > 0
  }

  fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer, vps: ByteBuffer?) {
    Log.i(TAG, "send sps and pps")
    commandsManager.setVideoInfo(sps, pps, vps)
    if (mutex.isLocked) runCatching { mutex.unlock() }
  }

  fun setAudioInfo(sampleRate: Int, isStereo: Boolean) {
    commandsManager.setAudioInfo(sampleRate, isStereo)
  }

  fun setVideoCodec(codec: VideoCodec) {
    commandsManager.setCodec(codec)
  }

  @JvmOverloads
  fun connect(url: String?, isRetry: Boolean = false) {
    if (!isRetry) doingRetry = true
    if (!isStreaming || isRetry) {
      isStreaming = true

      job = scope.launch {
        if (url == null) {
          isStreaming = false
          onMainThread {
            connectCheckerRtsp.onConnectionFailedRtsp("Endpoint malformed, should be: rtsp://ip:port/appname/streamname")
          }
          return@launch
        }
        this@RtspClient.url = url
        onMainThread {
          connectCheckerRtsp.onConnectionStartedRtsp(url)
        }
        val rtspMatcher = rtspUrlPattern.matcher(url)
        if (rtspMatcher.matches()) {
          tlsEnabled = (rtspMatcher.group(0) ?: "").startsWith("rtsps")
        } else {
          isStreaming = false
          onMainThread {
            connectCheckerRtsp.onConnectionFailedRtsp("Endpoint malformed, should be: rtsp://ip:port/appname/streamname")
          }
          return@launch
        }
        val host = rtspMatcher.group(1) ?: ""
        val port: Int = rtspMatcher.group(2)?.toInt() ?: if (tlsEnabled) 443 else 554
        val streamName = if (rtspMatcher.group(4).isNullOrEmpty()) "" else "/" + rtspMatcher.group(4)
        val path = "/" + rtspMatcher.group(3) + streamName

        val error = runCatching {
          commandsManager.setUrl(host, port, path)
          rtspSender.setSocketsInfo(commandsManager.protocol,
            commandsManager.videoClientPorts,
            commandsManager.audioClientPorts)
          if (!commandsManager.audioDisabled) {
            rtspSender.setAudioInfo(commandsManager.sampleRate)
          }
          if (!commandsManager.videoDisabled) {
            if (!commandsManager.videoInfoReady()) {
              Log.i(TAG, "waiting for sps and pps")
              withTimeoutOrNull(5000) {
                mutex.lock()
              }
              if (!commandsManager.videoInfoReady()) {
                onMainThread {
                  connectCheckerRtsp.onConnectionFailedRtsp("sps or pps is null")
                }
                return@launch
              }
            }
            rtspSender.setVideoInfo(commandsManager.sps!!, commandsManager.pps!!, commandsManager.vps)
          }
          if (!tlsEnabled) {
            connectionSocket = Socket()
            val socketAddress: SocketAddress = InetSocketAddress(host, port)
            connectionSocket?.connect(socketAddress, 5000)
          } else {
            connectionSocket = createSSlSocket(host, port)
            if (connectionSocket == null) throw IOException("Socket creation failed")
          }
          connectionSocket?.soTimeout = 5000
          val reader = BufferedReader(InputStreamReader(connectionSocket?.getInputStream()))
          val outputStream = connectionSocket?.getOutputStream()
          val writer = BufferedWriter(OutputStreamWriter(outputStream))
          this@RtspClient.reader = reader
          this@RtspClient.writer = writer
          writer.write(commandsManager.createOptions())
          writer.flush()
          commandsManager.getResponse(reader, Method.OPTIONS)
          writer.write(commandsManager.createAnnounce())
          writer.flush()
          //check if you need credential for stream, if you need try connect with credential
          val announceResponse = commandsManager.getResponse(reader, Method.ANNOUNCE)
          when (announceResponse.status) {
            403 -> {
              onMainThread {
                connectCheckerRtsp.onConnectionFailedRtsp("Error configure stream, access denied")
              }
              Log.e(TAG, "Response 403, access denied")
              return@launch
            }
            401 -> {
              if (commandsManager.user == null || commandsManager.password == null) {
                onMainThread {
                  connectCheckerRtsp.onAuthErrorRtsp()
                }
                return@launch
              } else {
                writer.write(commandsManager.createAnnounceWithAuth(announceResponse.text))
                writer.flush()
                when (commandsManager.getResponse(reader, Method.ANNOUNCE).status) {
                  401 -> {
                    onMainThread {
                      connectCheckerRtsp.onAuthErrorRtsp()
                    }
                    return@launch
                  }
                  200 -> {
                    onMainThread {
                      connectCheckerRtsp.onAuthSuccessRtsp()
                    }
                  }
                  else -> {
                    onMainThread {
                      connectCheckerRtsp.onConnectionFailedRtsp("Error configure stream, announce with auth failed")
                    }
                    return@launch
                  }
                }
              }
            }
            200 -> {
              Log.i(TAG, "announce success")
            }
            else -> {
              onMainThread {
                connectCheckerRtsp.onConnectionFailedRtsp("Error configure stream, announce failed")
              }
              return@launch
            }
          }
          if (!commandsManager.videoDisabled) {
            writer.write(commandsManager.createSetup(RtpConstants.trackVideo))
            writer.flush()
            val setupVideoStatus = commandsManager.getResponse(reader, Method.SETUP).status
            if (setupVideoStatus != 200) {
              onMainThread {
                connectCheckerRtsp.onConnectionFailedRtsp("Error configure stream, setup video $setupVideoStatus")
              }
              return@launch
            }
          }
          if (!commandsManager.audioDisabled) {
            writer.write(commandsManager.createSetup(RtpConstants.trackAudio))
            writer.flush()
            val setupAudioStatus = commandsManager.getResponse(reader, Method.SETUP).status
            if (setupAudioStatus != 200) {
              onMainThread {
                connectCheckerRtsp.onConnectionFailedRtsp("Error configure stream, setup audio $setupAudioStatus")
              }
              return@launch
            }
          }
          writer.write(commandsManager.createRecord())
          writer.flush()
          val recordStatus = commandsManager.getResponse(reader, Method.RECORD).status
          if (recordStatus != 200) {
            onMainThread {
              connectCheckerRtsp.onConnectionFailedRtsp("Error configure stream, record $recordStatus")
            }
            return@launch
          }
          outputStream?.let { out ->
            rtspSender.setDataStream(out, host)
          }
          val videoPorts = commandsManager.videoServerPorts
          val audioPorts = commandsManager.audioServerPorts
          if (!commandsManager.videoDisabled) {
            rtspSender.setVideoPorts(videoPorts[0], videoPorts[1])
          }
          if (!commandsManager.audioDisabled) {
            rtspSender.setAudioPorts(audioPorts[0], audioPorts[1])
          }
          rtspSender.start()
          reTries = numRetry
          onMainThread {
            connectCheckerRtsp.onConnectionSuccessRtsp()
          }
          handleServerCommands()
        }.exceptionOrNull()
        if (error != null) {
          Log.e(TAG, "connection error", error)
          onMainThread {
            connectCheckerRtsp.onConnectionFailedRtsp("Error configure stream, ${error.message}")
          }
          return@launch
        }
      }
    }
  }

  private suspend fun handleServerCommands() {
    //Read and print server commands received each 2 seconds
    while (scope.isActive && isStreaming) {
      val error = runCatching {
        if (isAlive()) {
          delay(2000)
          reader?.let { r ->
            if (r.ready()) {
              val command = commandsManager.getResponse(r)
              //Do something depend of command if required
            }
          }
        } else {
          onMainThread {
            connectCheckerRtsp.onConnectionFailedRtsp("No response from server")
          }
          scope.cancel()
        }
      }.exceptionOrNull()
      if (error != null && error !is SocketTimeoutException) {
        scope.cancel()
      }
    }
  }

  /*
    Send a heartbeat to know if server is alive using Echo Protocol.
    Your firewall could block it.
   */
  private fun isAlive(): Boolean {
    val connected = connectionSocket?.isConnected ?: false
    if (!checkServerAlive) return connected
    val reachable = connectionSocket?.inetAddress?.isReachable(5000) ?: false
    return if (connected && !reachable) false else connected
  }

  fun disconnect() {
    CoroutineScope(Dispatchers.IO).launch {
      disconnect(true)
    }
  }

  private suspend fun disconnect(clear: Boolean) {
    if (isStreaming) rtspSender.stop()
    val error = runCatching {
      writer?.write(commandsManager.createTeardown())
      writer?.flush()
      connectionSocket?.close()
      reader?.close()
      reader = null
      writer?.close()
      writer = null
      connectionSocket = null
      Log.i(TAG, "write teardown success")
    }.exceptionOrNull()
    if (error != null) {
      Log.e(TAG, "disconnect error", error)
    }
    if (clear) {
      commandsManager.clear()
      reTries = numRetry
      doingRetry = false
      isStreaming = false
      onMainThread {
        connectCheckerRtsp.onDisconnectRtsp()
      }
      mutex = Mutex(true)
      jobRetry?.cancelAndJoin()
      jobRetry = null
      scopeRetry.cancel()
      scopeRetry = CoroutineScope(Dispatchers.IO)
    } else {
      commandsManager.retryClear()
    }
    job?.cancelAndJoin()
    job = null
    scope.cancel()
    scope = CoroutineScope(Dispatchers.IO)
  }

  fun sendVideo(h264Buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    if (!commandsManager.videoDisabled) {
      rtspSender.sendVideoFrame(h264Buffer, info)
    }
  }

  fun sendAudio(aacBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    if (!commandsManager.audioDisabled) {
      rtspSender.sendAudioFrame(aacBuffer, info)
    }
  }

  @JvmOverloads
  @Throws(IllegalArgumentException::class)
  fun hasCongestion(percentUsed: Float = 20f): Boolean {
    return rtspSender.hasCongestion(percentUsed)
  }

  @JvmOverloads
  fun reConnect(delay: Long, backupUrl: String? = null) {
    jobRetry = scopeRetry.launch {
      reTries--
      disconnect(false)
      delay(delay)
      val reconnectUrl = backupUrl ?: url
      connect(reconnectUrl, true)
    }
  }

  fun resetSentAudioFrames() {
    rtspSender.resetSentAudioFrames()
  }

  fun resetSentVideoFrames() {
    rtspSender.resetSentVideoFrames()
  }

  fun resetDroppedAudioFrames() {
    rtspSender.resetDroppedAudioFrames()
  }

  fun resetDroppedVideoFrames() {
    rtspSender.resetDroppedVideoFrames()
  }

  @Throws(RuntimeException::class)
  fun resizeCache(newSize: Int) {
    rtspSender.resizeCache(newSize)
  }

  fun setLogs(enable: Boolean) {
    rtspSender.setLogs(enable)
  }

  fun clearCache() {
    rtspSender.clearCache()
  }

  fun getItemsInCache(): Int = rtspSender.getItemsInCache()
}