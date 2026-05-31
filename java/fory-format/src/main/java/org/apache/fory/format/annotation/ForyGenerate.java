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

package org.apache.fory.format.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface as a row-codec factory whose methods describe encoders the Fory annotation
 * processor should generate at build time. The processor emits a {@code <Interface>_Fory}
 * implementation alongside the row codec classes, so applications can use Fory row codecs without
 * paying the runtime Janino warmup cost and without needing runtime bytecode generation (Android,
 * GraalVM native image, ahead-of-time builds).
 *
 * <p>Method shapes the processor recognises (T is a user bean class, K/V are arbitrary):
 *
 * <ul>
 *   <li>{@code RowEncoder<T> name()} returns the underlying row encoder for T
 *   <li>{@code ArrayEncoder<List<T>> name()} returns the array encoder
 *   <li>{@code MapEncoder<Map<K,V>> name()} returns the map encoder
 *   <li>{@code byte[] name(T)} encodes a single bean to a fresh byte array
 *   <li>{@code T name(byte[])} decodes a single bean from a byte array
 *   <li>{@code void name(MemoryBuffer, T)} appends a single bean to the buffer
 *   <li>{@code T name(MemoryBuffer)} reads the next bean from the buffer
 *   <li>{@code void name(MemoryBuffer, List<T>)} appends a list of beans
 *   <li>{@code List<T> name(MemoryBuffer)} reads a list of beans
 *   <li>{@code void name(MemoryBuffer, Map<K,V>)} appends a map
 *   <li>{@code Map<K,V> name(MemoryBuffer)} reads a map
 * </ul>
 *
 * <p>Method names are not parsed; the dispatch is based purely on parameter and return types.
 * Methods that do not match any supported shape produce a compile error.
 *
 * <p>The bean classes referenced by the interface must be on the annotation-processor classpath
 * when the processor runs (i.e. produced by an earlier compilation step or supplied by a dependency
 * jar). They cannot live in the same compilation unit as the {@code @ForyGenerate} interface.
 *
 * <p>Declare an {@code INSTANCE} constant on the interface that resolves through {@code
 * Encoders.factory(...)} so callers do not have to know the generated class name:
 *
 * <pre>{@code
 * @ForyGenerate(format = RowFormat.COMPACT)
 * public interface MyCodecs {
 *   MyCodecs INSTANCE = Encoders.factory(MyCodecs.class);
 *
 *   RowEncoder<MyBean> myBeanEncoder();
 *   byte[] encode(MyBean bean);
 *   MyBean decode(byte[] bytes);
 *   void append(MemoryBuffer buf, MyBean bean);
 *   MyBean readNext(MemoryBuffer buf);
 *   List<MyBean> readList(MemoryBuffer buf);
 *   void appendList(MemoryBuffer buf, List<MyBean> beans);
 *   Map<Integer, MyBean> readMap(MemoryBuffer buf);
 *   void appendMap(MemoryBuffer buf, Map<Integer, MyBean> beans);
 * }
 *
 * byte[] bytes = MyCodecs.INSTANCE.encode(new MyBean(...));
 * }</pre>
 *
 * <p>The generated class is {@code <Interface>_Fory} (e.g. {@code com.example.MyCodecs_Fory});
 * {@code Encoders.factory(MyCodecs.class)} derives that name and returns its singleton.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ForyGenerate {

  RowFormat format() default RowFormat.DEFAULT;

  /**
   * Enable schema evolution. The generated factory builds codecs with {@code
   * .withSchemaEvolution()} and the processor precompiles a projection codec for every combination
   * of outer and nested bean versions. The number of generated classes is the product of per-bean
   * version counts, so shrink the cross product by retiring {@code @ForyVersion} entries you no
   * longer need to read.
   */
  boolean evolution() default false;
}
