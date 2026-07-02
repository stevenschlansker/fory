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

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
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

  @Test
  public void evolutionRowRoundTrips() {
    Account account = new Account(7L, "alice", "alice@example.com");
    byte[] bytes = AccountCodecs.INSTANCE.encode(account);
    Assert.assertEquals(AccountCodecs.INSTANCE.decode(bytes), account);
  }

  @Test
  public void evolutionRowProjectsOlderVersion() {
    // A payload written in the version-1 layout (no email) decodes through the projection codec the
    // evolution factory resolves for that schema hash; the version-2 email field is left null.
    byte[] v1Bytes = AccountV1Codecs.INSTANCE.encode(new AccountV1(7L, "alice"));
    Account decoded = AccountCodecs.INSTANCE.decode(v1Bytes);
    Assert.assertEquals(decoded, new Account(7L, "alice", null));
  }

  @Test
  public void evolutionArrayRoundTrips() {
    List<Account> accounts =
        Arrays.asList(new Account(1L, "a", "a@x"), new Account(2L, "b", "b@x"));
    MemoryBuffer buffer = MemoryUtils.buffer(64);
    AccountCodecs.INSTANCE.appendList(buffer, accounts);
    Assert.assertEquals(AccountCodecs.INSTANCE.readList(buffer), accounts);
  }

  @Test
  public void evolutionArrayProjectsOlderVersion() {
    // A list written in the version-1 element layout decodes through the array projection codec;
    // each element's version-2 email is left null.
    MemoryBuffer buffer = MemoryUtils.buffer(64);
    AccountV1Codecs.INSTANCE.appendList(
        buffer, Arrays.asList(new AccountV1(1L, "a"), new AccountV1(2L, "b")));
    List<Account> decoded = AccountCodecs.INSTANCE.readList(buffer);
    Assert.assertEquals(
        decoded, Arrays.asList(new Account(1L, "a", null), new Account(2L, "b", null)));
  }

  @Test
  public void evolutionMapRoundTrips() {
    Map<String, Account> accounts = new LinkedHashMap<>();
    accounts.put("a", new Account(1L, "a", "a@x"));
    accounts.put("b", new Account(2L, "b", "b@x"));
    MemoryBuffer buffer = MemoryUtils.buffer(64);
    AccountCodecs.INSTANCE.appendMap(buffer, accounts);
    Assert.assertEquals(AccountCodecs.INSTANCE.readMap(buffer), accounts);
  }

  @Test
  public void evolutionMapProjectsOlderVersion() {
    // A map whose values are written in the version-1 layout decodes through the map projection
    // codec for that value schema; each value's version-2 email is left null.
    Map<String, AccountV1> v1 = new LinkedHashMap<>();
    v1.put("a", new AccountV1(1L, "a"));
    v1.put("b", new AccountV1(2L, "b"));
    MemoryBuffer buffer = MemoryUtils.buffer(64);
    AccountV1Codecs.INSTANCE.appendMap(buffer, v1);
    Map<String, Account> decoded = AccountCodecs.INSTANCE.readMap(buffer);
    Map<String, Account> expected = new LinkedHashMap<>();
    expected.put("a", new Account(1L, "a", null));
    expected.put("b", new Account(2L, "b", null));
    Assert.assertEquals(decoded, expected);
  }

  /**
   * The evolution encoders resolve projection codecs lazily on the first decode of an older
   * version's hash. That decode may run on a thread whose context classloader cannot see the
   * precompiled projection class. This reproduces a layered deployment: fory-format-runtime (and
   * {@code GeneratedRowCodecs}) on a parent loader, and the {@code @ForyGenerate}-generated codec
   * classes on a child below it. The encoder is built with the child as the context classloader, so
   * it must capture the child and pin lazy projection resolution to it; otherwise a decode on a
   * thread whose context classloader is the platform loader cannot find {@code AccountRowCodec_V1}
   * (the parent that holds {@code GeneratedRowCodecs} does not have the generated classes), and the
   * runtime throws "No precompiled codec".
   *
   * <p>Splitting the generated classes onto a child whose parent lacks them is what makes this
   * discriminate: without the classloader capture the projection resolution probes only the decode
   * thread's context classloader and {@code GeneratedRowCodecs}'s own (parent) loader, both of
   * which miss.
   */
  @Test
  public void projectionResolvesUnderForeignThreadContextClassLoader() throws Exception {
    // Parent holds everything on the test classpath EXCEPT the module's own generated classes dir;
    // the child holds only that dir, so the generated codecs live strictly below the runtime.
    URL generatedClassesDir = null;
    java.util.List<URL> parentUrls = new java.util.ArrayList<>();
    for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
      URL url = new java.io.File(entry).toURI().toURL();
      if (entry.replace('\\', '/').endsWith("/target/test-classes")) {
        generatedClassesDir = url;
      } else {
        parentUrls.add(url);
      }
    }
    Assert.assertNotNull(generatedClassesDir, "expected target/test-classes on the classpath");

    try (URLClassLoader parent =
            new URLClassLoader(
                parentUrls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
        URLClassLoader child = new URLClassLoader(new URL[] {generatedClassesDir}, parent)) {
      // Sanity: the parent (which holds GeneratedRowCodecs) must NOT see the generated projection
      // class, so a missing capture cannot be masked by the GeneratedRowCodecs-loader fallback.
      Assert.assertThrows(
          ClassNotFoundException.class,
          () -> parent.loadClass("org.apache.fory.format.runtime.AccountRowCodec_V1"));

      // Write v1-layout payloads for all three shapes (row, array, map) through the child loader,
      // and capture the child-loaded factory whose evolving encoders must pin resolution to it.
      Object factory;
      Class<?> codecsInterface;
      byte[] rowV1;
      byte[] listV1;
      byte[] mapV1;
      Class<?> bufferClass = child.loadClass("org.apache.fory.memory.MemoryBuffer");
      ClassLoader prior = Thread.currentThread().getContextClassLoader();
      Thread.currentThread().setContextClassLoader(child);
      try {
        Class<?> v1CodecsClass = child.loadClass("org.apache.fory.format.runtime.AccountV1Codecs");
        Class<?> accountV1Class = child.loadClass("org.apache.fory.format.runtime.AccountV1");
        Object v1Factory = v1CodecsClass.getField("INSTANCE").get(null);
        Object v1 =
            accountV1Class.getConstructor(long.class, String.class).newInstance(9L, "carol");

        rowV1 = (byte[]) v1CodecsClass.getMethod("encode", accountV1Class).invoke(v1Factory, v1);
        listV1 = writeToBuffer(child, v1CodecsClass, v1Factory, "appendList", singletonList(v1));
        mapV1 = writeToBuffer(child, v1CodecsClass, v1Factory, "appendMap", singletonMap("k", v1));

        codecsInterface = child.loadClass("org.apache.fory.format.runtime.AccountCodecs");
        factory = codecsInterface.getField("INSTANCE").get(null);
      } finally {
        Thread.currentThread().setContextClassLoader(prior);
      }

      // Decode every shape on a fresh thread whose context classloader is the platform loader,
      // which
      // cannot see any application class. Each projection must resolve through the captured child
      // loader; without the classloader capture, resolution misses and throws "No precompiled
      // codec".
      AtomicReference<Throwable> failure = new AtomicReference<>();
      Object f = factory;
      Class<?> iface = codecsInterface;
      Thread decodeThread =
          new Thread(
              () -> {
                try {
                  Object row = iface.getMethod("decode", byte[].class).invoke(f, rowV1);
                  Assert.assertEquals(
                      row.getClass().getName(), "org.apache.fory.format.runtime.Account");
                  Object list =
                      iface.getMethod("readList", bufferClass).invoke(f, wrap(bufferClass, listV1));
                  Assert.assertEquals(((java.util.List<?>) list).size(), 1);
                  Object map =
                      iface.getMethod("readMap", bufferClass).invoke(f, wrap(bufferClass, mapV1));
                  Assert.assertEquals(((java.util.Map<?, ?>) map).size(), 1);
                } catch (Throwable t) {
                  failure.set(t.getCause() != null ? t.getCause() : t);
                }
              });
      decodeThread.setContextClassLoader(ClassLoader.getPlatformClassLoader());
      decodeThread.start();
      decodeThread.join();

      Assert.assertNull(
          failure.get(),
          "row, array, and map projection decodes on a foreign-context-CL thread must resolve "
              + "through the captured build-time classloader");
    }
  }

  private static java.util.List<Object> singletonList(Object v) {
    java.util.List<Object> l = new java.util.ArrayList<>();
    l.add(v);
    return l;
  }

  private static java.util.Map<String, Object> singletonMap(String k, Object v) {
    java.util.Map<String, Object> m = new LinkedHashMap<>();
    m.put(k, v);
    return m;
  }

  /**
   * Allocate a child-loaded MemoryBuffer, invoke the append method, and return the written bytes.
   */
  private static byte[] writeToBuffer(
      ClassLoader child, Class<?> codecsClass, Object factory, String method, Object arg)
      throws Exception {
    Class<?> bufferClass = child.loadClass("org.apache.fory.memory.MemoryBuffer");
    Class<?> utilClass = child.loadClass("org.apache.fory.memory.MemoryUtils");
    Object buffer = utilClass.getMethod("buffer", int.class).invoke(null, 64);
    // The append method's second parameter is List or Map; resolve it from the codec interface.
    for (java.lang.reflect.Method m : codecsClass.getMethods()) {
      if (m.getName().equals(method)) {
        m.invoke(factory, buffer, arg);
        break;
      }
    }
    int size = (int) bufferClass.getMethod("writerIndex").invoke(buffer);
    return (byte[]) bufferClass.getMethod("getBytes", int.class, int.class).invoke(buffer, 0, size);
  }

  /** Wrap bytes in a child-loaded MemoryBuffer for the foreign-thread decode. */
  private static Object wrap(Class<?> bufferClass, byte[] bytes) throws Exception {
    Class<?> utilClass =
        bufferClass.getClassLoader().loadClass("org.apache.fory.memory.MemoryUtils");
    return utilClass.getMethod("wrap", byte[].class).invoke(null, (Object) bytes);
  }
}
