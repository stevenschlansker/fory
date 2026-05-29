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
 * Marks an implementation of {@link org.apache.fory.format.encoder.CustomCodec} for automatic
 * registration. The {@code @ForyGenerate} annotation processor discovers every class carrying this
 * annotation, registers it on the build-time custom-codec registry so generated row codecs honor
 * it, and emits the corresponding {@code Encoders.registerCustomCodec(...)} call into each
 * generated factory's static initializer.
 *
 * <p>The codec class must have a public no-arg constructor. The field type the codec handles is
 * read from the codec's {@code CustomCodec<T, E>} type parameter; only {@code beanType} is
 * declared on the annotation, defaulting to {@code Object.class} (global scope).
 *
 * <p>Example:
 *
 * <pre>{@code
 * @ForyCustomCodec
 * public final class MoneyCodec implements CustomCodec<Money, Long> { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ForyCustomCodec {
  /**
   * The enclosing bean type to which this codec applies. {@code Object.class} (the default) means
   * the codec applies whenever the codec's field type appears in any bean.
   */
  Class<?> beanType() default Object.class;
}
