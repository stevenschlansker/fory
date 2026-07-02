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

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Whitebox verification that generated row codecs run through the public API with only
 * fory-format-runtime on the classpath. This module does not depend on fory-format-codegen at
 * compile or runtime (only the annotation processor runs at build time), so the fact that these
 * round-trips work is itself the proof that a @ForyGenerate factory executes without the
 * code-generation machinery.
 */
public class RuntimeOnlyCodecTest {

  @Test
  public void rowRoundTrips() {
    Point point = new Point(7, "runtime");
    byte[] bytes = PointCodecs.INSTANCE.encode(point);
    Assert.assertEquals(PointCodecs.INSTANCE.decode(bytes), point);
  }

  @Test
  public void arrayRoundTrips() {
    List<Point> points = Arrays.asList(new Point(1, "a"), new Point(2, "b"), new Point(3, "c"));
    MemoryBuffer buffer = MemoryUtils.buffer(32);
    PointCodecs.INSTANCE.appendList(buffer, points);
    Assert.assertEquals(PointCodecs.INSTANCE.readList(buffer), points);
  }

  @Test
  public void mapRoundTrips() {
    Map<String, Point> points = new LinkedHashMap<>();
    points.put("first", new Point(10, "x"));
    points.put("second", new Point(20, "y"));
    MemoryBuffer buffer = MemoryUtils.buffer(32);
    PointCodecs.INSTANCE.appendMap(buffer, points);
    Assert.assertEquals(PointCodecs.INSTANCE.readMap(buffer), points);
  }
}
