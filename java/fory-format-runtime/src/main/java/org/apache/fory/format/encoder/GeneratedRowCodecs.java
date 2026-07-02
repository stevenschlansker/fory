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
import org.apache.fory.collection.LongMap;
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
 * <p>A precompiled class is resolved by the stable, unique-id-free name the processor emits (the
 * same name the code-generation module's runtime probe uses), so the two agree by construction.
 * When the class is absent this throws rather than falling back to code generation.
 * Schema-evolution encoders additionally bind each historical version to its precompiled projection
 * codec.
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

  /**
   * Build a schema-evolution row encoder for {@code beanClass}. The current-version codec is
   * resolved as above; in addition, each historical version enumerated from the bean's schema
   * history is bound to a {@link BinaryRowEncoder.ProjectionSource} that resolves the precompiled
   * projection codec class on the first decode of that version's hash. Nothing is generated: the
   * projection classes and their nested inner codecs are all precompiled by {@code @ForyGenerate}.
   */
  @SuppressWarnings("unchecked")
  public static <T> RowEncoder<T> evolvingRowEncoder(
      final Class<T> beanClass, final RowFormat format, final Fory fory) {
    final Encoding encoding = encoding(format);
    final Schema schema = encoding.sortSchema(TypeInference.inferSchema(beanClass));
    final Class<?> codecClass = loadCodecClass(beanClass, "", format, GeneratedRowEncoder.class);
    final MethodHandle ctor = constructorHandleFor(codecClass, GeneratedRowEncoder.class);
    final BaseBinaryRowWriter writer = encoding.newWriter(schema);
    final GeneratedRowEncoder codec = construct(ctor, new Object[] {schema, writer, fory});

    final ClassLoader buildLoader = captureClassLoader();
    final LongMap<BinaryRowEncoder.ProjectionSource> projectionSources = new LongMap<>();
    for (final ProjectionVariant.Row variant : ProjectionVariants.forRow(beanClass, encoding)) {
      projectionSources.put(
          variant.hash(), rowProjectionSource(variant, format, encoding, buildLoader));
    }
    return new BinaryRowEncoder<>(
        schema,
        codec,
        writer,
        true,
        ProjectionVariants.currentRowHash(beanClass, encoding),
        projectionSources.size == 0 ? null : projectionSources,
        fory);
  }

  /**
   * A projection source that resolves the precompiled projection codec class for one historical row
   * version by name and constructs it on first decode of that version's hash. Throws if the class
   * is absent, since the runtime-only path never falls back to code generation.
   */
  private static BinaryRowEncoder.ProjectionSource rowProjectionSource(
      final ProjectionVariant.Row variant,
      final RowFormat format,
      final Encoding encoding,
      final ClassLoader buildLoader) {
    return (projectionWriter, projectionFory) -> {
      final Schema historicalSchema = variant.historicalSchema();
      final Class<?> projectionClass =
          withClassLoader(
              buildLoader,
              () ->
                  loadCodecClass(
                      variant.beanClass(),
                      "",
                      variant.suffix(),
                      format,
                      GeneratedRowEncoder.class));
      final MethodHandle ctor = constructorHandleFor(projectionClass, GeneratedRowEncoder.class);
      final RowFactory rowFactory = encoding.newRowFactory(historicalSchema);
      final GeneratedRowEncoder projectionCodec =
          construct(ctor, new Object[] {historicalSchema, projectionWriter, projectionFory});
      return new BinaryRowEncoder.ProjectionCodec(rowFactory, projectionCodec);
    };
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

  /**
   * Build a schema-evolution array encoder for {@code collectionType}. Mirrors {@link
   * #evolvingRowEncoder} over the element field: each historical element version is bound to a
   * projection source that resolves the precompiled projection array codec on first decode of its
   * hash.
   */
  public static <C extends Collection<?>> ArrayEncoder<C> evolvingArrayEncoder(
      final TypeRef<C> collectionType, final RowFormat format, final Fory fory) {
    final Encoding encoding = encoding(format);
    Schema schema = encoding.sortSchema(TypeInference.inferSchema(collectionType, false));
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

    final ClassLoader buildLoader = captureClassLoader();
    final LongMap<BinaryArrayEncoder.ProjectionSource> projectionSources = new LongMap<>();
    for (final ProjectionVariant.Array variant :
        ProjectionVariants.forArray(collectionType, elementType, elementClass, encoding)) {
      projectionSources.put(
          variant.hash(), arrayProjectionSource(variant, format, encoding, buildLoader));
    }
    // Pass the projection map even when empty (unlike the row path): the array wire form carries
    // the
    // 8-byte element-schema-hash header only in evolution mode, which the encoder keys on a
    // non-null
    // projectionSources. A bean with no historical versions of its own must still emit that header
    // so
    // an evolving peer can dispatch, so an empty map here is not collapsed to null.
    final BinaryArrayEncoder<C> encoder =
        new BinaryArrayEncoder<>(
            writer,
            codec,
            true,
            ProjectionVariants.currentArrayHash(collectionType, elementType, encoding),
            projectionSources,
            fory);
    return new BufferResettingArrayEncoder<>(16, writer, encoder);
  }

  private static BinaryArrayEncoder.ProjectionSource arrayProjectionSource(
      final ProjectionVariant.Array variant,
      final RowFormat format,
      final Encoding encoding,
      final ClassLoader buildLoader) {
    return projectionFory -> {
      final Class<?> projectionClass =
          withClassLoader(
              buildLoader,
              () ->
                  loadCodecClass(
                      variant.elementClass(),
                      variant.prefix(),
                      variant.suffix(),
                      format,
                      GeneratedArrayEncoder.class));
      final MethodHandle ctor = constructorHandleFor(projectionClass, GeneratedArrayEncoder.class);
      final Field histListField = variant.historicalListField();
      final BinaryArrayWriter projWriter = encoding.newArrayWriter(histListField);
      final GeneratedArrayEncoder projectionCodec =
          construct(
              ctor,
              new Object[] {histListField, projWriter, projectionFory},
              GeneratedArrayEncoder.class);
      return new BinaryArrayEncoder.ProjectionArrayCodec(projWriter, projectionCodec);
    };
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
   * Build a schema-evolution map encoder for {@code mapType}. Enumerates the (key-version,
   * value-version) cross-product from the shared deriver and binds each combination to a projection
   * source that resolves the precompiled projection map codec on first decode of the combination's
   * combined hash. Like the array path, the projection map is passed even when empty so the
   * evolution header is always written.
   */
  public static <M extends Map<?, ?>> MapEncoder<M> evolvingMapEncoder(
      final TypeRef<M> mapType, final RowFormat format, final Fory fory) {
    final Encoding encoding = encoding(format);
    final Schema schema = encoding.sortSchema(TypeInference.inferSchema(mapType, false));
    final Field field = DataTypes.fieldOfSchema(schema, 0);
    final Field keyField = DataTypes.keyArrayFieldForMap(field);
    final Field valField = DataTypes.itemArrayFieldForMap(field);
    final org.apache.fory.collection.Tuple2<TypeRef<?>, TypeRef<?>> kvType =
        TypeUtils.getMapKeyValueType(mapType);
    final TypeRef<?> keyType = kvType.f0;
    final TypeRef<?> valType = kvType.f1;
    // The current codec is named on the key bean, else value bean, else Object — matching the
    // non-evolution path and the precompiler's current-codec naming (which key on isBean, not on
    // whether the bean evolves). The projection enumeration below anchors on the evolving beans,
    // which is a distinct choice: a bean can be a map position without carrying a schema history.
    final Class<?> nameClass;
    if (TypeUtils.isBean(keyType)) {
      nameClass = TypeUtils.getRawType(keyType);
    } else if (TypeUtils.isBean(valType)) {
      nameClass = TypeUtils.getRawType(valType);
    } else {
      nameClass = Object.class;
    }
    final Class<?> keyClass = ProjectionVariants.evolutionBean(keyType);
    final Class<?> valClass = ProjectionVariants.evolutionBean(valType);
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

    final ClassLoader buildLoader = captureClassLoader();
    final LongMap<BinaryMapEncoder.ProjectionSource> projectionSources = new LongMap<>();
    for (final ProjectionVariant.MapVariant variant :
        ProjectionVariants.forMap(mapType, keyType, valType, valClass, keyClass, encoding)) {
      projectionSources.put(variant.hash(), mapProjectionSource(variant, format, buildLoader));
    }
    final BinaryMapEncoder<M> encoder =
        new BinaryMapEncoder<>(
            encoding,
            field,
            valWriter,
            keyWriter,
            codec,
            true,
            ProjectionVariants.currentMapHash(
                mapType, keyType, valType, valClass, keyClass, encoding),
            projectionSources,
            fory);
    return new BufferResettingMapEncoder<>(16, keyWriter, valWriter, encoder);
  }

  private static BinaryMapEncoder.ProjectionSource mapProjectionSource(
      final ProjectionVariant.MapVariant variant,
      final RowFormat format,
      final ClassLoader buildLoader) {
    // The variant's map-class suffix concatenates the value suffix and, if the key evolves, a keyed
    // segment — mirroring the code-generation module's MapEncoderBuilder.mapClassSuffix().
    final String keySuffix = variant.keySuffix();
    final String variantSuffix =
        variant.valSuffix() + (keySuffix.isEmpty() ? "" : "_K" + keySuffix);
    return (projFormat, projectionFory) -> {
      final Class<?> projectionClass =
          withClassLoader(
              buildLoader,
              () ->
                  loadCodecClass(
                      variant.beanClass(),
                      variant.prefix(),
                      variantSuffix,
                      format,
                      GeneratedMapEncoder.class));
      final MethodHandle ctor = constructorHandleFor(projectionClass, GeneratedMapEncoder.class);
      final Field histMapField = variant.historicalMapField();
      final Field histKeyField = DataTypes.keyArrayFieldForMap(histMapField);
      final Field histValField = DataTypes.itemArrayFieldForMap(histMapField);
      final BinaryArrayWriter projKey = projFormat.newArrayWriter(histKeyField);
      final BinaryArrayWriter projVal =
          projFormat.newArrayWriter(histValField, projKey.getBuffer());
      final GeneratedMapEncoder projectionCodec =
          construct(
              ctor,
              new Object[] {
                histKeyField, histValField, projKey, projVal, projectionFory, histMapField
              },
              GeneratedMapEncoder.class);
      return new BinaryMapEncoder.ProjectionMapCodec(projFormat, histMapField, projectionCodec);
    };
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
    return loadCodecClass(beanClass, prefix, "", format, expectedGenerated);
  }

  /**
   * Resolve a precompiled codec class. {@code prefix} sits before the {@code RowCodec}/{@code
   * CompactCodec} base (naming the collection or map wrapper); {@code variantSuffix} sits after it
   * (the empty string for the current version, or a projection variant's version suffix). Both
   * placements mirror the code-generation module's class naming so a runtime probe and the emitted
   * class name agree by construction.
   */
  private static Class<?> loadCodecClass(
      final Class<?> beanClass,
      final String prefix,
      final String variantSuffix,
      final RowFormat format,
      final Class<?> expectedGenerated) {
    final String suffix = format == RowFormat.COMPACT ? "CompactCodec" : "RowCodec";
    final String simple =
        (ReflectionUtils.getClassNameWithoutPackage(beanClass) + prefix + suffix + variantSuffix)
            .replace("$", "_");
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

  /**
   * The classloader that built an evolving encoder, captured so lazy projection resolution can
   * reach the precompiled classes even when it runs on a decode thread with a different context
   * classloader. Falls back to this class's loader when no context classloader is set, mirroring
   * the code-generation module's {@code BaseCodecBuilder}.
   */
  private static ClassLoader captureClassLoader() {
    final ClassLoader tccl = Thread.currentThread().getContextClassLoader();
    return tccl != null ? tccl : GeneratedRowCodecs.class.getClassLoader();
  }

  /**
   * Run {@code resolve} with {@code loader} installed as the thread context classloader, restoring
   * the previous one afterward. Projection codecs resolve lazily on the first decode of an older
   * version's hash, possibly on a thread whose context classloader cannot see the precompiled
   * classes; pinning resolution to the loader that built the encoder makes {@link #tryForName}
   * probe the right classes regardless of the decode thread.
   */
  private static <R> R withClassLoader(
      final ClassLoader loader, final java.util.function.Supplier<R> resolve) {
    final Thread thread = Thread.currentThread();
    final ClassLoader prev = thread.getContextClassLoader();
    if (prev == loader) {
      return resolve.get();
    }
    try {
      thread.setContextClassLoader(loader);
      return resolve.get();
    } finally {
      thread.setContextClassLoader(prev);
    }
  }

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
      // Adapt to an Object return so callers can invokeExact with a fixed (Object) static type
      // regardless of the concrete Generated*Encoder subtype the handle constructs.
      return MethodHandles.lookup()
          .unreflectConstructor(constructor)
          .asType(MethodType.methodType(Object.class, Object[].class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new EncoderException("Failed to resolve constructor for " + generatedClass, e);
    }
  }

  private static GeneratedRowEncoder construct(final MethodHandle ctor, final Object[] references) {
    try {
      return (GeneratedRowEncoder) (Object) ctor.invokeExact(references);
    } catch (Throwable e) {
      throw ExceptionUtils.throwException(e);
    }
  }

  private static <G> G construct(
      final MethodHandle ctor, final Object[] references, final Class<G> type) {
    try {
      final Object codec = (Object) ctor.invokeExact(references);
      return type.cast(codec);
    } catch (Throwable e) {
      throw ExceptionUtils.throwException(e);
    }
  }
}
