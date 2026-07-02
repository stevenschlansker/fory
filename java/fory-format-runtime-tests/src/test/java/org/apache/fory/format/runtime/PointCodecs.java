/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.fory.format.runtime;

import java.util.List;
import java.util.Map;
import org.apache.fory.format.annotation.ForyGenerate;
import org.apache.fory.format.encoder.RowCodecs;
import org.apache.fory.memory.MemoryBuffer;

/**
 * A {@code @ForyGenerate} factory covering the row, array, and map shapes. The processor emits
 * {@code PointCodecs_Fory} and the precompiled codec classes at build time; at runtime the {@code
 * INSTANCE} resolves through {@link RowCodecs#factory}, which needs only {@code
 * fory-format-runtime}.
 */
@ForyGenerate
public interface PointCodecs {
  PointCodecs INSTANCE = RowCodecs.factory(PointCodecs.class);

  byte[] encode(Point point);

  Point decode(byte[] bytes);

  void appendList(MemoryBuffer buffer, List<Point> points);

  List<Point> readList(MemoryBuffer buffer);

  void appendMap(MemoryBuffer buffer, Map<String, Point> points);

  Map<String, Point> readMap(MemoryBuffer buffer);
}
