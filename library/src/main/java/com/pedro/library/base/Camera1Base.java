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

package com.pedro.library.base;

import android.content.Context;
import android.graphics.Point;
import android.hardware.Camera;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.pedro.encoder.EncoderErrorCallback;
import com.pedro.encoder.audio.AudioEncoder;
import com.pedro.encoder.audio.GetAacData;
import com.pedro.encoder.input.audio.CustomAudioEffect;
import com.pedro.encoder.input.audio.GetMicrophoneData;
import com.pedro.encoder.input.audio.MicrophoneManager;
import com.pedro.encoder.input.audio.MicrophoneManagerManual;
import com.pedro.encoder.input.audio.MicrophoneMode;
import com.pedro.encoder.input.video.Camera1ApiManager;
import com.pedro.encoder.input.video.CameraCallbacks;
import com.pedro.encoder.input.video.CameraHelper;
import com.pedro.encoder.input.video.CameraOpenException;
import com.pedro.encoder.input.video.GetCameraData;
import com.pedro.encoder.input.video.facedetector.FaceDetectorCallback;
import com.pedro.encoder.utils.CodecUtil;
import com.pedro.encoder.video.FormatVideoEncoder;
import com.pedro.encoder.video.GetVideoData;
import com.pedro.encoder.video.VideoEncoder;
import com.pedro.library.base.recording.BaseRecordController;
import com.pedro.library.base.recording.RecordController;
import com.pedro.library.util.AndroidMuxerRecordController;
import com.pedro.library.util.FpsListener;
import com.pedro.library.util.VideoCodec;
import com.pedro.library.util.streamclient.StreamBaseClient;
import com.pedro.library.view.GlInterface;
import com.pedro.library.view.LightOpenGlView;
import com.pedro.library.view.OffScreenGlThread;
import com.pedro.library.view.OpenGlView;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Wrapper to stream with camera1 api and microphone. Support stream with SurfaceView, TextureView
 * and OpenGlView(Custom SurfaceView that use OpenGl). SurfaceView and TextureView use buffer to
 * buffer encoding mode for H264 and OpenGlView use Surface to buffer mode(This mode is generally
 * better because skip buffer processing).
 *
 * API requirements:
 * SurfaceView and TextureView mode: API 16+.
 * OpenGlView: API 18+.
 *
 * Created by pedro on 7/07/17.
 */

public abstract class Camera1Base {

  private static final String TAG = "Camera1Base";

  private final Context context;
  private final Camera1ApiManager cameraManager;
  protected VideoEncoder videoEncoder;
  private MicrophoneManager microphoneManager;
  private AudioEncoder audioEncoder;
  private GlInterface glInterface;
  private boolean streaming = false;
  protected boolean audioInitialized = false;
  private boolean onPreview = false;
  protected BaseRecordController recordController;
  private int previewWidth, previewHeight;
  private final FpsListener fpsListener = new FpsListener();

  public Camera1Base(SurfaceView surfaceView) {
    context = surfaceView.getContext();
    cameraManager = new Camera1ApiManager(surfaceView, getCameraData);
    init();
  }

  public Camera1Base(TextureView textureView) {
    context = textureView.getContext();
    cameraManager = new Camera1ApiManager(textureView, getCameraData);
    init();
  }

  @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
  public Camera1Base(OpenGlView openGlView) {
    context = openGlView.getContext();
    this.glInterface = openGlView;
    cameraManager = new Camera1ApiManager(glInterface.getSurfaceTexture(), context);
    init();
  }

  @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
  public Camera1Base(LightOpenGlView lightOpenGlView) {
    context = lightOpenGlView.getContext();
    this.glInterface = lightOpenGlView;
    cameraManager = new Camera1ApiManager(glInterface.getSurfaceTexture(), context);
    init();
  }

  @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
  public Camera1Base(Context context) {
    this.context = context;
    glInterface = new OffScreenGlThread(context);
    cameraManager = new Camera1ApiManager(glInterface.getSurfaceTexture(), context);
    init();
  }

  private void init() {
    videoEncoder = new VideoEncoder(getVideoData);
    setMicrophoneMode(MicrophoneMode.ASYNC);
    recordController = new AndroidMuxerRecordController();
  }

  /**
   * Must be called before prepareAudio.
   *
   * @param microphoneMode mode to work accord to audioEncoder. By default ASYNC:
   * SYNC using same thread. This mode could solve choppy audio or audio frame discarded.
   * ASYNC using other thread.
   */
  public void setMicrophoneMode(MicrophoneMode microphoneMode) {
    switch (microphoneMode) {
      case SYNC:
        microphoneManager = new MicrophoneManagerManual();
        audioEncoder = new AudioEncoder(getAacData);
        audioEncoder.setGetFrame(((MicrophoneManagerManual) microphoneManager).getGetFrame());
        audioEncoder.setTsModeBuffer(false);
        break;
      case ASYNC:
        microphoneManager = new MicrophoneManager(getMicrophoneData);
        audioEncoder = new AudioEncoder(getAacData);
        audioEncoder.setTsModeBuffer(false);
        break;
      case BUFFER:
        microphoneManager = new MicrophoneManager(getMicrophoneData);
        audioEncoder = new AudioEncoder(getAacData);
        audioEncoder.setTsModeBuffer(true);
        break;
    }
  }

  public void setCameraCallbacks(CameraCallbacks callbacks) {
    cameraManager.setCameraCallbacks(callbacks);
  }

  /**
   * Set a callback to know errors related with Video/Audio encoders
   * @param encoderErrorCallback callback to use, null to remove
   */
  public void setEncoderErrorCallback(EncoderErrorCallback encoderErrorCallback) {
    videoEncoder.setEncoderErrorCallback(encoderErrorCallback);
    audioEncoder.setEncoderErrorCallback(encoderErrorCallback);
  }

  /**
   * Set an audio effect modifying microphone's PCM buffer.
   */
  public void setCustomAudioEffect(CustomAudioEffect customAudioEffect) {
    microphoneManager.setCustomAudioEffect(customAudioEffect);
  }

  /**
   * @param callback get fps while record or stream
   */
  public void setFpsListener(FpsListener.Callback callback) {
    fpsListener.setCallback(callback);
  }

  /**
   * @return true if success, false if fail (not supported or called before start camera)
   */
  public boolean enableFaceDetection(FaceDetectorCallback faceDetectorCallback) {
    return cameraManager.enableFaceDetection(faceDetectorCallback);
  }

  public void disableFaceDetection() {
    cameraManager.disableFaceDetection();
  }

  public boolean isFaceDetectionEnabled() {
    return cameraManager.isFaceDetectionEnabled();
  }

  /**
   * @return true if success, false if fail (not supported or called before start camera)
   */
  public boolean enableVideoStabilization() {
    return cameraManager.enableVideoStabilization();
  }

  public void disableVideoStabilization() {
    cameraManager.disableVideoStabilization();
  }

  public boolean isVideoStabilizationEnabled() {
    return cameraManager.isVideoStabilizationEnabled();
  }

  /**
   * Use getCameraFacing instead
   */
  @Deprecated
  public boolean isFrontCamera() {
    return cameraManager.getCameraFacing() == CameraHelper.Facing.FRONT;
  }

  public CameraHelper.Facing getCameraFacing() {
    return cameraManager.getCameraFacing();
  }

  public void enableLantern() throws Exception {
    cameraManager.enableLantern();
  }

  public void disableLantern() {
    cameraManager.disableLantern();
  }

  public boolean isLanternEnabled() {
    return cameraManager.isLanternEnabled();
  }

  public void enableAutoFocus() {
    cameraManager.enableAutoFocus();
  }

  public void disableAutoFocus() {
    cameraManager.disableAutoFocus();
  }

  public boolean isAutoFocusEnabled() {
    return cameraManager.isAutoFocusEnabled();
  }

  /**
   * Call this method before use @startStream. If not you will do a stream without video. NOTE:
   * Rotation with encoder is silence ignored in some devices.
   *
   * @param width resolution in px.
   * @param height resolution in px.
   * @param fps frames per second of the stream.
   * @param bitrate H264 in bps.
   * @param rotation could be 90, 180, 270 or 0. You should use CameraHelper.getCameraOrientation
   * with SurfaceView or TextureView and 0 with OpenGlView or LightOpenGlView. NOTE: Rotation with
   * encoder is silence ignored in some devices.
   * @return true if success, false if you get a error (Normally because the encoder selected
   * doesn't support any configuration seated or your device hasn't a H264 encoder).
   */
  public boolean prepareVideo(int width, int height, int fps, int bitrate, int iFrameInterval,
      int rotation, int avcProfile, int avcProfileLevel) {
    if (onPreview && width != previewWidth || height != previewHeight
        || fps != videoEncoder.getFps() || rotation != videoEncoder.getRotation()) {
      stopPreview();
      onPreview = true;
    }
    FormatVideoEncoder formatVideoEncoder =
        glInterface == null ? FormatVideoEncoder.YUV420Dynamical : FormatVideoEncoder.SURFACE;
    return videoEncoder.prepareVideoEncoder(width, height, fps, bitrate, rotation, iFrameInterval,
        formatVideoEncoder, avcProfile, avcProfileLevel);
  }

  /**
   * backward compatibility reason
   */
  public boolean prepareVideo(int width, int height, int fps, int bitrate, int iFrameInterval,
      int rotation) {
    return prepareVideo(width, height, fps, bitrate, iFrameInterval, rotation, -1, -1);
  }

  public boolean prepareVideo(int width, int height, int fps, int bitrate, int rotation) {
    return prepareVideo(width, height, fps, bitrate, 2, rotation);
  }

  public boolean prepareVideo(int width, int height, int bitrate) {
    int rotation = CameraHelper.getCameraOrientation(context);
    return prepareVideo(width, height, 30, bitrate, 2, rotation);
  }

  protected abstract void prepareAudioRtp(boolean isStereo, int sampleRate);

  /**
   * Call this method before use @startStream. If not you will do a stream without audio.
   *
   * @param bitrate AAC in kb.
   * @param sampleRate of audio in hz. Can be 8000, 16000, 22500, 32000, 44100.
   * @param isStereo true if you want Stereo audio (2 audio channels), false if you want Mono audio
   * (1 audio channel).
   * @param echoCanceler true enable echo canceler, false disable.
   * @param noiseSuppressor true enable noise suppressor, false  disable.
   * @return true if success, false if you get a error (Normally because the encoder selected
   * doesn't support any configuration seated or your device hasn't a AAC encoder).
   */
  public boolean prepareAudio(int audioSource, int bitrate, int sampleRate, boolean isStereo, boolean echoCanceler,
      boolean noiseSuppressor) {
     if (!microphoneManager.createMicrophone(audioSource, sampleRate, isStereo, echoCanceler, noiseSuppressor)) {
       return false;
     }
    prepareAudioRtp(isStereo, sampleRate);
    audioInitialized = audioEncoder.prepareAudioEncoder(bitrate, sampleRate, isStereo,
        microphoneManager.getMaxInputSize());
    return audioInitialized;
  }

  public boolean prepareAudio(int bitrate, int sampleRate, boolean isStereo, boolean echoCanceler,
      boolean noiseSuppressor) {
    return prepareAudio(MediaRecorder.AudioSource.DEFAULT, bitrate, sampleRate, isStereo, echoCanceler,
        noiseSuppressor);
  }

  public boolean prepareAudio(int bitrate, int sampleRate, boolean isStereo) {
    return prepareAudio(bitrate, sampleRate, isStereo, false, false);
  }

  /**
   * Same to call: rotation = 0; if (Portrait) rotation = 90; prepareVideo(640, 480, 30, 1200 *
   * 1024, false, rotation);
   *
   * @return true if success, false if you get a error (Normally because the encoder selected
   * doesn't support any configuration seated or your device hasn't a H264 encoder).
   */
  public boolean prepareVideo() {
    int rotation = CameraHelper.getCameraOrientation(context);
    return prepareVideo(640, 480, 30, 1200 * 1024, rotation);
  }

  /**
   * Same to call: prepareAudio(64 * 1024, 32000, true, false, false);
   *
   * @return true if success, false if you get a error (Normally because the encoder selected
   * doesn't support any configuration seated or your device hasn't a AAC encoder).
   */
  public boolean prepareAudio() {
    return prepareAudio(64 * 1024, 32000, true, false, false);
  }

  /**
   * @param forceVideo force type codec used. FIRST_COMPATIBLE_FOUND, SOFTWARE, HARDWARE
   * @param forceAudio force type codec used. FIRST_COMPATIBLE_FOUND, SOFTWARE, HARDWARE
   */
  public void setForce(CodecUtil.Force forceVideo, CodecUtil.Force forceAudio) {
    videoEncoder.setForce(forceVideo);
    audioEncoder.setForce(forceAudio);
  }

  /**
   * Starts recording a MP4 video.
   *
   * @param path Where file will be saved.
   * @throws IOException If initialized before a stream.
   */
  @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
  public void startRecord(@NonNull final String path, @Nullable RecordController.Listener listener)
      throws IOException {
    recordController.startRecord(path, listener);
    if (!streaming) {
      startEncoders();
    } else if (videoEncoder.isRunning()) {
      requestKeyFrame();
    }
  }

  @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
  public void startRecord(@NonNull final String path) throws IOException {
    startRecord(path, null);
  }

  /**
   * Starts recording a MP4 video.
   *
   * @param fd Where the file will be saved.
   * @throws IOException If initialized before a stream.
   */
  @RequiresApi(api = Build.VERSION_CODES.O)
  public void startRecord(@NonNull final FileDescriptor fd,
      @Nullable RecordController.Listener listener) throws IOException {
    recordController.startRecord(fd, listener);
    if (!streaming) {
      startEncoders();
    } else if (videoEncoder.isRunning()) {
      requestKeyFrame();
    }
  }

  @RequiresApi(api = Build.VERSION_CODES.O)
  public void startRecord(@NonNull final FileDescriptor fd) throws IOException {
    startRecord(fd, null);
  }

  /**
   * Stop record MP4 video started with @startRecord. If you don't call it file will be unreadable.
   */
  @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
  public void stopRecord() {
    recordController.stopRecord();
    if (!streaming) stopStream();
  }

  @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
  public void replaceView(Context context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
      replaceGlInterface(new OffScreenGlThread(context));
    }
  }

  @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
  public void replaceView(OpenGlView openGlView) {
    replaceGlInterface(openGlView);
  }

  @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
  public void replaceView(LightOpenGlView lightOpenGlView) {
    replaceGlInterface(lightOpenGlView);
  }

  /**
   * Replace glInterface used on fly. Ignored if you use SurfaceView or TextureView
   */
  @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
  private void replaceGlInterface(GlInterface glInterface) {
    if (this.glInterface != null && Build.VERSION.SDK_INT >= 18) {
      if (isStreaming() || isRecording() || isOnPreview()) {
        Point size = this.glInterface.getEncoderSize();
        cameraManager.stop();
        this.glInterface.removeMediaCodecSurface();
        this.glInterface.stop();
        this.glInterface = glInterface;
        this.glInterface.setEncoderSize(size.x, size.y);
        this.glInterface.setRotation(0);
        this.glInterface.start();
        if (isStreaming() || isRecording()) {
          this.glInterface.addMediaCodecSurface(videoEncoder.getInputSurface());
        }
        cameraManager.setSurfaceTexture(glInterface.getSurfaceTexture());
        cameraManager.setRotation(videoEncoder.getRotation());
        cameraManager.start(videoEncoder.getWidth(), videoEncoder.getHeight(),
            videoEncoder.getFps());
      } else {
        this.glInterface = glInterface;
      }
    }
  }

  /**
   * Start camera preview. Ignored, if stream or preview is started.
   *
   * @param cameraFacing front or back camera. Like: {@link com.pedro.encoder.input.video.CameraHelper.Facing#BACK}
   * {@link com.pedro.encoder.input.video.CameraHelper.Facing#FRONT}
   * @param width of preview in px.
   * @param height of preview in px.
   * @param rotation camera rotation (0, 90, 180, 270). Recommended: {@link
   * com.pedro.encoder.input.video.CameraHelper#getCameraOrientation(Context)}
   */
  public void startPreview(CameraHelper.Facing cameraFacing, int width, int height, int fps, int rotation) {
    if (!isStreaming() && !onPreview && !(glInterface instanceof OffScreenGlThread)) {
      previewWidth = width;
      previewHeight = height;
      videoEncoder.setFps(fps);
      videoEncoder.setRotation(rotation);
      if (glInterface != null && Build.VERSION.SDK_INT >= 18) {
        if (videoEncoder.getRotation() == 90 || videoEncoder.getRotation() == 270) {
          glInterface.setEncoderSize(height, width);
        } else {
          glInterface.setEncoderSize(width, height);
        }
        glInterface.setRotation(0);
        glInterface.setFps(fps);
        glInterface.start();
        cameraManager.setSurfaceTexture(glInterface.getSurfaceTexture());
      }
      cameraManager.setRotation(rotation);
      cameraManager.start(cameraFacing, width, height, videoEncoder.getFps());
      onPreview = true;
    } else if (!isStreaming() && !onPreview && glInterface instanceof OffScreenGlThread) {
      // if you are using background mode startPreview only work to indicate
      // that you want start with front or back camera
      cameraManager.setCameraFacing(cameraFacing);
    } else {
      Log.e(TAG, "Streaming or preview started, ignored");
    }
  }

  /**
   * Start camera preview. Ignored, if stream or preview is started.
   *
   * @param cameraId camera id.
   * {@link com.pedro.encoder.input.video.CameraHelper.Facing#FRONT}
   * @param width of preview in px.
   * @param height of preview in px.
   * @param rotation camera rotation (0, 90, 180, 270). Recommended: {@link
   * com.pedro.encoder.input.video.CameraHelper#getCameraOrientation(Context)}
   */
  public void startPreview(int cameraId, int width, int height, int fps, int rotation) {
    if (!isStreaming() && !onPreview && !(glInterface instanceof OffScreenGlThread)) {
      previewWidth = width;
      previewHeight = height;
      videoEncoder.setFps(fps);
      videoEncoder.setRotation(rotation);
      if (glInterface != null && Build.VERSION.SDK_INT >= 18) {
        if (videoEncoder.getRotation() == 90 || videoEncoder.getRotation() == 270) {
          glInterface.setEncoderSize(height, width);
        } else {
          glInterface.setEncoderSize(width, height);
        }
        glInterface.setRotation(0);
        glInterface.setFps(fps);
        glInterface.start();
        cameraManager.setSurfaceTexture(glInterface.getSurfaceTexture());
      }
      cameraManager.setRotation(rotation);
      cameraManager.start(cameraId, width, height, videoEncoder.getFps());
      onPreview = true;
    } else if (!isStreaming() && !onPreview && glInterface instanceof OffScreenGlThread) {
      // if you are using background mode startPreview only work to indicate
      // that you want start with front or back camera
      cameraManager.setCameraSelect(cameraId);
    } else {
      Log.e(TAG, "Streaming or preview started, ignored");
    }
  }

  public void startPreview(CameraHelper.Facing cameraFacing, int width, int height, int rotation) {
    startPreview(cameraFacing, width, height, videoEncoder.getFps(), rotation);
  }

  public void startPreview(int cameraFacing, int width, int height, int rotation) {
    startPreview(cameraFacing, width, height, videoEncoder.getFps(), rotation);
  }

  public void startPreview(CameraHelper.Facing cameraFacing, int width, int height) {
    startPreview(cameraFacing, width, height, CameraHelper.getCameraOrientation(context));
  }

  public void startPreview(int cameraFacing, int width, int height) {
    startPreview(cameraFacing, width, height, CameraHelper.getCameraOrientation(context));
  }

  public void startPreview(CameraHelper.Facing cameraFacing, int rotation) {
    startPreview(cameraFacing, videoEncoder.getWidth(), videoEncoder.getHeight(), rotation);
  }

  public void startPreview(CameraHelper.Facing cameraFacing) {
    startPreview(cameraFacing, videoEncoder.getWidth(), videoEncoder.getHeight());
  }

  public void startPreview(int cameraFacing) {
    startPreview(cameraFacing, videoEncoder.getWidth(), videoEncoder.getHeight());
  }

  public void startPreview(int width, int height) {
    startPreview(getCameraFacing(), width, height);
  }

  public void startPreview() {
    startPreview(getCameraFacing());
  }

  /**
   * Stop camera preview. Ignored if streaming or already stopped. You need call it after
   *
   * @stopStream to release camera properly if you will close activity.
   */
  public void stopPreview() {
    if (!isStreaming()
        && !isRecording()
        && onPreview
        && !(glInterface instanceof OffScreenGlThread)) {
      if (glInterface != null && Build.VERSION.SDK_INT >= 18) {
        glInterface.stop();
      }
      cameraManager.stop();
      onPreview = false;
      previewWidth = 0;
      previewHeight = 0;
    } else {
      Log.e(TAG, "Streaming or preview stopped, ignored");
    }
  }

  /**
   * Change preview orientation can be called while stream.
   *
   * @param orientation of the camera preview. Could be 90, 180, 270 or 0.
   */
  public void setPreviewOrientation(int orientation) {
    cameraManager.setPreviewOrientation(orientation);
  }

  /**
   * Set zoomIn or zoomOut to camera.
   *
   * @param event motion event. Expected to get event.getPointerCount() > 1
   */
  public void setZoom(MotionEvent event) {
    cameraManager.setZoom(event);
  }

  /**
   * Set zoomIn or zoomOut to camera.
   * Use this method if you use a zoom slider.
   *
   * @param level Expected to be >= 1 and <= max zoom level
   * @see Camera2Base#getZoom()
   */
  public void setZoom(int level) {
    cameraManager.setZoom(level);
  }

  /**
   * Return current zoom level
   *
   * @return current zoom level
   */
  public float getZoom() {
    return cameraManager.getZoom();
  }

  /**
   * Return max zoom level
   *
   * @return max zoom level range
   */
  public int getMaxZoom() {
    return cameraManager.getMaxZoom();
  }

  /**
   * Return min zoom level
   *
   * @return min zoom level range
   */
  public int getMinZoom() {
    return cameraManager.getMinZoom();
  }

  @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
  public void startStreamAndRecord(String url, String path, RecordController.Listener listener) throws IOException {
    startStream(url);
    recordController.startRecord(path, listener);
  }

  @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
  public void startStreamAndRecord(String url, String path) throws IOException {
    startStreamAndRecord(url, path, null);
  }

  protected abstract void startStreamRtp(String url);

  /**
   * Need be called after @prepareVideo or/and @prepareAudio. This method override resolution of
   *
   * @param url of the stream like: protocol://ip:port/application/streamName
   *
   * RTSP: rtsp://192.168.1.1:1935/live/pedroSG94 RTSPS: rtsps://192.168.1.1:1935/live/pedroSG94
   * RTMP: rtmp://192.168.1.1:1935/live/pedroSG94 RTMPS: rtmps://192.168.1.1:1935/live/pedroSG94
   * @startPreview to resolution seated in @prepareVideo. If you never startPreview this method
   * startPreview for you to resolution seated in @prepareVideo.
   */
  public void startStream(String url) {
    streaming = true;
    if (!recordController.isRunning()) {
      startEncoders();
    } else {
      requestKeyFrame();
    }
    startStreamRtp(url);
    onPreview = true;
  }

  private void startEncoders() {
    videoEncoder.start();
    if (audioInitialized) audioEncoder.start();
    prepareGlView();
    if (audioInitialized) microphoneManager.start();
    cameraManager.setRotation(videoEncoder.getRotation());
    if (!cameraManager.isRunning() && videoEncoder.getWidth() != previewWidth
        || videoEncoder.getHeight() != previewHeight) {
      cameraManager.start(videoEncoder.getWidth(), videoEncoder.getHeight(), videoEncoder.getFps());
    }
    onPreview = true;
  }

  public void requestKeyFrame() {
    if (videoEncoder.isRunning()) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
        videoEncoder.requestKeyframe();
      } else {
        if (glInterface != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
          glInterface.removeMediaCodecSurface();
        }
        videoEncoder.reset();
        if (glInterface != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
          glInterface.addMediaCodecSurface(videoEncoder.getInputSurface());
        }
      }
    }
  }

  private void prepareGlView() {
    if (glInterface != null && Build.VERSION.SDK_INT >= 18) {
      glInterface.setFps(videoEncoder.getFps());
      if (videoEncoder.getRotation() == 90 || videoEncoder.getRotation() == 270) {
        glInterface.setEncoderSize(videoEncoder.getHeight(), videoEncoder.getWidth());
      } else {
        glInterface.setEncoderSize(videoEncoder.getWidth(), videoEncoder.getHeight());
      }
      glInterface.setRotation(0);
      if (!cameraManager.isRunning() && videoEncoder.getWidth() != previewWidth
          || videoEncoder.getHeight() != previewHeight) {
        glInterface.start();
      }
      if (videoEncoder.getInputSurface() != null) {
        glInterface.addMediaCodecSurface(videoEncoder.getInputSurface());
      }
      cameraManager.setSurfaceTexture(glInterface.getSurfaceTexture());
    }
  }

  protected abstract void stopStreamRtp();

  /**
   * Stop stream started with @startStream.
   */
  public void stopStream() {
    if (streaming) {
      streaming = false;
      stopStreamRtp();
    }
    if (!recordController.isRecording()) {
      if (audioInitialized) microphoneManager.stop();
      if (glInterface != null && Build.VERSION.SDK_INT >= 18) {
        glInterface.removeMediaCodecSurface();
        if (glInterface instanceof OffScreenGlThread) {
          glInterface.stop();
          cameraManager.stop();
          onPreview = false;
        }
      }
      videoEncoder.stop();
      if (audioInitialized) audioEncoder.stop();
      recordController.resetFormats();
    }
  }

  /**
   * Get supported preview resolutions of back camera in px.
   *
   * @return list of preview resolutions supported by back camera
   */
  public List<Camera.Size> getResolutionsBack() {
    return cameraManager.getPreviewSizeBack();
  }

  /**
   * Get supported preview resolutions of front camera in px.
   *
   * @return list of preview resolutions supported by front camera
   */
  public List<Camera.Size> getResolutionsFront() {
    return cameraManager.getPreviewSizeFront();
  }

  public List<int[]> getSupportedFps() {
    return cameraManager.getSupportedFps();
  }

  /**
   * Set a custom size of audio buffer input.
   * If you set 0 or less you can disable it to use library default value.
   * Must be called before of prepareAudio method.
   *
   * @param size in bytes. Recommended multiple of 1024 (2048, 4096, 8196, etc)
   */
  public void setAudioMaxInputSize(int size) {
    microphoneManager.setMaxInputSize(size);
  }

  /**
   * Mute microphone, can be called before, while and after stream.
   */
  public void disableAudio() {
    microphoneManager.mute();
  }

  /**
   * Enable a muted microphone, can be called before, while and after stream.
   */
  public void enableAudio() {
    microphoneManager.unMute();
  }

  /**
   * Get mute state of microphone.
   *
   * @return true if muted, false if enabled
   */
  public boolean isAudioMuted() {
    return microphoneManager.isMuted();
  }

  public int getBitrate() {
    return videoEncoder.getBitRate();
  }

  public int getResolutionValue() {
    return videoEncoder.getWidth() * videoEncoder.getHeight();
  }

  public int getStreamWidth() {
    return videoEncoder.getWidth();
  }

  public int getStreamHeight() {
    return videoEncoder.getHeight();
  }

  /**
   * Switch camera used. Can be called anytime
   *
   * @throws CameraOpenException If the other camera doesn't support same resolution.
   */
  public void switchCamera() throws CameraOpenException {
    if (isStreaming() || isRecording() || onPreview) {
      cameraManager.switchCamera();
    } else {
      cameraManager.setCameraFacing(getCameraFacing() ==  CameraHelper.Facing.FRONT ? CameraHelper.Facing.BACK : CameraHelper.Facing.FRONT);
    }
  }

  public void switchCamera(int cameraId) throws CameraOpenException {
    if (isStreaming() || onPreview) {
      cameraManager.switchCamera(cameraId);
    } else {
      cameraManager.setCameraSelect(cameraId);
    }
  }

  public void setExposure(int value) {
    cameraManager.setExposure(value);
  }

  public int getExposure() {
    return cameraManager.getExposure();
  }

  public int getMaxExposure() {
    return cameraManager.getMaxExposure();
  }

  public int getMinExposure() {
    return cameraManager.getMinExposure();
  }

  public void tapToFocus(View view, MotionEvent event) {
    cameraManager.tapToFocus(view, event);
  }

  public GlInterface getGlInterface() {
    if (glInterface != null) {
      return glInterface;
    } else {
      throw new RuntimeException("You can't do it. You are not using Opengl");
    }
  }

  /**
   * Set video bitrate of H264 in bits per second while stream.
   *
   * @param bitrate H264 in bits per second.
   */
  @RequiresApi(api = Build.VERSION_CODES.KITKAT)
  public void setVideoBitrateOnFly(int bitrate) {
    videoEncoder.setVideoBitrateOnFly(bitrate);
  }

  /**
   * Set limit FPS while stream. This will be override when you call to prepareVideo method. This
   * could produce a change in iFrameInterval.
   *
   * @param fps frames per second
   */
  public void setLimitFPSOnFly(int fps) {
    videoEncoder.setFps(fps);
  }

  /**
   * Get stream state.
   *
   * @return true if streaming, false if not streaming.
   */
  public boolean isStreaming() {
    return streaming;
  }

  /**
   * Get preview state.
   *
   * @return true if enabled, false if disabled.
   */
  public boolean isOnPreview() {
    return onPreview;
  }

  /**
   * Get record state.
   *
   * @return true if recording, false if not recoding.
   */
  public boolean isRecording() {
    return recordController.isRunning();
  }

  public void pauseRecord() {
    recordController.pauseRecord();
  }

  public void resumeRecord() {
    recordController.resumeRecord();
  }

  public RecordController.Status getRecordStatus() {
    return recordController.getStatus();
  }

  protected abstract void getAacDataRtp(ByteBuffer aacBuffer, MediaCodec.BufferInfo info);

  protected abstract void onSpsPpsVpsRtp(ByteBuffer sps, ByteBuffer pps, ByteBuffer vps);

  protected abstract void getH264DataRtp(ByteBuffer h264Buffer, MediaCodec.BufferInfo info);

  public void setRecordController(BaseRecordController recordController) {
    if (!isRecording()) this.recordController = recordController;
  }

  private final GetCameraData getCameraData = frame -> {
    videoEncoder.inputYUVData(frame);
  };

  private final GetMicrophoneData getMicrophoneData = frame -> {
    audioEncoder.inputPCMData(frame);
  };

  private final GetAacData getAacData = new GetAacData() {
    @Override
    public void getAacData(@NonNull ByteBuffer aacBuffer, @NonNull MediaCodec.BufferInfo info) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
        recordController.recordAudio(aacBuffer, info);
      }
      if (streaming) getAacDataRtp(aacBuffer, info);
    }

    @Override
    public void onAudioFormat(@NonNull MediaFormat mediaFormat) {
      recordController.setAudioFormat(mediaFormat);
    }
  };

  private final GetVideoData getVideoData = new GetVideoData() {
    @Override
    public void onSpsPpsVps(@NonNull ByteBuffer sps, @NonNull ByteBuffer pps, @Nullable ByteBuffer vps) {
      onSpsPpsVpsRtp(sps.duplicate(), pps.duplicate(), vps != null ? vps.duplicate() : null);
    }

    @Override
    public void getVideoData(@NonNull ByteBuffer h264Buffer, @NonNull MediaCodec.BufferInfo info) {
      fpsListener.calculateFps();
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
        recordController.recordVideo(h264Buffer, info);
      }
      if (streaming) getH264DataRtp(h264Buffer, info);
    }

    @Override
    public void onVideoFormat(@NonNull MediaFormat mediaFormat) {
      recordController.setVideoFormat(mediaFormat, !audioInitialized);
    }
  };

  public abstract StreamBaseClient getStreamClient();

  public void setVideoCodec(VideoCodec codec) {
    recordController.setVideoMime(
        codec == VideoCodec.H265 ? CodecUtil.H265_MIME : CodecUtil.H264_MIME);
    videoEncoder.setType(codec == VideoCodec.H265 ? CodecUtil.H265_MIME : CodecUtil.H264_MIME);
    setVideoCodecImp(codec);
  }

  protected abstract void setVideoCodecImp(VideoCodec codec);
}