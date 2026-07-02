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

package org.apache.fory.format.encoder;

import lombok.Data;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Stable-name precompile probe must refuse a user class that happens to occupy the stable codec
 * name without implementing the expected {@code Generated*Encoder} interface; the build path then
 * falls through to runtime codegen. The colliding class is {@link
 * CodecProbeProvenanceTest_CollisionBeanRowCodec}, a sibling top-level class in this package.
 */
public class CodecProbeProvenanceTest {

  @Data
  public static class CollisionBean {
    private long id;
    private String name;
  }

  @Test
  public void buildIgnoresNonForyClassAtStableName() {
    // Sanity-check: the colliding class is reachable from the test classloader under the stable
    // name the probe will look up. The bean is an inner class, so '$' -> '_' substitution applies.
    String stableName =
        Encoders.stableQualifiedCodecName(CollisionBean.class, "", DefaultCodecFormat.INSTANCE);
    Assert.assertEquals(
        stableName,
        "org.apache.fory.format.encoder.CodecProbeProvenanceTest_CollisionBeanRowCodec");
    Class<?> collision;
    try {
      collision = Class.forName(stableName);
    } catch (ClassNotFoundException e) {
      throw new AssertionError("Collision class not on classpath", e);
    }
    Assert.assertFalse(
        GeneratedRowEncoder.class.isAssignableFrom(collision),
        "Collision class must not implement GeneratedRowEncoder");

    // Build path falls through to codegen and produces a working encoder.
    RowEncoder<CollisionBean> encoder = Encoders.buildBeanCodec(CollisionBean.class).build().get();
    CollisionBean in = new CollisionBean();
    in.setId(42L);
    in.setName("ada");
    CollisionBean out = encoder.decode(encoder.encode(in));
    Assert.assertEquals(out.getId(), 42L);
    Assert.assertEquals(out.getName(), "ada");
  }
}
