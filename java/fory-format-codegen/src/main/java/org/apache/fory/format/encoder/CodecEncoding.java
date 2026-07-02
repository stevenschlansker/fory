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

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.apache.fory.format.type.Schema;
import org.apache.fory.reflect.TypeRef;

/**
 * The code-generation half of a row-format encoding: constructs the codec builders that emit
 * generated codec source. Extends the runtime {@link Encoding} so a single format instance (see
 * {@link DefaultCodecFormat}, {@link CompactCodecFormat}) provides both the runtime writers and the
 * build-time builders. Runtime-only decoders depend on {@code Encoding} alone and never reference
 * this interface, keeping the code-generation machinery off the runtime path.
 */
interface CodecEncoding extends Encoding {
  RowEncoderBuilder newRowEncoder(TypeRef<?> beanType);

  /**
   * Construct a projection codec builder for an older version of {@code beanType}, reading the
   * supplied historical schema and producing instances of the current bean class. The {@code
   * nestedSuffixes} map directs codegen to embed a specific projection codec class for each
   * nested-bean type (used when a nested versioned bean was on the wire at an older version). An
   * empty map means all nested beans use their current-version codecs.
   */
  RowEncoderBuilder newProjectionRowEncoder(
      TypeRef<?> beanType,
      Schema historicalSchema,
      Set<String> liveNames,
      String classSuffix,
      Map<Class<?>, String> nestedSuffixes);

  ArrayEncoderBuilder newArrayEncoder(
      TypeRef<? extends Collection<?>> collectionType, TypeRef<?> elementType);

  /**
   * Construct an array encoder builder for one historical combination of the element's nested
   * versioned beans. {@code classSuffix} names the generated array codec class (encoding the whole
   * combination); {@code nestedSuffixes} routes each nested bean class to its own projection row
   * codec, so an element wrapping more than one distinct versioned bean (such as {@code Map<KBean,
   * VBean>}) embeds the right historical codec for each.
   */
  ArrayEncoderBuilder newProjectionArrayEncoder(
      TypeRef<? extends Collection<?>> collectionType,
      TypeRef<?> elementType,
      String classSuffix,
      Map<Class<?>, String> nestedSuffixes);

  MapEncoderBuilder newMapEncoder(TypeRef<? extends Map<?, ?>> mapType, TypeRef<?> beanToken);

  /**
   * Construct a map encoder builder whose generated code references the value and key bean row
   * codec classes for one historical (key-version, value-version) combination. {@code
   * valCodecSuffix} and {@code keyCodecSuffix} name the generated map codec class per position;
   * {@code valNestedSuffixes} and {@code keyNestedSuffixes} route each nested bean class within a
   * position to its own projection row codec, so a position wrapping more than one distinct
   * versioned bean (such as a value typed {@code Map<KBean, VBean>}) embeds the right historical
   * codec for each. An empty suffix or absent class means that position, or that bean, is read at
   * its current schema.
   */
  MapEncoderBuilder newProjectionMapEncoder(
      TypeRef<? extends Map<?, ?>> mapType,
      TypeRef<?> beanToken,
      String valCodecSuffix,
      String keyCodecSuffix,
      Map<Class<?>, String> valNestedSuffixes,
      Map<Class<?>, String> keyNestedSuffixes);
}
