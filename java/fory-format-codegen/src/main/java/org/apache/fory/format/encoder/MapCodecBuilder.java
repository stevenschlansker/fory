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
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.apache.fory.Fory;
import org.apache.fory.collection.LongMap;
import org.apache.fory.format.row.binary.writer.BinaryArrayWriter;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.format.type.Field;
import org.apache.fory.format.type.SchemaHistory;
import org.apache.fory.format.type.TypeInference;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.util.ExceptionUtils;

public class MapCodecBuilder<M extends Map<?, ?>> extends BaseCodecBuilder<MapCodecBuilder<M>> {

  private final TypeRef<M> mapType;
  private final Field field;
  private final Field keyField;
  private final Field valField;
  private final TypeRef<?> keyType;
  private final TypeRef<?> valType;

  MapCodecBuilder(final TypeRef<M> mapType) {
    super(TypeInference.inferSchema(mapType, false));
    this.mapType = mapType;
    field = DataTypes.fieldOfSchema(schema, 0);
    keyField = DataTypes.keyArrayFieldForMap(field);
    valField = DataTypes.itemArrayFieldForMap(field);
    final var kvType = TypeUtils.getMapKeyValueType(mapType);
    keyType = kvType.f0;
    valType = kvType.f1;
  }

  public Supplier<MapEncoder<M>> build() {
    loadMapInnerCodecs();
    final Class<?> valClass = schemaEvolution ? evolutionBean() : null;
    final Class<?> keyClass = schemaEvolution ? keyEvolutionBean() : null;
    if (valClass == null && keyClass == null) {
      final var mapEncoderFactory = generatedMapEncoder();
      return new Supplier<MapEncoder<M>>() {
        @Override
        public MapEncoder<M> get() {
          final BinaryArrayWriter keyWriter = codecFormat.newArrayWriter(keyField);
          final BinaryArrayWriter valWriter =
              codecFormat.newArrayWriter(valField, keyWriter.getBuffer());
          final var codec = mapEncoderFactory.apply(keyWriter, valWriter);
          return new BufferResettingMapEncoder<>(
              initialBufferSize,
              keyWriter,
              valWriter,
              new BinaryMapEncoder<M>(
                  codecFormat, field, valWriter, keyWriter, codec, sizeEmbedded));
        }
      };
    }
    return buildVersioned(valClass, keyClass);
  }

  /**
   * Whether the value position takes the evolution path, and a representative bean for naming. A
   * directly-typed bean (versioned or not) takes the path so the strict-hash prefix is always
   * present and an evolution-on consumer can detect a flag-mismatched producer cleanly; a bean
   * nested inside a list/map/array value is found by descending the wrapper. Null when the value
   * carries no bean. The per-version enumeration over every reachable bean in the value position is
   * done by {@link #buildElementSchemaHistory}.
   */
  private Class<?> evolutionBean() {
    return SchemaHistory.evolutionBean(valType, typeCtx());
  }

  /**
   * Bean this map's key evolves on, reachable through the key type, mirroring {@link
   * #evolutionBean()} for the value. Null when the key carries no bean. A versioned key is read at
   * the matching historical layout selected by the map header's combined hash, so an evolving key
   * no longer corrupts silently.
   */
  private Class<?> keyEvolutionBean() {
    return SchemaHistory.evolutionBean(keyType, typeCtx());
  }

  private Supplier<MapEncoder<M>> buildVersioned(final Class<?> valClass, final Class<?> keyClass) {
    // Index one deferred projection source per (key-version, value-version) combination the shared
    // enumeration reaches, keyed by the combined map-layout hash. ProjectionVariants.forMap owns
    // the
    // cross-product walk and the build-time combined-hash collision guards; building the index here
    // compiles nothing, since each combination's codec classes are generated on the first decode of
    // its hash. The current/current combination is the hot path served by the unsuffixed codec, so
    // the enumeration omits it and currentMapHash keys it separately.
    LongMap<BinaryMapEncoder.ProjectionSource> projectionSources = new LongMap<>();
    for (ProjectionVariant.MapVariant variant :
        ProjectionVariants.forMap(mapType, keyType, valType, valClass, keyClass, codecFormat)) {
      ProjectionCodegen.materializeNested(variant, codecFormat);
      projectionSources.put(variant.hash(), new ProjectionSource(variant));
    }
    final var currentFactory = generatedMapEncoder();
    long currentHash =
        ProjectionVariants.currentMapHash(
            mapType, keyType, valType, valClass, keyClass, codecFormat);
    return new Supplier<MapEncoder<M>>() {
      @Override
      public MapEncoder<M> get() {
        BinaryArrayWriter keyWriter = codecFormat.newArrayWriter(keyField);
        BinaryArrayWriter valWriter = codecFormat.newArrayWriter(valField, keyWriter.getBuffer());
        var codec = currentFactory.apply(keyWriter, valWriter);
        return new BufferResettingMapEncoder<>(
            initialBufferSize,
            keyWriter,
            valWriter,
            new BinaryMapEncoder<M>(
                codecFormat,
                field,
                valWriter,
                keyWriter,
                codec,
                sizeEmbedded,
                currentHash,
                projectionSources,
                fory));
      }
    };
  }

  /**
   * Deferred projection codec for one (key-version, value-version) combination. Holds the variant
   * that owns this combination's identity; the per-position row codecs and the map codec class are
   * generated on the first {@link #compile} call (the first decode of this combination's combined
   * hash), not at build time. The build-time collision guards already proved this combination's
   * hash is unique.
   */
  private final class ProjectionSource implements BinaryMapEncoder.ProjectionSource {
    private final ProjectionVariant.MapVariant variant;

    ProjectionSource(ProjectionVariant.MapVariant variant) {
      this.variant = variant;
    }

    @Override
    public BinaryMapEncoder.ProjectionMapCodec compile(Encoding format, Fory fory) {
      return withBuildTimeClassLoader(() -> compileProjection(format, fory));
    }

    private BinaryMapEncoder.ProjectionMapCodec compileProjection(Encoding format, Fory fory) {
      // The variant owns the position suffixes and nested routing; loadOrGenProjectionMapCodecClass
      // generates the projection row codec for every nested versioned bean in each position so the
      // map codec's references resolve. The projected key/value fields carry the substituted type
      // with the current position's name and nullability (keys non-nullable, values nullable).
      Class<?> mapClass =
          Encoders.loadOrGenProjectionMapCodecClass(
              variant.mapType(),
              TypeRef.of(variant.beanClass()),
              codecFormat,
              variant.valSuffix(),
              variant.keySuffix(),
              variant.valNested(),
              variant.keyNested());
      MethodHandle ctor = Encoders.constructorHandleFor(mapClass, GeneratedMapEncoder.class);
      Field histMapField = variant.historicalMapField();
      try {
        Field histKeyField = DataTypes.keyArrayFieldForMap(histMapField);
        Field histValField = DataTypes.itemArrayFieldForMap(histMapField);
        BinaryArrayWriter projKey = format.newArrayWriter(histKeyField);
        BinaryArrayWriter projVal = format.newArrayWriter(histValField, projKey.getBuffer());
        Object[] references = {histKeyField, histValField, projKey, projVal, fory, histMapField};
        GeneratedMapEncoder codec = (GeneratedMapEncoder) ctor.invokeExact(references);
        return new BinaryMapEncoder.ProjectionMapCodec(format, histMapField, codec);
      } catch (Throwable e) {
        throw ExceptionUtils.throwException(e);
      }
    }
  }

  private void loadMapInnerCodecs() {
    Encoders.loadMapCodecs(keyType, codecFormat);
    Encoders.loadMapCodecs(valType, codecFormat);
  }

  BiFunction<BinaryArrayWriter, BinaryArrayWriter, GeneratedMapEncoder> generatedMapEncoder() {
    final Class<?> arrayCodecClass =
        Encoders.loadOrGenMapCodecClass(mapType, keyType, valType, codecFormat);

    final MethodHandle constructorHandle =
        Encoders.constructorHandleFor(arrayCodecClass, GeneratedMapEncoder.class);
    return new BiFunction<BinaryArrayWriter, BinaryArrayWriter, GeneratedMapEncoder>() {
      @Override
      public GeneratedMapEncoder apply(
          final BinaryArrayWriter keyWriter, final BinaryArrayWriter valWriter) {
        final Object[] references = {keyField, valField, keyWriter, valWriter, fory, field};
        try {
          return (GeneratedMapEncoder) constructorHandle.invokeExact(references);
        } catch (Throwable t) {
          throw ExceptionUtils.throwException(t);
        }
      }
    };
  }
}
