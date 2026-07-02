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
import org.apache.fory.format.type.SchemaHistory;
import org.apache.fory.reflect.TypeRef;

/**
 * The code-generation half of a projection variant. A {@link ProjectionVariant} is pure identity —
 * hash, suffixes, projected fields — and carries no encoder builder. This helper attaches the
 * source emission the build-time precompiler needs, given a variant and the {@link CodecEncoding}
 * that selects the wire layout. Because both the emitted class name and the runtime probe name
 * derive from the same variant identity, the precompiled class a runtime probe looks for equals the
 * emitted name by construction.
 *
 * <p>It also materializes the inner projection classes a variant references. The pure {@link
 * ProjectionRouting#nestedSuffixesFor} only names them; when a codec is generated at runtime (no
 * precompiled classes present), the inner classes a generated codec references by {@code new
 * InnerCodec<suffix>} must already be on the classpath, so {@link #materializeNested} generates
 * them recursively at build time.
 */
final class ProjectionCodegen {

  private ProjectionCodegen() {}

  /** The encoder builder that emits {@code variant}'s generated codec source. */
  static BaseBinaryEncoderBuilder builder(
      final ProjectionVariant variant, final CodecEncoding encoding) {
    if (variant instanceof ProjectionVariant.Row) {
      ProjectionVariant.Row row = (ProjectionVariant.Row) variant;
      return (BaseBinaryEncoderBuilder)
          encoding.newProjectionRowEncoder(
              TypeRef.of(row.beanClass()),
              row.historicalSchema(),
              row.liveFieldNames(),
              row.suffix(),
              row.nestedSuffixes());
    }
    if (variant instanceof ProjectionVariant.Array) {
      ProjectionVariant.Array array = (ProjectionVariant.Array) variant;
      return (BaseBinaryEncoderBuilder)
          encoding.newProjectionArrayEncoder(
              array.collectionType(),
              TypeRef.of(array.elementClass()),
              array.suffix(),
              array.nestedSuffixes());
    }
    ProjectionVariant.MapVariant map = (ProjectionVariant.MapVariant) variant;
    return (BaseBinaryEncoderBuilder)
        encoding.newProjectionMapEncoder(
            map.mapType(),
            map.beanToken(),
            map.valSuffix(),
            map.keySuffix(),
            map.valNested(),
            map.keyNested());
  }

  /** The generated Java source for {@code variant}'s codec class. */
  static String genCode(final ProjectionVariant variant, final CodecEncoding encoding) {
    return builder(variant, encoding).genCode();
  }

  /** Fully qualified name of {@code variant}'s generated codec class, matching what it emits. */
  static String generatedClassName(final ProjectionVariant variant, final CodecEncoding encoding) {
    if (variant instanceof ProjectionVariant.Row) {
      ProjectionVariant.Row row = (ProjectionVariant.Row) variant;
      return builder(variant, encoding).codecQualifiedClassName(row.beanClass()) + row.suffix();
    }
    if (variant instanceof ProjectionVariant.Array) {
      ProjectionVariant.Array array = (ProjectionVariant.Array) variant;
      return builder(variant, encoding)
              .codecQualifiedClassName(array.elementClass(), array.prefix())
          + array.suffix();
    }
    ProjectionVariant.MapVariant map = (ProjectionVariant.MapVariant) variant;
    MapEncoderBuilder mapBuilder = (MapEncoderBuilder) builder(variant, encoding);
    return mapBuilder.codecQualifiedClassName(map.beanClass(), map.prefix())
        + mapBuilder.mapClassSuffix();
  }

  /**
   * Generate the inner projection row codec classes {@code variant} references, recursively, so a
   * generated codec's {@code new InnerCodec<suffix>} resolves at class load when nothing was
   * precompiled. The chosen inner version is read straight from the variant, so it materializes the
   * exact combination to arbitrary depth. A no-op for variants with no nested versioned beans.
   */
  static void materializeNested(final ProjectionVariant variant, final CodecEncoding encoding) {
    if (variant instanceof ProjectionVariant.Row) {
      materializeNested(((ProjectionVariant.Row) variant).version(), encoding);
    } else if (variant instanceof ProjectionVariant.Array) {
      materializeNested(((ProjectionVariant.Array) variant).version(), encoding);
    } else {
      ProjectionVariant.MapVariant map = (ProjectionVariant.MapVariant) variant;
      if (map.valVersion() != null) {
        materializeNested(map.valVersion(), encoding);
      }
      if (map.keyVersion() != null) {
        materializeNested(map.keyVersion(), encoding);
      }
    }
  }

  private static void materializeNested(
      final SchemaHistory.VersionedSchema vs, final CodecEncoding encoding) {
    for (Map.Entry<Class<?>, SchemaHistory.VersionedSchema> e : vs.nestedBeanSchemas().entrySet()) {
      SchemaHistory.VersionedSchema innerVs = e.getValue();
      if (innerVs.isCurrent()) {
        continue;
      }
      Class<?> innerClass = e.getKey();
      String innerSuffix = ProjectionRouting.projectionSuffix(innerVs);
      Encoders.loadOrGenProjectionRowCodecClass(
          innerClass,
          encoding,
          innerVs.schema(),
          innerVs.liveFieldNames(),
          innerSuffix,
          ProjectionRouting.nestedSuffixesFor(innerVs));
      // Recurse into the inner's own nested combination.
      materializeNested(innerVs, encoding);
    }
  }
}
