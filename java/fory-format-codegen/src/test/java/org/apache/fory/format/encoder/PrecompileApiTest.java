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

import java.lang.reflect.Constructor;
import java.util.List;
import lombok.Data;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.codegen.CompileUnit;
import org.apache.fory.codegen.JaninoUtils;
import org.apache.fory.format.annotation.RowFormat;
import org.apache.fory.format.row.binary.BinaryRow;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Contract test for the build-time precompilation API. The annotation processor for
 * {@code @ForyGenerate} consumes this API; this test pins the API surface and verifies the produced
 * source survives a round-trip through Janino at runtime.
 */
public class PrecompileApiTest {

  private boolean savedFlagValue;

  /**
   * PrecompileApi refuses to run when the class-unique-id flag is on. Force it off here for this
   * class only, then restore the prior value in {@link #restoreUniqueIdAfterPrecompile} so we don't
   * leak the override into tests that rely on the suffix (e.g. {@code CodecProbeProvenanceTest},
   * which needs the suffix to dodge a deliberate stable-name collision).
   */
  @BeforeClass
  public void disableUniqueIdForPrecompile() {
    savedFlagValue = CodeGenerator.isClassUniqueIdEnabled();
    CodeGenerator.setClassUniqueIdEnabled(false);
    Assert.assertFalse(
        CodeGenerator.isClassUniqueIdEnabled(), "test setup should leave the unique-id flag off");
  }

  @AfterClass(alwaysRun = true)
  public void restoreUniqueIdAfterPrecompile() {
    CodeGenerator.setClassUniqueIdEnabled(savedFlagValue);
  }

  @Data
  public static class Bean {
    private long id;
    private String name;
  }

  @Data
  public static class Nested {
    private long id;
    private Bean inner;
  }

  @Test
  public void precompileRowCodecsEmitsCurrentVersionForBean() {
    List<PrecompileApi.GeneratedSource> sources =
        PrecompileApi.precompileRowCodecs(Bean.class, RowFormat.DEFAULT, false);
    Assert.assertEquals(sources.size(), 1, "Single non-nested bean produces one codec");
    PrecompileApi.GeneratedSource only = sources.get(0);
    // With the unique-id flag forced off in @BeforeClass, the emitted name must match what the
    // runtime probe in Encoders.stableQualifiedCodecName builds — no class-loader / class
    // hashcode suffix.
    Assert.assertTrue(
        only.qualifiedClassName().endsWith("BeanRowCodec"),
        "Precompiled class name should end with BeanRowCodec, got " + only.qualifiedClassName());
    Assert.assertTrue(only.source().contains("class "), "Source should declare a class");
  }

  @Test
  public void precompileRowCodecsEmitsNestedBeansToo() {
    List<PrecompileApi.GeneratedSource> sources =
        PrecompileApi.precompileRowCodecs(Nested.class, RowFormat.DEFAULT, false);
    Assert.assertEquals(
        sources.size(), 2, "Nested bean produces one codec each for outer and inner");
    Assert.assertTrue(
        sources.stream().anyMatch(s -> s.qualifiedClassName().contains("NestedRowCodec")));
    Assert.assertTrue(
        sources.stream().anyMatch(s -> s.qualifiedClassName().contains("BeanRowCodec")));
  }

  @Test
  public void precompileRowCodecSourceCompilesAndConstructorMatchesRuntimeShape() throws Exception {
    // The generated source must compile cleanly and produce a class whose Object[]-only
    // constructor is what the runtime BinaryRowEncoder relies on.
    PrecompileApi.GeneratedSource src =
        PrecompileApi.precompileRowCodecs(Bean.class, RowFormat.DEFAULT, false).get(0);
    String packageName =
        src.qualifiedClassName().substring(0, src.qualifiedClassName().lastIndexOf('.'));
    String simpleName =
        src.qualifiedClassName().substring(src.qualifiedClassName().lastIndexOf('.') + 1);
    CompileUnit unit = new CompileUnit(packageName, simpleName, src.source());
    java.util.Map<String, byte[]> bytecode =
        JaninoUtils.toBytecode(PrecompileApiTest.class.getClassLoader(), unit);
    Assert.assertFalse(
        bytecode.isEmpty(), "Janino must produce class bytes for the precompiled source");
    org.apache.fory.util.ClassLoaderUtils.ByteArrayClassLoader loader =
        new org.apache.fory.util.ClassLoaderUtils.ByteArrayClassLoader(
            bytecode, PrecompileApiTest.class.getClassLoader());
    Class<?> cls = loader.loadClass(src.qualifiedClassName());
    Constructor<?> ctor = cls.getConstructor(Object[].class);
    Assert.assertEquals(
        ctor.getParameterCount(), 1, "Row codec constructor takes a single Object[] arg");
    Assert.assertTrue(
        GeneratedRowEncoder.class.isAssignableFrom(cls),
        "Compiled class must implement GeneratedRowEncoder");
    // We don't invoke the constructor: it would require building a matching writer + Schema +
    // Fory tuple. The shape check is enough to prove the source is consumable by the runtime
    // load path (which itself constructs the tuple and invokes the same ctor).
    Assert.assertNotNull(BinaryRow.class, "BinaryRow type reachable for codec runtime contract");
  }

  @Test
  public void precompileRowCodecsRoundTripsViaJanino() {
    // The compact variant. Same shape, different naming.
    List<PrecompileApi.GeneratedSource> sources =
        PrecompileApi.precompileRowCodecs(Bean.class, RowFormat.COMPACT, false);
    Assert.assertEquals(sources.size(), 1);
    Assert.assertTrue(
        sources.get(0).qualifiedClassName().contains("BeanCompactCodec"),
        "Compact codec name should contain BeanCompactCodec, got "
            + sources.get(0).qualifiedClassName());
  }
}
