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

package com.pedro.encoder;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.pedro.encoder.utils.CodecUtil;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Created by pedro on 18/09/19.
 */
public abstract class BaseEncoder implements EncoderCallback {

  protected String TAG = "BaseEncoder";
  private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
  private HandlerThread handlerThread;
  protected BlockingQueue<Frame> queue = new ArrayBlockingQueue<>(80);
  protected MediaCodec codec;
  protected static long presentTimeUs;
  protected volatile boolean running = false;
  protected boolean isBufferMode = true;
  protected CodecUtil.Force force = CodecUtil.Force.FIRST_COMPATIBLE_FOUND;
  private MediaCodec.Callback callback;
  private long oldTimeStamp = 0L;
  protected boolean shouldReset = true;
  protected boolean prepared = false;
  private Handler handler;
  private EncoderErrorCallback encoderErrorCallback;

  public void setEncoderErrorCallback(EncoderErrorCallback encoderErrorCallback) {
    this.encoderErrorCallback = encoderErrorCallback;
  }

  public void restart() {
    start(false);
    initCodec();
  }

  public void start() {
    if (!prepared) throw new IllegalStateException(TAG + " not prepared yet. You must call prepare method before start it");
    if (presentTimeUs == 0) {
      presentTimeUs = System.nanoTime() / 1000;
    }
    start(true);
    initCodec();
  }

  protected void setCallback() {
    handlerThread = new HandlerThread(TAG);
    handlerThread.start();
    handler = new Handler(handlerThread.getLooper());
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      createAsyncCallback();
      codec.setCallback(callback, handler);
    }
  }

  private void initCodec() {
    codec.start();
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      handler.post(() -> {
        while (running) {
          try {
            getDataFromEncoder();
          } catch (IllegalStateException e) {
            Log.i(TAG, "Encoding error", e);
            reloadCodec(e);
          }
        }
      });
    }
    running = true;
  }

  public abstract void reset();

  public abstract void start(boolean resetTs);

  protected abstract void stopImp();

  protected void fixTimeStamp(MediaCodec.BufferInfo info) {
    if (oldTimeStamp > info.presentationTimeUs) {
      info.presentationTimeUs = oldTimeStamp;
    } else {
      oldTimeStamp = info.presentationTimeUs;
    }
  }

  private void reloadCodec(IllegalStateException e) {
    //Sometimes encoder crash, we will try recover it. Reset encoder a time if crash
    EncoderErrorCallback callback = encoderErrorCallback;
    if (callback != null) {
      shouldReset = callback.onEncodeError(TAG, e);
    }
    if (shouldReset) {
      Log.e(TAG, "Encoder crashed, trying to recover it");
      reset();
    }
  }

  public void stop() {
    stop(true);
  }

  public void stop(boolean resetTs) {
    if (resetTs) {
      presentTimeUs = 0;
    }
    running = false;
    stopImp();
    if (handlerThread != null) {
      if (handlerThread.getLooper() != null) {
        if (handlerThread.getLooper().getThread() != null) {
          handlerThread.getLooper().getThread().interrupt();
        }
        handlerThread.getLooper().quit();
      }
      handlerThread.quit();
      if (codec != null) {
        try {
          codec.flush();
        } catch (IllegalStateException ignored) { }
      }
      //wait for thread to die for 500ms.
      try {
        handlerThread.getLooper().getThread().join(500);
      } catch (Exception ignored) { }
    }
    queue.clear();
    queue = new ArrayBlockingQueue<>(80);
    try {
      codec.stop();
      codec.release();
      codec = null;
    } catch (IllegalStateException | NullPointerException e) {
      codec = null;
    }
    prepared = false;
    oldTimeStamp = 0L;
  }

  protected abstract MediaCodecInfo chooseEncoder(String mime);

  protected void getDataFromEncoder() throws IllegalStateException {
    if (isBufferMode) {
      int inBufferIndex = codec.dequeueInputBuffer(0);
      if (inBufferIndex >= 0) {
        inputAvailable(codec, inBufferIndex);
      }
    }
    while (running) {
      int outBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0);
      if (outBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
        MediaFormat mediaFormat = codec.getOutputFormat();
        formatChanged(codec, mediaFormat);
      } else if (outBufferIndex >= 0) {
        outputAvailable(codec, outBufferIndex, bufferInfo);
      } else {
        break;
      }
    }
  }

  protected abstract Frame getInputFrame() throws InterruptedException;

  protected abstract long calculatePts(Frame frame, long presentTimeUs);

  private void processInput(@NonNull ByteBuffer byteBuffer, @NonNull MediaCodec mediaCodec,
      int inBufferIndex) throws IllegalStateException {
    try {
      Frame frame = getInputFrame();
      while (frame == null) frame = getInputFrame();
      byteBuffer.clear();
      int size = Math.max(0, Math.min(frame.getSize(), byteBuffer.remaining()) - frame.getOffset());
      byteBuffer.put(frame.getBuffer(), frame.getOffset(), size);
      long pts = calculatePts(frame, presentTimeUs);
      mediaCodec.queueInputBuffer(inBufferIndex, 0, size, pts, 0);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (NullPointerException | IndexOutOfBoundsException e) {
      Log.i(TAG, "Encoding error", e);
    }
  }

  protected abstract void checkBuffer(@NonNull ByteBuffer byteBuffer,
      @NonNull MediaCodec.BufferInfo bufferInfo);

  protected abstract void sendBuffer(@NonNull ByteBuffer byteBuffer,
      @NonNull MediaCodec.BufferInfo bufferInfo);

  private void processOutput(@NonNull ByteBuffer byteBuffer, @NonNull MediaCodec mediaCodec,
      int outBufferIndex, @NonNull MediaCodec.BufferInfo bufferInfo) throws IllegalStateException {
    checkBuffer(byteBuffer, bufferInfo);
    sendBuffer(byteBuffer, bufferInfo);
    mediaCodec.releaseOutputBuffer(outBufferIndex, false);
  }

  public void setForce(CodecUtil.Force force) {
    this.force = force;
  }

  public boolean isRunning() {
    return running;
  }

  @Override
  public void inputAvailable(@NonNull MediaCodec mediaCodec, int inBufferIndex)
      throws IllegalStateException {
    ByteBuffer byteBuffer;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      byteBuffer = mediaCodec.getInputBuffer(inBufferIndex);
    } else {
      byteBuffer = mediaCodec.getInputBuffers()[inBufferIndex];
    }
    processInput(byteBuffer, mediaCodec, inBufferIndex);
  }

  @Override
  public void outputAvailable(@NonNull MediaCodec mediaCodec, int outBufferIndex,
      @NonNull MediaCodec.BufferInfo bufferInfo) throws IllegalStateException {
    ByteBuffer byteBuffer;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      byteBuffer = mediaCodec.getOutputBuffer(outBufferIndex);
    } else {
      byteBuffer = mediaCodec.getOutputBuffers()[outBufferIndex];
    }
    processOutput(byteBuffer, mediaCodec, outBufferIndex, bufferInfo);
  }

  @RequiresApi(api = Build.VERSION_CODES.M)
  private void createAsyncCallback() {
    callback = new MediaCodec.Callback() {
      @Override
      public void onInputBufferAvailable(@NonNull MediaCodec mediaCodec, int inBufferIndex) {
        try {
          inputAvailable(mediaCodec, inBufferIndex);
        } catch (IllegalStateException e) {
          Log.i(TAG, "Encoding error", e);
          reloadCodec(e);
        }
      }

      @Override
      public void onOutputBufferAvailable(@NonNull MediaCodec mediaCodec, int outBufferIndex,
          @NonNull MediaCodec.BufferInfo bufferInfo) {
        try {
          outputAvailable(mediaCodec, outBufferIndex, bufferInfo);
        } catch (IllegalStateException e) {
          Log.i(TAG, "Encoding error", e);
          reloadCodec(e);
        }
      }

      @Override
      public void onError(@NonNull MediaCodec mediaCodec, @NonNull MediaCodec.CodecException e) {
        Log.e(TAG, "Error", e);
        EncoderErrorCallback callback = encoderErrorCallback;
        if (callback != null) callback.onCodecError(TAG, e);
      }

      @Override
      public void onOutputFormatChanged(@NonNull MediaCodec mediaCodec,
          @NonNull MediaFormat mediaFormat) {
        formatChanged(mediaCodec, mediaFormat);
      }
    };
  }
}
