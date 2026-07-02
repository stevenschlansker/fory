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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.util.Collection;
import java.util.Map;
import org.apache.fory.Fory;
import org.apache.fory.annotation.Internal;
import org.apache.fory.format.annotation.RowFormat;
import org.apache.fory.format.row.binary.writer.BaseBinaryRowWriter;
import org.apache.fory.format.row.binary.writer.BinaryArrayWriter;
import org.apache.fory.format.row.binary.writer.CompactBinaryRowWriter;
import org.apache.fory.format.type.CustomTypeRegistration;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.format.type.Field;
import org.apache.fory.format.type.Schema;
import org.apache.fory.format.type.TypeInference;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.util.ExceptionUtils;

/**
 * Constructs row, array, and map codecs from classes the {@code @ForyGenerate} annotation processor
 * precompiled at build time, without the row-codec code-generation machinery on the classpath. The
 * generated {@code <Interface>_Fory} factory calls into this helper so an application can run
 * precompiled codecs with only {@code fory-format-runtime} (no builders, no Janino).
 *
 * <p>Only non-evolution codecs are supported here; schema-evolution codecs are handled separately.
 * A precompiled class is resolved by the stable, unique-id-free name the processor emits (the same
 * name the code-generation module's runtime probe uses), so the two agree by construction. When the
 * class is absent this throws rather than falling back to code generation.
 */
@Internal
public final class GeneratedRowCodecs {

  private GeneratedRowCodecs() {}

  /** Build a row encoder from the precompiled codec class for {@code beanClass}. */
  @SuppressWarnings("unchecked")
  public static <T> RowEncoder<T> rowEncoder(
      final Class<T> beanClass, final RowFormat format, final Fory fory) {
    final Encoding encoding = encoding(format);
    Schema schema = TypeInference.inferSchema(beanClass);
    if (format == RowFormat.COMPACT) {
      schema = CompactBinaryRowWriter.sortSchema(schema);
    }
    final Class<?> codecClass = loadCodecClass(beanClass, "", format, GeneratedRowEncoder.class);
    final MethodHandle ctor = constructorHandleFor(codecClass, GeneratedRowEncoder.class);
    final BaseBinaryRowWriter writer = encoding.newWriter(schema);
    final GeneratedRowEncoder codec = construct(ctor, new Object[] {schema, writer, fory});
    return new BinaryRowEncoder<>(schema, codec, writer, true);
  }

  /** Build an array encoder from the precompiled codec class for {@code collectionType}. */
  public static <C extends Collection<?>> ArrayEncoder<C> arrayEncoder(
      final TypeRef<C> collectionType, final RowFormat format, final Fory fory) {
    final Encoding encoding = encoding(format);
    Schema schema = TypeInference.inferSchema(collectionType, false);
    if (format == RowFormat.COMPACT) {
      schema = CompactBinaryRowWriter.sortSchema(schema);
    }
    final Field elementField = DataTypes.fieldOfSchema(schema, 0);
    final TypeRef<?> elementType = TypeUtils.getElementType(collectionType);
    final Class<?> elementClass = TypeUtils.getRawType(elementType);
    final String prefix = TypeInference.inferTypeName(collectionType);
    final Class<?> codecClass =
        loadCodecClass(elementClass, prefix, format, GeneratedArrayEncoder.class);
    final MethodHandle ctor = constructorHandleFor(codecClass, GeneratedArrayEncoder.class);
    final BinaryArrayWriter writer = encoding.newArrayWriter(elementField);
    final GeneratedArrayEncoder codec =
        construct(
            ctor, new Object[] {writer.getField(), writer, fory}, GeneratedArrayEncoder.class);
    final BinaryArrayEncoder<C> encoder = new BinaryArrayEncoder<>(writer, codec, true);
    return new BufferResettingArrayEncoder<>(16, writer, encoder);
  }

  /** Build a map encoder from the precompiled codec class for {@code mapType}. */
  public static <M extends Map<?, ?>> MapEncoder<M> mapEncoder(
      final TypeRef<M> mapType, final RowFormat format, final Fory fory) {
    final Encoding encoding = encoding(format);
    Schema schema = TypeInference.inferSchema(mapType, false);
    if (format == RowFormat.COMPACT) {
      schema = CompactBinaryRowWriter.sortSchema(schema);
    }
    final Field field = DataTypes.fieldOfSchema(schema, 0);
    final Field keyField = DataTypes.keyArrayFieldForMap(field);
    final Field valField = DataTypes.itemArrayFieldForMap(field);
    final org.apache.fory.collection.Tuple2<TypeRef<?>, TypeRef<?>> kvType =
        TypeUtils.getMapKeyValueType(mapType);
    final TypeRef<?> keyType = kvType.f0;
    final TypeRef<?> valType = kvType.f1;
    // Name the codec on the key bean when the key is a bean, else the value bean, else Object —
    // mirroring the code-generation module's Encoders.loadOrGenMapCodecClass.
    final Class<?> nameClass;
    if (TypeUtils.isBean(keyType)) {
      nameClass = TypeUtils.getRawType(keyType);
    } else if (TypeUtils.isBean(valType)) {
      nameClass = TypeUtils.getRawType(valType);
    } else {
      nameClass = Object.class;
    }
    final String prefix = TypeInference.inferTypeName(mapType);
    final Class<?> codecClass =
        loadCodecClass(nameClass, prefix, format, GeneratedMapEncoder.class);
    final MethodHandle ctor = constructorHandleFor(codecClass, GeneratedMapEncoder.class);
    final BinaryArrayWriter keyWriter = encoding.newArrayWriter(keyField);
    final BinaryArrayWriter valWriter = encoding.newArrayWriter(valField, keyWriter.getBuffer());
    final GeneratedMapEncoder codec =
        construct(
            ctor,
            new Object[] {keyField, valField, keyWriter, valWriter, fory, field},
            GeneratedMapEncoder.class);
    final BinaryMapEncoder<M> encoder =
        new BinaryMapEncoder<>(encoding, field, valWriter, keyWriter, codec, true);
    return new BufferResettingMapEncoder<>(16, keyWriter, valWriter, encoder);
  }

  /**
   * Register a custom codec, scoped to {@code beanType}. Called from a generated factory's static
   * initializer to echo the {@code @ForyCustomCodec} registrations back at runtime, so the codec is
   * available before any encoder that references it is constructed.
   */
  public static <T> void registerCustomCodec(
      final Class<?> beanType, final Class<T> type, final CustomCodec<T, ?> codec) {
    TypeInference.registerCustomCodec(new CustomTypeRegistration(beanType, type), codec);
  }

  /**
   * Register a custom collection factory. Called from a generated factory's static initializer to
   * echo the {@code @ForyCustomCollection} registrations back at runtime.
   */
  public static <E, C extends Collection<E>> void registerCustomCollectionFactory(
      final Class<?> collectionType,
      final Class<E> elementType,
      final CustomCollectionFactory<E, C> factory) {
    TypeInference.registerCustomCollectionFactory(collectionType, elementType, factory);
  }

  private static Encoding encoding(final RowFormat format) {
    return format == RowFormat.COMPACT ? RowEncoding.COMPACT : RowEncoding.DEFAULT;
  }

  /**
   * Resolve the precompiled codec class for {@code beanClass} under the stable, unique-id-free name
   * the processor emits. The name derivation mirrors the code-generation module's {@code
   * Encoders.stableQualifiedCodecName} so a runtime probe and the emitted class name agree by
   * construction.
   */
  private static Class<?> loadCodecClass(
      final Class<?> beanClass,
      final String prefix,
      final RowFormat format,
      final Class<?> expectedGenerated) {
    final String suffix = format == RowFormat.COMPACT ? "CompactCodec" : "RowCodec";
    final String simple =
        (ReflectionUtils.getClassNameWithoutPackage(beanClass) + prefix + suffix).replace("$", "_");
    final String pkg = codecPackage(beanClass);
    final String qualifiedName = pkg.isEmpty() ? simple : pkg + "." + simple;
    final Class<?> loaded = tryForName(qualifiedName, expectedGenerated);
    if (loaded == null) {
      throw new IllegalStateException(
          "No precompiled codec "
              + qualifiedName
              + " for "
              + beanClass.getName()
              + ". Generate it with @ForyGenerate, or use the fory-format-codegen module to build "
              + "codecs at runtime.");
    }
    return loaded;
  }

  /**
   * Package of the generated codec, mirroring {@code CodeGenerator.getPackage}: the bean's package,
   * or the fallback package for {@code java.*} classes (whose packages cannot host generated code).
   */
  private static String codecPackage(final Class<?> beanClass) {
    final String pkg = ReflectionUtils.getPackage(beanClass);
    return pkg.startsWith("java.") ? FALLBACK_PACKAGE : pkg;
  }

  private static final String FALLBACK_PACKAGE =
      org.apache.fory.builder.Generated.class.getPackage().getName();

  private static Class<?> tryForName(final String qualifiedName, final Class<?> expectedGenerated) {
    final ClassLoader[] loaders = {
      Thread.currentThread().getContextClassLoader(), GeneratedRowCodecs.class.getClassLoader()
    };
    for (final ClassLoader cl : loaders) {
      if (cl == null) {
        continue;
      }
      try {
        final Class<?> cls = Class.forName(qualifiedName, false, cl);
        return expectedGenerated.isAssignableFrom(cls) ? cls : null;
      } catch (ClassNotFoundException ignored) {
        // try the next loader
      }
    }
    return null;
  }

  private static MethodHandle constructorHandleFor(
      final Class<?> generatedClass, final Class<?> generatedType) {
    try {
      final Constructor<?> constructor =
          generatedClass.asSubclass(generatedType).getConstructor(Object[].class);
      return MethodHandles.lookup()
          .unreflectConstructor(constructor)
          .asType(MethodType.methodType(generatedType, Object[].class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new EncoderException("Failed to resolve constructor for " + generatedClass, e);
    }
  }

  private static GeneratedRowEncoder construct(final MethodHandle ctor, final Object[] references) {
    try {
      return (GeneratedRowEncoder) ctor.invokeExact(references);
    } catch (Throwable e) {
      throw ExceptionUtils.throwException(e);
    }
  }

  private static <G> G construct(
      final MethodHandle ctor, final Object[] references, final Class<G> type) {
    try {
      final Object codec = ctor.invoke(references);
      return type.cast(codec);
    } catch (Throwable e) {
      throw ExceptionUtils.throwException(e);
    }
  }
}
