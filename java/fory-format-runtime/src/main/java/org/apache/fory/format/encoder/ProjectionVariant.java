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

import java.util.Map;
import java.util.Set;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.format.type.Field;
import org.apache.fory.format.type.Schema;
import org.apache.fory.format.type.SchemaHistory;
import org.apache.fory.reflect.TypeRef;

/**
 * One historical projection of a row, array, or map codec: a single combination of the versions the
 * schema history reaches, together with the identity that combination's generated codec class has
 * on the wire and on the classpath. A variant is the single owner of that derivation — the strict
 * (or combined) hash that keys it, the class-name suffix(es), the per-nested-bean suffix routing,
 * and the projected historical field.
 *
 * <p>Both consumers of a codec's version cross-product read variants instead of re-deriving them.
 * The runtime codec builders index one deferred projection source per variant, keyed by {@link
 * #hash()}, and their {@code compile} reads the variant's projected field and suffixes; the
 * build-time precompiler and code-generation builders derive each variant's class name and source
 * from the same identity (see {@code ProjectionCodegen} in the code-generation module). Because a
 * single deriver feeds both, the precompiled class name a runtime probe looks for is equal to the
 * emitted name by construction.
 *
 * <p>Variants carry no {@link org.apache.fory.Fory}, writer, or method handle: they are the pure
 * identity half of projection codegen. Constructing the writers and invoking the constructor is the
 * runtime half and stays in each codec builder's projection source. The {@link Encoding} a variant
 * holds is the codegen-free runtime encoding; the code-generation module attaches source emission
 * externally.
 */
abstract class ProjectionVariant {

  private final Encoding encoding;

  ProjectionVariant(final Encoding encoding) {
    this.encoding = encoding;
  }

  final Encoding encoding() {
    return encoding;
  }

  /** The LongMap key the runtime dispatches this variant on: strict hash, or combined map hash. */
  abstract long hash();

  /**
   * A row projection: one historical {@link SchemaHistory.VersionedSchema} of a bean. The strict
   * hash keys it; the suffix and nested-suffix map name the generated class and route each nested
   * versioned bean to its own projection codec.
   */
  static final class Row extends ProjectionVariant {
    private final Class<?> beanClass;
    private final SchemaHistory.VersionedSchema version;
    private final String suffix;
    private final Map<Class<?>, String> nestedSuffixes;

    Row(
        final Encoding encoding,
        final Class<?> beanClass,
        final SchemaHistory.VersionedSchema version) {
      super(encoding);
      this.beanClass = beanClass;
      this.version = version;
      this.suffix = ProjectionRouting.projectionSuffix(version);
      this.nestedSuffixes = ProjectionRouting.nestedSuffixesFor(version);
    }

    Class<?> beanClass() {
      return beanClass;
    }

    SchemaHistory.VersionedSchema version() {
      return version;
    }

    Schema historicalSchema() {
      return version.schema();
    }

    Set<String> liveFieldNames() {
      return version.liveFieldNames();
    }

    String suffix() {
      return suffix;
    }

    Map<Class<?>, String> nestedSuffixes() {
      return nestedSuffixes;
    }

    @Override
    long hash() {
      return version.strictHash();
    }
  }

  /**
   * An array projection: one historical version of the element field, enumerated over every
   * versioned bean reachable through the element's wrappers. The historical list field wraps the
   * chosen element schema so the runtime writer and the generated codec agree on layout.
   */
  static final class Array extends ProjectionVariant {
    private final TypeRef<? extends java.util.Collection<?>> collectionType;
    private final Class<?> elementClass;
    private final String elementName;
    private final String prefix;
    private final SchemaHistory.VersionedSchema version;
    private final String suffix;
    private final Map<Class<?>, String> nestedSuffixes;

    Array(
        final Encoding encoding,
        final TypeRef<? extends java.util.Collection<?>> collectionType,
        final Class<?> elementClass,
        final String elementName,
        final String prefix,
        final SchemaHistory.VersionedSchema version) {
      super(encoding);
      this.collectionType = collectionType;
      this.elementClass = elementClass;
      this.elementName = elementName;
      this.prefix = prefix;
      this.version = version;
      this.suffix = ProjectionRouting.projectionSuffix(version);
      this.nestedSuffixes = ProjectionRouting.nestedSuffixesFor(version);
    }

    TypeRef<? extends java.util.Collection<?>> collectionType() {
      return collectionType;
    }

    Class<?> elementClass() {
      return elementClass;
    }

    String prefix() {
      return prefix;
    }

    SchemaHistory.VersionedSchema version() {
      return version;
    }

    String suffix() {
      return suffix;
    }

    Map<Class<?>, String> nestedSuffixes() {
      return nestedSuffixes;
    }

    /** The list field wrapping the chosen historical element schema, for the projection writer. */
    Field historicalListField() {
      return DataTypes.arrayField(elementName, DataTypes.fieldOfSchema(version.schema(), 0));
    }

    @Override
    long hash() {
      return version.strictHash();
    }
  }

  /**
   * A map projection: one (key-version, value-version) combination. Each position independently
   * takes an empty suffix when it is at its current schema (or carries no evolving bean), so a map
   * where only one side evolves pays no codegen for the unchanged side. The combined hash keys the
   * variant; the composite class suffix (value suffix plus a {@code _K}-prefixed key suffix) names
   * the generated class.
   */
  static final class MapVariant extends ProjectionVariant {
    private final TypeRef<? extends Map<?, ?>> mapType;
    private final TypeRef<?> beanToken;
    private final Class<?> beanClass;
    private final String prefix;
    private final Field mapField;
    private final SchemaHistory.VersionedSchema valVs;
    private final SchemaHistory.VersionedSchema keyVs;
    private final long hash;
    private final String valSuffix;
    private final String keySuffix;
    private final Map<Class<?>, String> valNested;
    private final Map<Class<?>, String> keyNested;

    MapVariant(
        final Encoding encoding,
        final TypeRef<? extends Map<?, ?>> mapType,
        final TypeRef<?> beanToken,
        final Class<?> beanClass,
        final String prefix,
        final Field mapField,
        final SchemaHistory.VersionedSchema valVs,
        final SchemaHistory.VersionedSchema keyVs,
        final long hash) {
      super(encoding);
      this.mapType = mapType;
      this.beanToken = beanToken;
      this.beanClass = beanClass;
      this.prefix = prefix;
      this.mapField = mapField;
      this.valVs = valVs;
      this.keyVs = keyVs;
      this.hash = hash;
      // An evolving position at a non-current version contributes a suffix and nested routing; a
      // current or non-bean position keeps the empty suffix and reads at its current schema.
      if (valVs != null) {
        this.valSuffix = ProjectionRouting.projectionSuffix(valVs);
        this.valNested = ProjectionRouting.nestedSuffixesFor(valVs);
      } else {
        this.valSuffix = "";
        this.valNested = null;
      }
      if (keyVs != null) {
        this.keySuffix = ProjectionRouting.projectionSuffix(keyVs);
        this.keyNested = ProjectionRouting.nestedSuffixesFor(keyVs);
      } else {
        this.keySuffix = "";
        this.keyNested = null;
      }
    }

    TypeRef<? extends Map<?, ?>> mapType() {
      return mapType;
    }

    TypeRef<?> beanToken() {
      return beanToken;
    }

    Class<?> beanClass() {
      return beanClass;
    }

    String prefix() {
      return prefix;
    }

    SchemaHistory.VersionedSchema valVersion() {
      return valVs;
    }

    SchemaHistory.VersionedSchema keyVersion() {
      return keyVs;
    }

    String valSuffix() {
      return valSuffix;
    }

    String keySuffix() {
      return keySuffix;
    }

    Map<Class<?>, String> valNested() {
      return valNested;
    }

    Map<Class<?>, String> keyNested() {
      return keyNested;
    }

    /** The value position field projected onto its chosen version, or the current field. */
    Field historicalValueField() {
      Field currentVal = DataTypes.itemFieldForMap(mapField);
      return valVs == null ? currentVal : projectedPositionField(currentVal, valVs);
    }

    /** The key position field projected onto its chosen version, or the current field. */
    Field historicalKeyField() {
      Field currentKey = DataTypes.keyFieldForMap(mapField);
      return keyVs == null ? currentKey : projectedPositionField(currentKey, keyVs);
    }

    /** The historical map field combining the projected key and value fields. */
    Field historicalMapField() {
      return DataTypes.mapField(mapField.name(), historicalKeyField(), historicalValueField());
    }

    /**
     * The map position field projected onto {@code positionVs}: the substituted type from the
     * position's historical schema, but the current position field's name and nullability (a map
     * key is non-nullable, a value nullable), which forElement's inferred field does not carry.
     */
    private static Field projectedPositionField(
        final Field currentField, final SchemaHistory.VersionedSchema positionVs) {
      Field projected = DataTypes.fieldOfSchema(positionVs.schema(), 0);
      return DataTypes.field(currentField.name(), projected.type(), currentField.nullable());
    }

    @Override
    long hash() {
      return hash;
    }
  }
}
