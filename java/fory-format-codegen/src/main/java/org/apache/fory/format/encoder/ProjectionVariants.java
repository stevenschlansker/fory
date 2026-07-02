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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.apache.fory.collection.LongMap;
import org.apache.fory.format.row.binary.writer.CompactBinaryRowWriter;
import org.apache.fory.format.type.CustomTypeEncoderRegistry;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.format.type.Field;
import org.apache.fory.format.type.Schema;
import org.apache.fory.format.type.SchemaHistory;
import org.apache.fory.format.type.TypeInference;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.TypeResolutionContext;

/**
 * The single owner of a codec's projection cross-product. Walking a bean's (or an array element's,
 * or a map's key/value) schema history and turning each reachable version combination into a {@link
 * ProjectionVariant} happens here and nowhere else, so the build-time {@link PrecompileApi} and the
 * runtime codec builders enumerate exactly the same set. Both then read the variants' derived
 * identity — hash, class name, suffixes, projected fields — rather than deriving it a second time.
 *
 * <p>Enumeration compiles nothing: it produces the identity descriptors. The runtime builders index
 * them lazily and compile a combination's codec class on the first decode of its hash; the
 * precompiler emits every combination's source up front. The map enumerator additionally proves the
 * combined (key, value) hashes are collision-free, the one build-time check both callers share.
 */
final class ProjectionVariants {

  private ProjectionVariants() {}

  /**
   * Every non-current row projection of {@code beanClass}, keyed by the strict hash the runtime
   * dispatches on. The current version is served by the unsuffixed codec and is not a variant.
   */
  static List<ProjectionVariant.Row> forRow(
      final Class<?> beanClass, final CodecEncoding encoding) {
    SchemaHistory history = SchemaHistory.build(beanClass, schemaTransform(encoding));
    SchemaHistory.VersionedSchema current = history.current();
    List<ProjectionVariant.Row> variants = new ArrayList<>();
    for (SchemaHistory.VersionedSchema vs : history.versions()) {
      if (vs == current) {
        continue;
      }
      variants.add(new ProjectionVariant.Row(encoding, beanClass, vs));
    }
    return variants;
  }

  /**
   * Every non-current array projection over the element field, enumerated across every versioned
   * bean reachable through the element's wrappers so an element like {@code Map<KBean, VBean>}
   * evolves both. Keyed by the element schema's strict hash.
   */
  static List<ProjectionVariant.Array> forArray(
      final TypeRef<? extends Collection<?>> collectionType,
      final TypeRef<?> elementType,
      final Class<?> elementClass,
      final CodecEncoding encoding) {
    Field elementField =
        DataTypes.fieldOfSchema(TypeInference.inferSchema(collectionType, false), 0);
    String elementName = elementField.name();
    String prefix = TypeInference.inferTypeName(collectionType);
    SchemaHistory history =
        SchemaHistory.forElement(elementName, elementType, schemaTransform(encoding));
    SchemaHistory.VersionedSchema current = history.current();
    List<ProjectionVariant.Array> variants = new ArrayList<>();
    for (SchemaHistory.VersionedSchema vs : history.versions()) {
      if (vs == current) {
        continue;
      }
      variants.add(
          new ProjectionVariant.Array(
              encoding, collectionType, elementClass, elementName, prefix, vs));
    }
    return variants;
  }

  /**
   * Every non-current map projection over the full (value-version × key-version) cross-product. The
   * two positions are independent wire arrays, so a bean pinned to different versions across key
   * and value (such as {@code Map<DefaultsV1, DefaultsV2>}) is a reachable off-diagonal combination
   * and is enumerated. A position with no evolving bean contributes a single current (null) entry,
   * so the cross-product degenerates to the evolving position's versions.
   *
   * <p>The map header carries a single hash, so each combination's key and value strict hashes are
   * combined into one 64-bit layout hash. This method proves those combined hashes are unique
   * across the cross-product and distinct from the current schema's, failing fast at build time —
   * the one build-time guard both the runtime builder and the precompiler rely on.
   *
   * @param valClass the value's evolving bean, or null if the value carries none
   * @param keyClass the key's evolving bean, or null if the key carries none
   */
  static List<ProjectionVariant.MapVariant> forMap(
      final TypeRef<? extends Map<?, ?>> mapType,
      final TypeRef<?> keyType,
      final TypeRef<?> valType,
      final Class<?> valClass,
      final Class<?> keyClass,
      final CodecEncoding encoding) {
    UnaryOperator<Schema> transform = schemaTransform(encoding);
    Field mapField = DataTypes.fieldOfSchema(TypeInference.inferSchema(mapType, false), 0);
    String prefix = TypeInference.inferTypeName(mapType);

    SchemaHistory valHistory =
        valClass == null ? null : SchemaHistory.forElement(mapField.name(), valType, transform);
    SchemaHistory keyHistory =
        keyClass == null ? null : SchemaHistory.forElement(mapField.name(), keyType, transform);
    SchemaHistory.VersionedSchema valCurrent = valHistory == null ? null : valHistory.current();
    SchemaHistory.VersionedSchema keyCurrent = keyHistory == null ? null : keyHistory.current();

    TypeRef<?> beanToken = valClass != null ? valType : keyType;
    Class<?> beanClass = valClass != null ? valClass : keyClass;

    List<SchemaHistory.VersionedSchema> valVersions = positionVersions(valHistory);
    List<SchemaHistory.VersionedSchema> keyVersions = positionVersions(keyHistory);
    List<ProjectionVariant.MapVariant> variants = new ArrayList<>();
    // Reject a combined-hash clash between two combinations, and (below) against the current
    // schema:
    // the decode hot path matches the current hash before consulting projections, so a projection
    // colliding with it would be shadowed and never dispatched to.
    LongMap<Boolean> seen = new LongMap<>();
    for (SchemaHistory.VersionedSchema valVs : valVersions) {
      for (SchemaHistory.VersionedSchema keyVs : keyVersions) {
        if (valVs == valCurrent && keyVs == keyCurrent) {
          continue;
        }
        long hash = SchemaHistory.combineHashes(positionHash(keyVs), positionHash(valVs));
        if (seen.containsKey(hash)) {
          throw new IllegalStateException(
              "Combined (key, value) schema-hash collision for map "
                  + mapType
                  + ": two distinct version combinations produced the same map-layout hash. "
                  + "Please file an issue with the key and value bean definitions.");
        }
        seen.put(hash, Boolean.TRUE);
        // A position at its current schema reads at that schema and contributes no suffix; pass
        // null
        // so the variant keeps the empty suffix and the current field for that side.
        SchemaHistory.VersionedSchema valChosen = valVs == valCurrent ? null : valVs;
        SchemaHistory.VersionedSchema keyChosen = keyVs == keyCurrent ? null : keyVs;
        variants.add(
            new ProjectionVariant.MapVariant(
                encoding, mapType, beanToken, beanClass, prefix, mapField, valChosen, keyChosen,
                hash));
      }
    }
    long currentHash =
        SchemaHistory.combineHashes(positionHash(keyCurrent), positionHash(valCurrent));
    if (seen.containsKey(currentHash)) {
      throw new IllegalStateException(
          "Combined (key, value) schema-hash collision for map "
              + mapType
              + ": a historical version combination produced the same map-layout hash as the "
              + "current schema. Please file an issue with the key and value bean definitions.");
    }
    return variants;
  }

  /** The current map-layout hash, so the runtime builder can key its current codec identically. */
  static long currentMapHash(
      final TypeRef<? extends Map<?, ?>> mapType,
      final TypeRef<?> keyType,
      final TypeRef<?> valType,
      final Class<?> valClass,
      final Class<?> keyClass,
      final CodecEncoding encoding) {
    UnaryOperator<Schema> transform = schemaTransform(encoding);
    Field mapField = DataTypes.fieldOfSchema(TypeInference.inferSchema(mapType, false), 0);
    SchemaHistory valHistory =
        valClass == null ? null : SchemaHistory.forElement(mapField.name(), valType, transform);
    SchemaHistory keyHistory =
        keyClass == null ? null : SchemaHistory.forElement(mapField.name(), keyType, transform);
    long valHash = positionHash(valHistory == null ? null : valHistory.current());
    long keyHash = positionHash(keyHistory == null ? null : keyHistory.current());
    return SchemaHistory.combineHashes(keyHash, valHash);
  }

  /** Whether {@code elementType} reaches an evolving bean the enumeration should walk. */
  static Class<?> evolutionBean(final TypeRef<?> elementType) {
    return SchemaHistory.evolutionBean(
        elementType,
        new TypeResolutionContext(CustomTypeEncoderRegistry.customTypeHandler(), true));
  }

  private static List<SchemaHistory.VersionedSchema> positionVersions(final SchemaHistory history) {
    return history == null ? Collections.singletonList(null) : history.versions();
  }

  // Strict hash contributed by one map position: its schema's hash, or the non-bean constant. A
  // position that carries no versioned bean has a single fixed wire layout, so a constant identity
  // is correct on both writer and reader and leaves the combined hash determined by the position
  // that does evolve. The build-time collision guards above make the constant safe.
  private static final long NON_BEAN_POSITION_HASH = 0L;

  private static long positionHash(final SchemaHistory.VersionedSchema vs) {
    return vs == null ? NON_BEAN_POSITION_HASH : vs.strictHash();
  }

  private static UnaryOperator<Schema> schemaTransform(final CodecEncoding encoding) {
    return encoding == CompactCodecFormat.INSTANCE
        ? CompactBinaryRowWriter::sortSchema
        : UnaryOperator.identity();
  }
}
