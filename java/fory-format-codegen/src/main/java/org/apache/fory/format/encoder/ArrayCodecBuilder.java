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

import static org.apache.fory.type.TypeUtils.getRawType;

import java.lang.invoke.MethodHandle;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.fory.Fory;
import org.apache.fory.collection.LongMap;
import org.apache.fory.format.row.binary.writer.BinaryArrayWriter;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.format.type.Field;
import org.apache.fory.format.type.TypeInference;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.util.ExceptionUtils;

public class ArrayCodecBuilder<C extends Collection<?>>
    extends BaseCodecBuilder<ArrayCodecBuilder<C>> {

  private final TypeRef<C> collectionType;
  private final Field elementField;

  ArrayCodecBuilder(final TypeRef<C> collectionType) {
    super(TypeInference.inferSchema(collectionType, false));
    this.collectionType = collectionType;
    elementField = DataTypes.fieldOfSchema(schema, 0);
  }

  public Supplier<ArrayEncoder<C>> build() {
    final Function<BinaryArrayWriter, ArrayEncoder<C>> arrayEncoderFactory = buildWithWriter();
    return new Supplier<ArrayEncoder<C>>() {
      @Override
      public ArrayEncoder<C> get() {
        final BinaryArrayWriter writer = codecFormat.newArrayWriter(elementField);
        return new BufferResettingArrayEncoder<>(
            initialBufferSize, writer, arrayEncoderFactory.apply(writer));
      }
    };
  }

  Function<BinaryArrayWriter, ArrayEncoder<C>> buildWithWriter() {
    loadArrayInnerCodecs();
    final TypeRef<?> elementType = TypeUtils.getElementType(collectionType);
    final Class<?> elementClass =
        schemaEvolution ? ProjectionVariants.evolutionBean(elementType) : null;
    if (elementClass == null) {
      final Function<BinaryArrayWriter, GeneratedArrayEncoder> generatedEncoderFactory =
          generatedEncoderFactory();
      return new Function<BinaryArrayWriter, ArrayEncoder<C>>() {
        @Override
        public ArrayEncoder<C> apply(final BinaryArrayWriter writer) {
          return new BinaryArrayEncoder<>(
              writer, generatedEncoderFactory.apply(writer), sizeEmbedded);
        }
      };
    }
    return buildVersionedWithWriter(elementType, elementClass);
  }

  private Function<BinaryArrayWriter, ArrayEncoder<C>> buildVersionedWithWriter(
      final TypeRef<?> elementType, final Class<?> elementClass) {
    // Index of hash → deferred projection source per non-current combination, from the shared
    // enumeration so the runtime dispatch keys and the precompiler's emitted class names come from
    // one deriver. The enumeration walks the element field over every versioned bean reachable
    // through its wrappers, so an element like Map<KBean, VBean> evolves both the key and value
    // beans. Building the index compiles nothing: a combination's row and array codec classes are
    // generated the first time a payload with that hash is decoded. Keyed by the strict hash, which
    // SchemaHistory already proves is unique across versions() and distinct from the current
    // schema,
    // so no builder-side collision check is needed here (unlike the map codec's combined hash).
    LongMap<BinaryArrayEncoder.ProjectionSource> projectionSources = new LongMap<>();
    for (ProjectionVariant.Array variant :
        ProjectionVariants.forArray(collectionType, elementType, elementClass, codecFormat)) {
      projectionSources.put(variant.hash(), new ProjectionSource(variant));
    }
    final Function<BinaryArrayWriter, GeneratedArrayEncoder> currentFactory =
        generatedEncoderFactory();
    // The current element schema's strict hash keys the hot path; it is the one version the shared
    // enumeration omits (it is served by the unsuffixed codec).
    long currentHash =
        buildElementSchemaHistory(elementField.name(), elementType).current().strictHash();
    return new Function<BinaryArrayWriter, ArrayEncoder<C>>() {
      @Override
      public ArrayEncoder<C> apply(final BinaryArrayWriter writer) {
        return new BinaryArrayEncoder<>(
            writer,
            currentFactory.apply(writer),
            sizeEmbedded,
            currentHash,
            projectionSources,
            fory);
      }
    };
  }

  /**
   * Deferred projection codec for one historical element version. Holds only the inputs to generate
   * the codec; the row and array codec classes are compiled on the first {@link #compile} call (the
   * first decode of this version's hash), not at build time.
   */
  private final class ProjectionSource implements BinaryArrayEncoder.ProjectionSource {
    private final ProjectionVariant.Array variant;

    ProjectionSource(ProjectionVariant.Array variant) {
      this.variant = variant;
    }

    @Override
    public BinaryArrayEncoder.ProjectionArrayCodec compile(Fory fory) {
      return withBuildTimeClassLoader(() -> compileProjection(fory));
    }

    private BinaryArrayEncoder.ProjectionArrayCodec compileProjection(Fory fory) {
      // Generates the projection row codec for every nested versioned bean class in this
      // combination, both map key and value, so the array codec's references all resolve.
      Class<?> arrayClass =
          Encoders.loadOrGenProjectionArrayCodecClass(
              variant.collectionType(),
              TypeRef.of(variant.elementClass()),
              codecFormat,
              variant.suffix(),
              variant.nestedSuffixes());
      MethodHandle ctor = Encoders.constructorHandleFor(arrayClass, GeneratedArrayEncoder.class);
      // forElement substitutes each chosen historical struct into its leaf, so the element field at
      // this combination is simply the single field of vs.schema(); wrap it back in the list field.
      Field histListField = variant.historicalListField();
      try {
        BinaryArrayWriter projWriter = codecFormat.newArrayWriter(histListField);
        Object[] references = {histListField, projWriter, fory};
        GeneratedArrayEncoder codec = (GeneratedArrayEncoder) ctor.invokeExact(references);
        return new BinaryArrayEncoder.ProjectionArrayCodec(projWriter, codec);
      } catch (Throwable e) {
        throw ExceptionUtils.throwException(e);
      }
    }
  }

  private void loadArrayInnerCodecs() {
    final Set<TypeRef<?>> set = new HashSet<>();
    Encoders.findBeanToken(collectionType, set);
    if (set.isEmpty()) {
      throw new IllegalArgumentException("can not find bean class.");
    }

    for (final TypeRef<?> tt : set) {
      Encoders.loadOrGenRowCodecClass(getRawType(tt), codecFormat);
    }
  }

  Function<BinaryArrayWriter, GeneratedArrayEncoder> generatedEncoderFactory() {
    final TypeRef<?> elementType = TypeUtils.getElementType(collectionType);
    final Class<?> arrayCodecClass =
        Encoders.loadOrGenArrayCodecClass(collectionType, elementType, codecFormat);
    final MethodHandle constructorHandle =
        Encoders.constructorHandleFor(arrayCodecClass, GeneratedArrayEncoder.class);
    return new Function<BinaryArrayWriter, GeneratedArrayEncoder>() {
      @Override
      public GeneratedArrayEncoder apply(final BinaryArrayWriter writer) {
        final Object[] references = {writer.getField(), writer, fory};
        try {
          return (GeneratedArrayEncoder) constructorHandle.invokeExact(references);
        } catch (Throwable t) {
          throw ExceptionUtils.throwException(t);
        }
      }
    };
  }
}
