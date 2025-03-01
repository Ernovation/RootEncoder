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

package com.pedro.srt.srt.packets.data

import java.io.IOException

/**
 * Created by pedro on 23/8/23.
 */
enum class PacketPosition(val value: Int) {
  FIRST(2), MIDDLE(0), LAST(1), SINGLE(3);

  companion object {
    infix fun from(value: Int): PacketPosition = PacketPosition.values().firstOrNull { it.value == value } ?: throw IOException("unknown packet position: $value")
  }
}