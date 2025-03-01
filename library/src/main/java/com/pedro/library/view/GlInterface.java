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

package com.pedro.library.view;

import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.view.Surface;

import com.pedro.encoder.input.gl.render.filters.BaseFilterRender;

public interface GlInterface {

  /**
   * Set video encoder size use to Opengl
   * @param width video encoder width in px
   * @param height video encoder height in px
   */
  void setEncoderSize(int width, int height);

  Point getEncoderSize();
  /**
   * Get SurfaceTexture generated by Opengl. This should be called after start render.
   * @return surface texture generated by Opengl.
   */
  SurfaceTexture getSurfaceTexture();

  /**
   * Get Surface generated by Opengl. This should be called after start render.
   * @return surface texture generated by Opengl.
   */
  Surface getSurface();

  /**
   * Set surface from MediaCodec class to Opengl.
   * This surface is used to copy pixels from Opengl surface to this surface and encode this pixels.
   * @param surface surface created from MediaCodec.
   */
  void addMediaCodecSurface(Surface surface);

  /**
   * Remove surface generated from MediaCodec.
   */
  void removeMediaCodecSurface();

  /**
   * Capture an Image from Opengl.
   *
   * @param takePhotoCallback callback where you will get your image like a bitmap.
   */
  void takePhoto(TakePhotoCallback takePhotoCallback);

  /**
   * Replaces the filter at the specified position with the specified filter.
   * You can modify filter's parameters after set it to stream.
   *
   * @param filterPosition filter position
   * @param baseFilterRender filter to set
   */
  void setFilter(int filterPosition, BaseFilterRender baseFilterRender);

  /**
   * Appends the specified filter to the end.
   * You can modify filter's parameters after set it to stream.
   *
   * @param baseFilterRender filter to add
   */
  void addFilter(BaseFilterRender baseFilterRender);

  /**
   * Inserts the specified filter at the specified position.
   * You can modify filter's parameters after set it to stream.
   *
   * @param filterPosition filter position
   * @param baseFilterRender filter to set
   */
  void addFilter(int filterPosition, BaseFilterRender baseFilterRender);

  /**
   * Remove all filters
   */
  void clearFilters();

  /**
   * Remove the filter at the specified position.
   *
   * @param filterPosition position of filter to remove
   */
  void removeFilter(int filterPosition);

  /**
   * Removes the first occurrence of the specified element from this list, if it is present.
   *
   * @param baseFilterRender filter to remove
   */
  void removeFilter(BaseFilterRender baseFilterRender);
  /**
   * @return number of filters
   */
  int filtersCount();

  /**
   * Replace the filter in position 0 or add the filter if list is empty.
   * You can modify filter's parameters after set it to stream.
   *
   * @param baseFilterRender filter to set.
   */
  void setFilter(BaseFilterRender baseFilterRender);
  
  /**
   * Enable or disable Anti aliasing (This method use FXAA).
   *
   * @param AAEnabled true is AA enabled, false is AA disabled. False by default.
   */
  void enableAA(boolean AAEnabled);

  void setRotation(int rotation);

  /**
   * @param rotation change stream rotation on fly. No effect to preview
   */
  void setStreamRotation(int rotation);

  /**
   * When true, flips only the stream horizontally
   */
  void setIsStreamHorizontalFlip(boolean flip);

  /**
   * When true, flips only the stream vertically
   */
  void setIsStreamVerticalFlip(boolean flip);

  /**
   * When true, flips only the preview horizontally
   */
  void setIsPreviewHorizontalFlip(boolean flip);

  /**
   * When true, flips only the preview vertically
   */
  void setIsPreviewVerticalFlip(boolean flip);

  /**
   * Get Anti alias is enabled.
   * @return true is enabled, false is disabled.
   */
  boolean isAAEnabled();

  /**
   * INTERNAL METHOD.
   *
   * Start Opengl rendering.
   *
   */
  void start();

  /**
   * INTERNAL METHOD.
   *
   * Stop Opengl rendering.
   */
  void stop();

  void setFps(int fps);

  /**
   * This produce send black image all time.
   * This affect to stream and record result.
   */
  void muteVideo();

  void unMuteVideo();

  boolean isVideoMuted();

  /**
   * @param force render last frame.
   * This is useful with Display mode to continue producing video frames.
   * Not recommendable in others modes.
   */
  void setForceRender(boolean force);
}
