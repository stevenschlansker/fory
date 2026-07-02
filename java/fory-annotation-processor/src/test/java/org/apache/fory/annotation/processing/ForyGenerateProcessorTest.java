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

package org.apache.fory.annotation.processing;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.apache.fory.format.encoder.ArrayEncoder;
import org.apache.fory.format.encoder.MapEncoder;
import org.apache.fory.format.encoder.RowEncoder;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Two-stage compile tests for @ForyGenerate: stage 1 compiles the bean classes so their .class
 * files exist on the classpath; stage 2 compiles the @ForyGenerate interface with the processor,
 * which loads the beans via reflection while emitting the codec source. Generated factory and codec
 * classes are then loaded and exercised end-to-end.
 */
public class ForyGenerateProcessorTest {

  @Test
  public void rowEncoderRoundTripsBean() throws Exception {
    TwoStageResult result =
        compileWithBeans(
            beanSource(),
            "test.MyCodecs",
            "package test;\n"
                + "import java.util.List;\n"
                + "import java.util.Map;\n"
                + "import org.apache.fory.format.annotation.ForyGenerate;\n"
                + "import org.apache.fory.format.encoder.RowEncoder;\n"
                + "import org.apache.fory.memory.MemoryBuffer;\n"
                + "@ForyGenerate\n"
                + "public interface MyCodecs {\n"
                + "  RowEncoder<MyBean> beanEncoder();\n"
                + "  byte[] encodeBean(MyBean bean);\n"
                + "  MyBean decodeBean(byte[] bytes);\n"
                + "  int appendBean(MemoryBuffer buf, MyBean bean);\n"
                + "  MyBean readNextBean(MemoryBuffer buf);\n"
                + "}\n");
    Assert.assertTrue(result.success, result.diagnostics());
    try (URLClassLoader loader = result.classLoader()) {
      Object codecs = invokeStaticInstance(loader, "test.MyCodecs_Fory");
      Class<?> beanClass = loader.loadClass("test.MyBean");
      Object bean = beanClass.getConstructor(int.class, String.class).newInstance(42, "fory");

      byte[] bytes = (byte[]) invoke(codecs, "encodeBean", new Class<?>[] {beanClass}, bean);
      Object roundTrip = invoke(codecs, "decodeBean", new Class<?>[] {byte[].class}, bytes);
      Assert.assertEquals(getField(beanClass, roundTrip, "id"), 42);
      Assert.assertEquals(getField(beanClass, roundTrip, "name"), "fory");

      MemoryBuffer buf = MemoryUtils.buffer(16);
      Integer written =
          (Integer)
              invoke(
                  codecs, "appendBean", new Class<?>[] {MemoryBuffer.class, beanClass}, buf, bean);
      Assert.assertTrue(written > 0);
      Object readBack = invoke(codecs, "readNextBean", new Class<?>[] {MemoryBuffer.class}, buf);
      Assert.assertEquals(getField(beanClass, readBack, "id"), 42);
      Assert.assertEquals(getField(beanClass, readBack, "name"), "fory");

      Object encoder = invoke(codecs, "beanEncoder", new Class<?>[] {});
      Assert.assertTrue(encoder instanceof RowEncoder);
    }
  }

  /**
   * The {@code INSTANCE} constant pattern recommended in the {@link
   * org.apache.fory.format.annotation.ForyGenerate} javadoc resolves the generated factory via
   * {@code RowCodecs.factory(MyCodecs.class)}, so callers never type the {@code _Fory} suffix. This
   * pins that wiring end-to-end against a compiled interface that exercises both shapes.
   */
  @Test
  public void encodersFactoryResolvesGeneratedSingleton() throws Exception {
    TwoStageResult result =
        compileWithBeans(
            beanSource(),
            "test.MyCodecs",
            "package test;\n"
                + "import org.apache.fory.format.annotation.ForyGenerate;\n"
                + "import org.apache.fory.format.encoder.RowCodecs;\n"
                + "@ForyGenerate\n"
                + "public interface MyCodecs {\n"
                + "  MyCodecs INSTANCE = RowCodecs.factory(MyCodecs.class);\n"
                + "  byte[] encodeBean(MyBean bean);\n"
                + "  MyBean decodeBean(byte[] bytes);\n"
                + "}\n");
    Assert.assertTrue(result.success, result.diagnostics());
    try (URLClassLoader loader = result.classLoader()) {
      ClassLoader prior = Thread.currentThread().getContextClassLoader();
      Thread.currentThread().setContextClassLoader(loader);
      try {
        Class<?> iface = loader.loadClass("test.MyCodecs");
        Object codecs = iface.getField("INSTANCE").get(null);
        Assert.assertNotNull(codecs, "INSTANCE must resolve through RowCodecs.factory");
        Class<?> generated = loader.loadClass("test.MyCodecs_Fory");
        Assert.assertSame(
            codecs.getClass(), generated, "RowCodecs.factory must return the generated _Fory");

        Class<?> beanClass = loader.loadClass("test.MyBean");
        Object bean = beanClass.getConstructor(int.class, String.class).newInstance(42, "fory");
        byte[] bytes = (byte[]) invoke(codecs, "encodeBean", new Class<?>[] {beanClass}, bean);
        Object roundTrip = invoke(codecs, "decodeBean", new Class<?>[] {byte[].class}, bytes);
        Assert.assertEquals(getField(beanClass, roundTrip, "id"), 42);
        Assert.assertEquals(getField(beanClass, roundTrip, "name"), "fory");
      } finally {
        Thread.currentThread().setContextClassLoader(prior);
      }
    }
  }

  @Test
  public void arrayEncoderRoundTripsList() throws Exception {
    TwoStageResult result =
        compileWithBeans(
            beanSource(),
            "test.MyCodecs",
            "package test;\n"
                + "import java.util.List;\n"
                + "import org.apache.fory.format.annotation.ForyGenerate;\n"
                + "import org.apache.fory.format.encoder.ArrayEncoder;\n"
                + "import org.apache.fory.memory.MemoryBuffer;\n"
                + "@ForyGenerate\n"
                + "public interface MyCodecs {\n"
                + "  ArrayEncoder<List<MyBean>> listEncoder();\n"
                + "  int appendList(MemoryBuffer buf, List<MyBean> beans);\n"
                + "  List<MyBean> readList(MemoryBuffer buf);\n"
                + "}\n");
    Assert.assertTrue(result.success, result.diagnostics());
    try (URLClassLoader loader = result.classLoader()) {
      Object codecs = invokeStaticInstance(loader, "test.MyCodecs_Fory");
      Class<?> beanClass = loader.loadClass("test.MyBean");
      Object b1 = beanClass.getConstructor(int.class, String.class).newInstance(1, "a");
      Object b2 = beanClass.getConstructor(int.class, String.class).newInstance(2, "b");
      List<Object> beans = Arrays.asList(b1, b2);

      MemoryBuffer buf = MemoryUtils.buffer(64);
      invoke(codecs, "appendList", new Class<?>[] {MemoryBuffer.class, List.class}, buf, beans);
      Object readBack = invoke(codecs, "readList", new Class<?>[] {MemoryBuffer.class}, buf);
      Assert.assertTrue(readBack instanceof List);
      List<?> list = (List<?>) readBack;
      Assert.assertEquals(list.size(), 2);
      Assert.assertEquals(getField(beanClass, list.get(0), "id"), 1);
      Assert.assertEquals(getField(beanClass, list.get(1), "name"), "b");

      Object enc = invoke(codecs, "listEncoder", new Class<?>[] {});
      Assert.assertTrue(enc instanceof ArrayEncoder);
    }
  }

  @Test
  public void mapEncoderRoundTripsMap() throws Exception {
    TwoStageResult result =
        compileWithBeans(
            beanSource(),
            "test.MyCodecs",
            "package test;\n"
                + "import java.util.Map;\n"
                + "import org.apache.fory.format.annotation.ForyGenerate;\n"
                + "import org.apache.fory.format.encoder.MapEncoder;\n"
                + "import org.apache.fory.memory.MemoryBuffer;\n"
                + "@ForyGenerate\n"
                + "public interface MyCodecs {\n"
                + "  MapEncoder<Map<Integer, MyBean>> mapEncoder();\n"
                + "  int appendMap(MemoryBuffer buf, Map<Integer, MyBean> m);\n"
                + "  Map<Integer, MyBean> readMap(MemoryBuffer buf);\n"
                + "}\n");
    Assert.assertTrue(result.success, result.diagnostics());
    try (URLClassLoader loader = result.classLoader()) {
      Object codecs = invokeStaticInstance(loader, "test.MyCodecs_Fory");
      Class<?> beanClass = loader.loadClass("test.MyBean");
      Map<Integer, Object> input = new LinkedHashMap<>();
      input.put(1, beanClass.getConstructor(int.class, String.class).newInstance(1, "a"));
      input.put(2, beanClass.getConstructor(int.class, String.class).newInstance(2, "b"));

      MemoryBuffer buf = MemoryUtils.buffer(64);
      invoke(codecs, "appendMap", new Class<?>[] {MemoryBuffer.class, Map.class}, buf, input);
      Object readBack = invoke(codecs, "readMap", new Class<?>[] {MemoryBuffer.class}, buf);
      Assert.assertTrue(readBack instanceof Map);
      Map<?, ?> out = (Map<?, ?>) readBack;
      Assert.assertEquals(out.size(), 2);

      Object enc = invoke(codecs, "mapEncoder", new Class<?>[] {});
      Assert.assertTrue(enc instanceof MapEncoder);
    }
  }

  @Test
  public void compactFormatRoundTripsBean() throws Exception {
    TwoStageResult result =
        compileWithBeans(
            beanSource(),
            "test.MyCodecs",
            "package test;\n"
                + "import org.apache.fory.format.annotation.ForyGenerate;\n"
                + "import org.apache.fory.format.annotation.RowFormat;\n"
                + "@ForyGenerate(format = RowFormat.COMPACT)\n"
                + "public interface MyCodecs {\n"
                + "  byte[] encodeBean(MyBean bean);\n"
                + "  MyBean decodeBean(byte[] bytes);\n"
                + "}\n");
    Assert.assertTrue(result.success, result.diagnostics());
    try (URLClassLoader loader = result.classLoader()) {
      Object codecs = invokeStaticInstance(loader, "test.MyCodecs_Fory");
      Class<?> beanClass = loader.loadClass("test.MyBean");
      Object bean = beanClass.getConstructor(int.class, String.class).newInstance(7, "compact");
      byte[] bytes = (byte[]) invoke(codecs, "encodeBean", new Class<?>[] {beanClass}, bean);
      Object roundTrip = invoke(codecs, "decodeBean", new Class<?>[] {byte[].class}, bytes);
      Assert.assertEquals(getField(beanClass, roundTrip, "id"), 7);
      Assert.assertEquals(getField(beanClass, roundTrip, "name"), "compact");
    }
  }

  @Test
  public void runtimePicksUpPreCompiledRowCodec() throws Exception {
    // After the processor runs, the runtime probe in Encoders.loadOrGenRowCodecClass should find
    // the precompiled MyBeanRowCodec on the classpath and use it instead of going through Janino.
    // We verify this by looking up the precompiled class on the result classloader and confirming
    // it loads.
    TwoStageResult result =
        compileWithBeans(
            beanSource(),
            "test.MyCodecs",
            "package test;\n"
                + "import org.apache.fory.format.annotation.ForyGenerate;\n"
                + "import org.apache.fory.format.encoder.RowEncoder;\n"
                + "@ForyGenerate\n"
                + "public interface MyCodecs {\n"
                + "  RowEncoder<MyBean> beanEncoder();\n"
                + "}\n");
    Assert.assertTrue(result.success, result.diagnostics());
    try (URLClassLoader loader = result.classLoader()) {
      // The expected class name is determined by BaseBinaryEncoderBuilder.codecClassName with the
      // unique-id flag disabled at processor time.
      Class<?> precompiled = loader.loadClass("test.MyBeanRowCodec");
      Assert.assertNotNull(precompiled);
    }
  }

  @Test
  public void evolutionRoundTripsAcrossSchemaVersions() throws Exception {
    Map<String, String> beans = new HashMap<>();
    // Producer bean has only the original v1 fields.
    beans.put(
        "test.UserV1",
        "package test;\n"
            + "import org.apache.fory.format.annotation.ForySchema;\n"
            + "@ForySchema\n"
            + "public class UserV1 {\n"
            + "  private long id;\n"
            + "  private String name;\n"
            + "  public UserV1() {}\n"
            + "  public UserV1(long id, String name) { this.id = id; this.name = name; }\n"
            + "  public long getId() { return id; }\n"
            + "  public void setId(long id) { this.id = id; }\n"
            + "  public String getName() { return name; }\n"
            + "  public void setName(String name) { this.name = name; }\n"
            + "}\n");
    // Consumer bean adds an email field at v2; @ForySchema makes evolution opt-in.
    beans.put(
        "test.UserV2",
        "package test;\n"
            + "import org.apache.fory.format.annotation.ForySchema;\n"
            + "import org.apache.fory.format.annotation.ForyVersion;\n"
            + "@ForySchema\n"
            + "public class UserV2 {\n"
            + "  private long id;\n"
            + "  private String name;\n"
            + "  @ForyVersion(since = 2) private String email;\n"
            + "  public UserV2() {}\n"
            + "  public long getId() { return id; }\n"
            + "  public void setId(long id) { this.id = id; }\n"
            + "  public String getName() { return name; }\n"
            + "  public void setName(String name) { this.name = name; }\n"
            + "  public String getEmail() { return email; }\n"
            + "  public void setEmail(String email) { this.email = email; }\n"
            + "}\n");
    TwoStageResult result =
        compileWithBeans(
            beans,
            "test.Codecs",
            "package test;\n"
                + "import org.apache.fory.format.annotation.ForyGenerate;\n"
                + "@ForyGenerate(evolution = true)\n"
                + "public interface Codecs {\n"
                + "  byte[] encode(UserV2 u);\n"
                + "  UserV2 decode(byte[] bytes);\n"
                + "}\n");
    Assert.assertTrue(result.success, result.diagnostics());
    try (URLClassLoader loader = result.classLoader()) {
      // The evolution factory resolves projection codecs lazily, on the first decode of an older
      // version's hash, via the thread context classloader. Keep the generated-class loader active
      // across both encode and decode so that lazy lookup can see test.UserV2RowCodec_V1, mirroring
      // how a runtime-only application runs with its own classloader as the context.
      ClassLoader prior = Thread.currentThread().getContextClassLoader();
      Thread.currentThread().setContextClassLoader(loader);
      try {
        // Write a v1 payload at runtime using the producer-side bean (no email field).
        Class<?> userV1 = loader.loadClass("test.UserV1");
        Object v1 = userV1.getConstructor(long.class, String.class).newInstance(7L, "alice");
        byte[] v1Bytes = encodeWithRuntimeBuilder(userV1, v1);
        // Decode through the precompiled, evolution-aware factory.
        Object codecs = invokeStaticInstance(loader, "test.Codecs_Fory");
        Class<?> userV2 = loader.loadClass("test.UserV2");
        Object decoded = invoke(codecs, "decode", new Class<?>[] {byte[].class}, v1Bytes);
        Assert.assertEquals(getField(userV2, decoded, "id"), 7L);
        Assert.assertEquals(getField(userV2, decoded, "name"), "alice");
        // Email field is not present in the v1 payload; evolution leaves it null.
        Assert.assertNull(getField(userV2, decoded, "email"));
      } finally {
        Thread.currentThread().setContextClassLoader(prior);
      }
    }
  }

  @Test
  public void customCodecCrossModule() throws Exception {
    // Simulates a typical user setup: codec lives in module A (compiled with the processor, so
    // META-INF/fory/custom-codecs.txt is written), factory lives in module B (depends on A,
    // discovers the codec via classpath descriptor). The test runs javac twice — stage 1 emits
    // the descriptor, stage 2 reads it.
    Path stageA = Files.createTempDirectory("fory-codec-stageA");
    Path stageACls = stageA.resolve("classes");
    Path stageASrc = stageA.resolve("src");
    Files.createDirectories(stageACls);
    Files.createDirectories(stageASrc.resolve("a"));
    Files.write(
        stageASrc.resolve("a/Money.java"),
        ("package a;\n"
                + "public final class Money {\n"
                + "  public final long cents;\n"
                + "  public Money(long cents) { this.cents = cents; }\n"
                + "}\n")
            .getBytes(StandardCharsets.UTF_8));
    Files.write(
        stageASrc.resolve("a/MoneyCodec.java"),
        ("package a;\n"
                + "import org.apache.fory.format.annotation.ForyCustomCodec;\n"
                + "import org.apache.fory.format.encoder.CustomCodec;\n"
                + "import org.apache.fory.format.type.Field;\n"
                + "import org.apache.fory.reflect.TypeRef;\n"
                + "@ForyCustomCodec\n"
                + "public final class MoneyCodec implements CustomCodec<Money, Long> {\n"
                + "  @Override public Field getForyField(String n) { return null; }\n"
                + "  @Override public TypeRef<Long> encodedType() { return TypeRef.of(Long.class); }\n"
                + "  @Override public Long encode(Money v) { return v.cents; }\n"
                + "  @Override public Money decode(Long v) { return new Money(v); }\n"
                + "}\n")
            .getBytes(StandardCharsets.UTF_8));
    compileWithProcessor(
        new Path[] {stageASrc.resolve("a/Money.java"), stageASrc.resolve("a/MoneyCodec.java")},
        stageACls,
        System.getProperty("java.class.path"));
    Assert.assertTrue(
        Files.exists(stageACls.resolve("META-INF/fory/custom-codecs.txt")),
        "Stage 1 must write the codec descriptor");

    Path stageB = Files.createTempDirectory("fory-codec-stageB");
    Path stageBCls = stageB.resolve("classes");
    Path stageBSrc = stageB.resolve("src");
    Path stageBGen = stageB.resolve("gen");
    Files.createDirectories(stageBCls);
    Files.createDirectories(stageBSrc.resolve("b"));
    Files.createDirectories(stageBGen);
    Files.write(
        stageBSrc.resolve("b/Order.java"),
        ("package b;\n"
                + "import a.Money;\n"
                + "public class Order {\n"
                + "  private long id;\n"
                + "  private Money price;\n"
                + "  public Order() {}\n"
                + "  public Order(long id, Money price) { this.id = id; this.price = price; }\n"
                + "  public long getId() { return id; }\n"
                + "  public void setId(long id) { this.id = id; }\n"
                + "  public Money getPrice() { return price; }\n"
                + "  public void setPrice(Money p) { this.price = p; }\n"
                + "}\n")
            .getBytes(StandardCharsets.UTF_8));
    Files.write(
        stageBSrc.resolve("b/Codecs.java"),
        ("package b;\n"
                + "import org.apache.fory.format.annotation.ForyGenerate;\n"
                + "@ForyGenerate\n"
                + "public interface Codecs {\n"
                + "  byte[] encode(Order o);\n"
                + "  Order decode(byte[] bytes);\n"
                + "}\n")
            .getBytes(StandardCharsets.UTF_8));
    String stageBClasspath =
        stageACls + java.io.File.pathSeparator + System.getProperty("java.class.path");

    // Stage B's javac must compile the bean class first (without processor) so the @ForyGenerate
    // processor can reflectively access it. Then run the processor on the factory source.
    compileNoProcessor(new Path[] {stageBSrc.resolve("b/Order.java")}, stageBCls, stageBClasspath);
    String stageBClasspathWithBeans = stageBCls + java.io.File.pathSeparator + stageBClasspath;

    URLClassLoader stageARuntime =
        new URLClassLoader(
            new java.net.URL[] {stageACls.toUri().toURL(), stageBCls.toUri().toURL()},
            ForyGenerateProcessorTest.class.getClassLoader());
    ClassLoader prior = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(stageARuntime);
    boolean success;
    DiagnosticCollector<JavaFileObject> stageBDiag = new DiagnosticCollector<>();
    try {
      JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
      try (StandardJavaFileManager fm =
          compiler.getStandardFileManager(stageBDiag, null, StandardCharsets.UTF_8)) {
        Iterable<? extends JavaFileObject> units =
            fm.getJavaFileObjectsFromFiles(
                Collections.singletonList(stageBSrc.resolve("b/Codecs.java").toFile()));
        List<String> options =
            Arrays.asList(
                "-classpath", stageBClasspathWithBeans,
                "-d", stageBCls.toString(),
                "-s", stageBGen.toString());
        JavaCompiler.CompilationTask task =
            compiler.getTask(null, fm, stageBDiag, options, null, units);
        task.setProcessors(Collections.singletonList(new ForyGenerateProcessor(stageARuntime)));
        success = task.call();
      }
    } finally {
      Thread.currentThread().setContextClassLoader(prior);
    }
    StringBuilder diagText = new StringBuilder();
    for (Diagnostic<? extends JavaFileObject> d : stageBDiag.getDiagnostics()) {
      diagText.append(d).append('\n');
    }
    Assert.assertTrue(success, diagText.toString());

    // The Order row codec must dispatch Money through the registered codec via a static field
    // cached from CustomTypeEncoderRegistry, not embed a MoneyRowCodec.
    String orderRowCodecSrc =
        new String(
            Files.readAllBytes(stageBGen.resolve("b/OrderRowCodec.java")), StandardCharsets.UTF_8);
    Assert.assertTrue(
        orderRowCodecSrc.contains("CustomTypeEncoderRegistry.findCodec"),
        "Expected OrderRowCodec to look up the custom codec from the registry, got: "
            + orderRowCodecSrc);
    Assert.assertFalse(
        orderRowCodecSrc.contains("MoneyRowCodec"),
        "Expected no MoneyRowCodec fallback, got: " + orderRowCodecSrc);

    try (URLClassLoader runtime =
        new URLClassLoader(
            new java.net.URL[] {stageACls.toUri().toURL(), stageBCls.toUri().toURL()},
            ForyGenerateProcessorTest.class.getClassLoader())) {
      Object codecs = invokeStaticInstance(runtime, "b.Codecs_Fory");
      Class<?> orderClass = runtime.loadClass("b.Order");
      Class<?> moneyClass = runtime.loadClass("a.Money");
      Object money = moneyClass.getConstructor(long.class).newInstance(1234L);
      Object order = orderClass.getConstructor(long.class, moneyClass).newInstance(9L, money);
      byte[] bytes = (byte[]) invoke(codecs, "encode", new Class<?>[] {orderClass}, order);
      Object roundTrip = invoke(codecs, "decode", new Class<?>[] {byte[].class}, bytes);
      Assert.assertEquals(getField(orderClass, roundTrip, "id"), 9L);
      Object roundTripMoney = getField(orderClass, roundTrip, "price");
      Assert.assertEquals(getField(moneyClass, roundTripMoney, "cents"), 1234L);
    }
  }

  /**
   * Compile {@code sources} with the @ForyGenerate processor enabled, classes landing in {@code
   * classOut}. Used by tests that simulate an upstream module producing META-INF descriptors for
   * downstream discovery.
   */
  private static void compileWithProcessor(Path[] sources, Path classOut, String classpath)
      throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diag = new DiagnosticCollector<>();
    try (StandardJavaFileManager fm =
        compiler.getStandardFileManager(diag, null, StandardCharsets.UTF_8)) {
      List<java.io.File> files = new ArrayList<>();
      for (Path p : sources) {
        files.add(p.toFile());
      }
      Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromFiles(files);
      List<String> options = Arrays.asList("-classpath", classpath, "-d", classOut.toString());
      JavaCompiler.CompilationTask task = compiler.getTask(null, fm, diag, options, null, units);
      task.setProcessors(Collections.singletonList(new ForyGenerateProcessor()));
      boolean ok = task.call();
      if (!ok) {
        StringBuilder sb = new StringBuilder("compile failed: ");
        for (Diagnostic<? extends JavaFileObject> d : diag.getDiagnostics()) {
          sb.append('\n').append(d);
        }
        throw new AssertionError(sb.toString());
      }
    }
  }

  /** Same as {@link #compileWithProcessor} but with annotation processing disabled. */
  private static void compileNoProcessor(Path[] sources, Path classOut, String classpath)
      throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diag = new DiagnosticCollector<>();
    try (StandardJavaFileManager fm =
        compiler.getStandardFileManager(diag, null, StandardCharsets.UTF_8)) {
      List<java.io.File> files = new ArrayList<>();
      for (Path p : sources) {
        files.add(p.toFile());
      }
      Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromFiles(files);
      List<String> options =
          Arrays.asList("-classpath", classpath, "-d", classOut.toString(), "-proc:none");
      JavaCompiler.CompilationTask task = compiler.getTask(null, fm, diag, options, null, units);
      boolean ok = task.call();
      if (!ok) {
        StringBuilder sb = new StringBuilder("compile failed: ");
        for (Diagnostic<? extends JavaFileObject> d : diag.getDiagnostics()) {
          sb.append('\n').append(d);
        }
        throw new AssertionError(sb.toString());
      }
    }
  }

  @Test
  public void customCollectionCrossModule() throws Exception {
    // Same cross-module shape as customCodecCrossModule: a factory class lives in stage 1
    // (with the processor enabled, so META-INF descriptor is written), the @ForyGenerate
    // interface in stage 2 picks it up via classpath descriptor.
    Path stageA = Files.createTempDirectory("fory-coll-stageA");
    Path stageACls = stageA.resolve("classes");
    Path stageASrc = stageA.resolve("src");
    Files.createDirectories(stageACls);
    Files.createDirectories(stageASrc.resolve("a"));
    Files.write(
        stageASrc.resolve("a/SealedList.java"),
        ("package a;\n"
                + "import java.util.ArrayList;\n"
                + "public class SealedList<E> extends ArrayList<E> {\n"
                + "  public SealedList() {}\n"
                + "  public SealedList(int initialCapacity) { super(initialCapacity); }\n"
                + "}\n")
            .getBytes(StandardCharsets.UTF_8));
    Files.write(
        stageASrc.resolve("a/SealedListFactory.java"),
        ("package a;\n"
                + "import org.apache.fory.format.annotation.ForyCustomCollection;\n"
                + "import org.apache.fory.format.encoder.CustomCollectionFactory;\n"
                + "@ForyCustomCollection\n"
                + "public final class SealedListFactory\n"
                + "    implements CustomCollectionFactory<String, SealedList<String>> {\n"
                + "  @Override public SealedList<String> newCollection(int size) { return new SealedList<>(size); }\n"
                + "}\n")
            .getBytes(StandardCharsets.UTF_8));
    compileWithProcessor(
        new Path[] {
          stageASrc.resolve("a/SealedList.java"), stageASrc.resolve("a/SealedListFactory.java")
        },
        stageACls,
        System.getProperty("java.class.path"));
    Assert.assertTrue(
        Files.exists(stageACls.resolve("META-INF/fory/custom-collections.txt")),
        "Stage 1 must write the collection descriptor");

    Path stageB = Files.createTempDirectory("fory-coll-stageB");
    Path stageBCls = stageB.resolve("classes");
    Path stageBSrc = stageB.resolve("src");
    Path stageBGen = stageB.resolve("gen");
    Files.createDirectories(stageBCls);
    Files.createDirectories(stageBSrc.resolve("b"));
    Files.createDirectories(stageBGen);
    Files.write(
        stageBSrc.resolve("b/Holder.java"),
        ("package b;\n"
                + "import a.SealedList;\n"
                + "public class Holder {\n"
                + "  private SealedList<String> items;\n"
                + "  public Holder() {}\n"
                + "  public SealedList<String> getItems() { return items; }\n"
                + "  public void setItems(SealedList<String> items) { this.items = items; }\n"
                + "}\n")
            .getBytes(StandardCharsets.UTF_8));
    Files.write(
        stageBSrc.resolve("b/Codecs.java"),
        ("package b;\n"
                + "import org.apache.fory.format.annotation.ForyGenerate;\n"
                + "@ForyGenerate\n"
                + "public interface Codecs {\n"
                + "  byte[] encode(Holder h);\n"
                + "  Holder decode(byte[] bytes);\n"
                + "}\n")
            .getBytes(StandardCharsets.UTF_8));
    String stageBClasspath =
        stageACls + java.io.File.pathSeparator + System.getProperty("java.class.path");
    compileNoProcessor(new Path[] {stageBSrc.resolve("b/Holder.java")}, stageBCls, stageBClasspath);
    String stageBClasspathWithBeans = stageBCls + java.io.File.pathSeparator + stageBClasspath;

    URLClassLoader stageARuntime =
        new URLClassLoader(
            new java.net.URL[] {stageACls.toUri().toURL(), stageBCls.toUri().toURL()},
            ForyGenerateProcessorTest.class.getClassLoader());
    ClassLoader prior = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(stageARuntime);
    boolean success;
    DiagnosticCollector<JavaFileObject> stageBDiag = new DiagnosticCollector<>();
    try {
      JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
      try (StandardJavaFileManager fm =
          compiler.getStandardFileManager(stageBDiag, null, StandardCharsets.UTF_8)) {
        Iterable<? extends JavaFileObject> units =
            fm.getJavaFileObjectsFromFiles(
                Collections.singletonList(stageBSrc.resolve("b/Codecs.java").toFile()));
        List<String> options =
            Arrays.asList(
                "-classpath", stageBClasspathWithBeans,
                "-d", stageBCls.toString(),
                "-s", stageBGen.toString());
        JavaCompiler.CompilationTask task =
            compiler.getTask(null, fm, stageBDiag, options, null, units);
        task.setProcessors(Collections.singletonList(new ForyGenerateProcessor(stageARuntime)));
        success = task.call();
      }
    } finally {
      Thread.currentThread().setContextClassLoader(prior);
    }
    StringBuilder diagText = new StringBuilder();
    for (Diagnostic<? extends JavaFileObject> d : stageBDiag.getDiagnostics()) {
      diagText.append(d).append('\n');
    }
    Assert.assertTrue(success, diagText.toString());

    try (URLClassLoader runtime =
        new URLClassLoader(
            new java.net.URL[] {stageACls.toUri().toURL(), stageBCls.toUri().toURL()},
            ForyGenerateProcessorTest.class.getClassLoader())) {
      Object codecs = invokeStaticInstance(runtime, "b.Codecs_Fory");
      Class<?> holderClass = runtime.loadClass("b.Holder");
      Class<?> sealedList = runtime.loadClass("a.SealedList");
      Object items = sealedList.getConstructor().newInstance();
      @SuppressWarnings({"rawtypes", "unchecked"})
      java.util.List raw = (java.util.List) items;
      raw.add("a");
      raw.add("b");
      Object holder = holderClass.getConstructor().newInstance();
      holderClass.getMethod("setItems", sealedList).invoke(holder, items);
      byte[] bytes = (byte[]) invoke(codecs, "encode", new Class<?>[] {holderClass}, holder);
      Object roundTrip = invoke(codecs, "decode", new Class<?>[] {byte[].class}, bytes);
      Object decodedItems = getField(holderClass, roundTrip, "items");
      Assert.assertNotNull(decodedItems);
      Assert.assertTrue(
          sealedList.isInstance(decodedItems),
          "Expected SealedList, got " + decodedItems.getClass().getName());
      Assert.assertEquals(((java.util.List<?>) decodedItems).size(), 2);
      Assert.assertEquals(((java.util.List<?>) decodedItems).get(0), "a");
    }
  }

  @Test
  public void nestedInterfaceFactoryNamesIncludeEnclosingType() throws Exception {
    // Two interfaces with the same simple name `Codecs` nested in different outers must produce
    // distinct generated factory source files; using only the simple name would collide on
    // test.Codecs_Fory and the second file would overwrite the first. Use distinct beans per
    // interface so each factory's codec sources are independent.
    Map<String, String> beans = beanSource();
    beans.put(
        "test.OtherBean",
        "package test;\n"
            + "public class OtherBean {\n"
            + "  private long value;\n"
            + "  public OtherBean() {}\n"
            + "  public OtherBean(long v) { this.value = v; }\n"
            + "  public long getValue() { return value; }\n"
            + "  public void setValue(long v) { this.value = v; }\n"
            + "}\n");
    TwoStageResult result =
        compileWithBeans(
            beans,
            "test.Holder",
            "package test;\n"
                + "import org.apache.fory.format.annotation.ForyGenerate;\n"
                + "public final class Holder {\n"
                + "  @ForyGenerate\n"
                + "  public interface Codecs {\n"
                + "    byte[] encode(MyBean bean);\n"
                + "  }\n"
                + "  private Holder() {}\n"
                + "}\n"
                + "final class OtherHolder {\n"
                + "  @ForyGenerate\n"
                + "  interface Codecs {\n"
                + "    OtherBean decode(byte[] bytes);\n"
                + "  }\n"
                + "  private OtherHolder() {}\n"
                + "}\n");
    Assert.assertTrue(result.success, result.diagnostics());
    Assert.assertTrue(
        Files.exists(result.generatedSrc.resolve("test/Holder_Codecs_Fory.java")),
        "Outer Holder.Codecs factory must land at test.Holder_Codecs_Fory");
    Assert.assertTrue(
        Files.exists(result.generatedSrc.resolve("test/OtherHolder_Codecs_Fory.java")),
        "Outer OtherHolder.Codecs factory must land at test.OtherHolder_Codecs_Fory");
  }

  @Test
  public void rejectsUnsupportedMethodShape() throws Exception {
    TwoStageResult result =
        compileWithBeans(
            beanSource(),
            "test.MyCodecs",
            "package test;\n"
                + "import org.apache.fory.format.annotation.ForyGenerate;\n"
                + "@ForyGenerate\n"
                + "public interface MyCodecs {\n"
                + "  String unsupported(MyBean bean, MyBean other, MyBean third);\n"
                + "}\n");
    Assert.assertFalse(result.success);
    boolean foundError = false;
    for (Diagnostic<? extends JavaFileObject> d : result.diagnostics) {
      if (d.getKind() == Diagnostic.Kind.ERROR && d.getMessage(null).contains("@ForyGenerate")) {
        foundError = true;
        break;
      }
    }
    Assert.assertTrue(
        foundError, "Expected a @ForyGenerate diagnostic, got: " + result.diagnostics());
  }

  // ---------------------------------------------------------------------------
  // Test fixture: a small bean used across all tests.

  private static Map<String, String> beanSource() {
    Map<String, String> beans = new HashMap<>();
    beans.put(
        "test.MyBean",
        "package test;\n"
            + "public class MyBean {\n"
            + "  private int id;\n"
            + "  private String name;\n"
            + "  public MyBean() {}\n"
            + "  public MyBean(int id, String name) { this.id = id; this.name = name; }\n"
            + "  public int getId() { return id; }\n"
            + "  public void setId(int id) { this.id = id; }\n"
            + "  public String getName() { return name; }\n"
            + "  public void setName(String name) { this.name = name; }\n"
            + "}\n");
    return beans;
  }

  // ---------------------------------------------------------------------------
  // Two-stage compile machinery.

  private static TwoStageResult compileWithBeans(
      Map<String, String> beans, String factoryTypeName, String factorySource) throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    Assert.assertNotNull(compiler, "Tests require a JDK compiler");

    Path root = Files.createTempDirectory("fory-generate-test");
    Path beanSrc = root.resolve("bean-src");
    Path beanClasses = root.resolve("bean-classes");
    Path factorySrc = root.resolve("factory-src");
    Path factoryClasses = root.resolve("factory-classes");
    Path generatedSrc = root.resolve("generated-src");
    Files.createDirectories(beanSrc);
    Files.createDirectories(beanClasses);
    Files.createDirectories(factorySrc);
    Files.createDirectories(factoryClasses);
    Files.createDirectories(generatedSrc);

    // Stage 1: compile beans.
    List<Path> beanFiles = new ArrayList<>();
    for (Map.Entry<String, String> entry : beans.entrySet()) {
      Path file = beanSrc.resolve(entry.getKey().replace('.', '/') + ".java");
      Files.createDirectories(file.getParent());
      Files.write(file, entry.getValue().getBytes(StandardCharsets.UTF_8));
      beanFiles.add(file);
    }
    DiagnosticCollector<JavaFileObject> beanDiagnostics = new DiagnosticCollector<>();
    try (StandardJavaFileManager fileManager =
        compiler.getStandardFileManager(beanDiagnostics, null, StandardCharsets.UTF_8)) {
      Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromPaths(beanFiles);
      List<String> options =
          Arrays.asList(
              "-classpath",
              System.getProperty("java.class.path"),
              "-d",
              beanClasses.toString(),
              "-proc:none");
      JavaCompiler.CompilationTask task =
          compiler.getTask(null, fileManager, beanDiagnostics, options, null, units);
      if (!task.call()) {
        return new TwoStageResult(
            beanClasses, factoryClasses, generatedSrc, false, beanDiagnostics.getDiagnostics());
      }
    }

    // Stage 2: compile factory interface, with the bean .class files on the processor classpath.
    Path factoryFile = factorySrc.resolve(factoryTypeName.replace('.', '/') + ".java");
    Files.createDirectories(factoryFile.getParent());
    Files.write(factoryFile, factorySource.getBytes(StandardCharsets.UTF_8));
    DiagnosticCollector<JavaFileObject> factoryDiagnostics = new DiagnosticCollector<>();
    String classpath =
        beanClasses + java.io.File.pathSeparator + System.getProperty("java.class.path");
    try (StandardJavaFileManager fileManager =
        compiler.getStandardFileManager(factoryDiagnostics, null, StandardCharsets.UTF_8)) {
      Iterable<? extends JavaFileObject> units =
          fileManager.getJavaFileObjectsFromFiles(Collections.singletonList(factoryFile.toFile()));
      List<String> options =
          Arrays.asList(
              "-classpath", classpath,
              "-d", factoryClasses.toString(),
              "-s", generatedSrc.toString());
      // Run the processor with the bean classloader on the thread context so loadClass succeeds.
      URLClassLoader beanLoader =
          new URLClassLoader(
              new URL[] {beanClasses.toUri().toURL()},
              ForyGenerateProcessorTest.class.getClassLoader());
      ClassLoader prior = Thread.currentThread().getContextClassLoader();
      Thread.currentThread().setContextClassLoader(beanLoader);
      boolean success;
      try {
        JavaCompiler.CompilationTask task =
            compiler.getTask(null, fileManager, factoryDiagnostics, options, null, units);
        // ForyGenerateProcessor calls Class.forName with its own classloader, which only sees the
        // processor's classpath. Inject one explicitly so the bean classes are findable.
        ForyGenerateProcessor proc = new ForyGenerateProcessor(beanLoader);
        task.setProcessors(Collections.singletonList(proc));
        success = task.call();
      } finally {
        Thread.currentThread().setContextClassLoader(prior);
      }
      return new TwoStageResult(
          beanClasses, factoryClasses, generatedSrc, success, factoryDiagnostics.getDiagnostics());
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static byte[] encodeWithRuntimeBuilder(Class<?> beanClass, Object value) {
    org.apache.fory.format.encoder.RowEncoder encoder =
        org.apache.fory.format.encoder.Encoders.buildBeanCodec(beanClass)
            .withSchemaEvolution()
            .build()
            .get();
    return encoder.encode(value);
  }

  private static Object invokeStaticInstance(URLClassLoader loader, String className)
      throws Exception {
    Class<?> cls = loader.loadClass(className);
    Method m = cls.getMethod("instance");
    // The factory's static initializer calls Encoders.* which probes for the precompiled codec
    // via Thread.currentThread().getContextClassLoader(); make sure it sees our loader.
    ClassLoader prior = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(loader);
    try {
      return m.invoke(null);
    } finally {
      Thread.currentThread().setContextClassLoader(prior);
    }
  }

  private static Object invoke(Object target, String name, Class<?>[] sig, Object... args)
      throws Exception {
    Method m = target.getClass().getMethod(name, sig);
    return m.invoke(target, args);
  }

  private static Object getField(Class<?> type, Object instance, String name) throws Exception {
    java.lang.reflect.Field f = type.getDeclaredField(name);
    f.setAccessible(true);
    return f.get(instance);
  }

  private static final class TwoStageResult {
    final Path beanClasses;
    final Path factoryClasses;
    final Path generatedSrc;
    final boolean success;
    final List<Diagnostic<? extends JavaFileObject>> diagnostics;

    TwoStageResult(
        Path beanClasses,
        Path factoryClasses,
        Path generatedSrc,
        boolean success,
        List<Diagnostic<? extends JavaFileObject>> diagnostics) {
      this.beanClasses = beanClasses;
      this.factoryClasses = factoryClasses;
      this.generatedSrc = generatedSrc;
      this.success = success;
      this.diagnostics = new ArrayList<>(diagnostics);
    }

    String generatedSource(String qualifiedName) throws IOException {
      Path file = generatedSrc.resolve(qualifiedName.replace('.', '/') + ".java");
      return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    URLClassLoader classLoader() throws IOException {
      List<URL> urls = new ArrayList<>();
      if (beanClasses != null) {
        urls.add(beanClasses.toUri().toURL());
      }
      urls.add(factoryClasses.toUri().toURL());
      return new URLClassLoader(
          urls.toArray(new URL[0]), ForyGenerateProcessorTest.class.getClassLoader());
    }

    String diagnostics() {
      StringBuilder sb = new StringBuilder();
      for (Diagnostic<? extends JavaFileObject> d : diagnostics) {
        sb.append(d).append('\n');
      }
      return sb.toString();
    }
  }
}
