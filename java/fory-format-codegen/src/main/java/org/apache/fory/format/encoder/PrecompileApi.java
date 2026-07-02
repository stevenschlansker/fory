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

import static org.apache.fory.type.TypeUtils.OBJECT_TYPE;
import static org.apache.fory.type.TypeUtils.getRawType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.fory.annotation.Internal;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.format.annotation.RowFormat;
import org.apache.fory.format.type.CustomTypeEncoderRegistry;
import org.apache.fory.format.type.TypeInference;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.TypeResolutionContext;
import org.apache.fory.type.TypeUtils;

/**
 * Build-time precompilation API consumed by the {@code @ForyGenerate} annotation processor. Emits
 * the same source the row-codec builders would otherwise hand to Janino at runtime, but under
 * stable unique-id-free class names so the runtime probes in {@link Encoders} pick the precompiled
 * classes up.
 */
@Internal
public final class PrecompileApi {

  private PrecompileApi() {}

  /** A single Java source artifact: qualified class name and the source body. */
  @Internal
  public static final class GeneratedSource {
    private final String qualifiedClassName;
    private final String source;

    GeneratedSource(String qualifiedClassName, String source) {
      this.qualifiedClassName = qualifiedClassName;
      this.source = source;
    }

    public String qualifiedClassName() {
      return qualifiedClassName;
    }

    public String source() {
      return source;
    }
  }

  /**
   * Emit row codec sources for {@code beanClass} and every reachable nested bean. With evolution
   * on, also emits the (outer version &times; nested-bean versions) cross-product the runtime
   * dispatches into.
   */
  public static List<GeneratedSource> precompileRowCodecs(
      Class<?> beanClass, RowFormat format, boolean evolution) {
    checkUniqueIdDisabled();
    CodecEncoding encoding = encodingFor(format);
    List<GeneratedSource> result = new ArrayList<>();
    addRowCodecSources(beanClass, encoding, evolution, result, new HashSet<>());
    return result;
  }

  /**
   * Emit the array codec for {@code parameterizedCollection} plus row codec sources for any bean
   * element type. With evolution on, also emits the projection cross-product over the element
   * bean's schema history.
   */
  public static List<GeneratedSource> precompileArrayCodec(
      TypeRef<? extends Collection<?>> parameterizedCollection,
      TypeRef<?> elementType,
      RowFormat format,
      boolean evolution) {
    checkUniqueIdDisabled();
    CodecEncoding encoding = encodingFor(format);
    List<GeneratedSource> result = new ArrayList<>();
    Set<String> emitted = new HashSet<>();
    Class<?> elementClass = getRawType(elementType);
    boolean elementIsBean = isBean(elementType);
    if (elementIsBean) {
      addRowCodecSources(elementClass, encoding, evolution, result, emitted);
    }
    ArrayEncoderBuilder currentBuilder =
        encoding.newArrayEncoder(parameterizedCollection, elementType);
    String prefix = TypeInference.inferTypeName(parameterizedCollection);
    String currentQualified = currentBuilder.codecQualifiedClassName(elementClass, prefix);
    if (emitted.add(currentQualified)) {
      result.add(new GeneratedSource(currentQualified, currentBuilder.genCode()));
    }
    if (evolution && elementIsBean) {
      addProjectionArrayCodecSources(
          parameterizedCollection, elementType, encoding, result, emitted);
    }
    return result;
  }

  /**
   * Emit the map codec for {@code parameterizedMap} plus row codec sources for any bean key or
   * value type. With evolution on, also emits the projection cross-product over the value bean's
   * schema history.
   */
  public static List<GeneratedSource> precompileMapCodec(
      TypeRef<? extends Map<?, ?>> parameterizedMap,
      TypeRef<?> keyType,
      TypeRef<?> valueType,
      RowFormat format,
      boolean evolution) {
    checkUniqueIdDisabled();
    CodecEncoding encoding = encodingFor(format);
    List<GeneratedSource> result = new ArrayList<>();
    Set<String> emitted = new HashSet<>();
    boolean keyIsBean = isBean(keyType);
    boolean valIsBean = isBean(valueType);
    if (keyIsBean) {
      addRowCodecSources(getRawType(keyType), encoding, evolution, result, emitted);
    }
    if (valIsBean) {
      addRowCodecSources(getRawType(valueType), encoding, evolution, result, emitted);
    }
    TypeRef<?> beanToken;
    Class<?> anchor;
    if (keyIsBean) {
      anchor = getRawType(keyType);
      beanToken = keyType;
    } else if (valIsBean) {
      anchor = getRawType(valueType);
      beanToken = valueType;
    } else {
      anchor = Object.class;
      beanToken = OBJECT_TYPE;
    }
    MapEncoderBuilder currentBuilder = encoding.newMapEncoder(parameterizedMap, beanToken);
    String prefix = TypeInference.inferTypeName(parameterizedMap);
    String currentQualified = currentBuilder.codecQualifiedClassName(anchor, prefix);
    if (emitted.add(currentQualified)) {
      result.add(new GeneratedSource(currentQualified, currentBuilder.genCode()));
    }
    if (evolution && (keyIsBean || valIsBean)) {
      addProjectionMapCodecSources(parameterizedMap, keyType, valueType, encoding, result, emitted);
    }
    return result;
  }

  private static void addRowCodecSources(
      Class<?> beanClass,
      CodecEncoding encoding,
      boolean evolution,
      List<GeneratedSource> result,
      Set<String> emittedNames) {
    Set<Class<?>> beans =
        TypeUtils.listBeansRecursiveInclusive(
            beanClass,
            new TypeResolutionContext(CustomTypeEncoderRegistry.customTypeHandler(), true));
    for (Class<?> cls : beans) {
      addCurrentRowCodecSource(cls, encoding, result, emittedNames);
      if (evolution) {
        addProjectionRowCodecSources(cls, encoding, result, emittedNames);
      }
    }
  }

  private static void addCurrentRowCodecSource(
      Class<?> cls,
      CodecEncoding encoding,
      List<GeneratedSource> result,
      Set<String> emittedNames) {
    RowEncoderBuilder builder = encoding.newRowEncoder(TypeRef.of(cls));
    String qualified = builder.codecQualifiedClassName(cls);
    if (emittedNames.add(qualified)) {
      result.add(new GeneratedSource(qualified, builder.genCode()));
    }
  }

  private static void addProjectionRowCodecSources(
      Class<?> beanClass,
      CodecEncoding encoding,
      List<GeneratedSource> result,
      Set<String> emittedNames) {
    for (ProjectionVariant variant : ProjectionVariants.forRow(beanClass, encoding)) {
      emit(variant, result, emittedNames);
    }
  }

  private static void addProjectionArrayCodecSources(
      TypeRef<? extends Collection<?>> arrayCls,
      TypeRef<?> elementType,
      CodecEncoding encoding,
      List<GeneratedSource> result,
      Set<String> emitted) {
    Class<?> elementClass = getRawType(elementType);
    for (ProjectionVariant variant :
        ProjectionVariants.forArray(arrayCls, elementType, elementClass, encoding)) {
      emit(variant, result, emitted);
    }
  }

  private static void addProjectionMapCodecSources(
      TypeRef<? extends Map<?, ?>> mapCls,
      TypeRef<?> keyType,
      TypeRef<?> valueType,
      CodecEncoding encoding,
      List<GeneratedSource> result,
      Set<String> emitted) {
    Class<?> valClass = ProjectionVariants.evolutionBean(valueType);
    Class<?> keyClass = ProjectionVariants.evolutionBean(keyType);
    for (ProjectionVariant variant :
        ProjectionVariants.forMap(mapCls, keyType, valueType, valClass, keyClass, encoding)) {
      emit(variant, result, emitted);
    }
  }

  /** Emit one variant's generated source, deduplicated by its generated class name. */
  private static void emit(
      ProjectionVariant variant, List<GeneratedSource> result, Set<String> emitted) {
    String qualified = variant.generatedClassName();
    if (emitted.add(qualified)) {
      result.add(new GeneratedSource(qualified, variant.genCode()));
    }
  }

  /**
   * Refuse to emit sources when the runtime would suffix generated class names with a unique id.
   * The probe in {@link Encoders} that picks up precompiled classes looks for the unsuffixed name;
   * if this JVM observed the unique-id flag as true, the emitted names won't match the runtime
   * probes and the precompile becomes silently dead weight. Mirrors the guard in the
   * {@code @ForyGenerate} annotation processor.
   */
  private static void checkUniqueIdDisabled() {
    if (CodeGenerator.isClassUniqueIdEnabled()) {
      throw new IllegalStateException(
          "Fory generated-class unique-id suffix is enabled in this JVM. Precompiled class "
              + "names would not match the runtime probes that look for unsuffixed names. Do not "
              + "set -Dfory.enable_fory_generated_class_unique_id on the JVM running the "
              + "precompile, or call CodeGenerator.setClassUniqueIdEnabled(false) before "
              + "invoking PrecompileApi.");
    }
  }

  private static CodecEncoding encodingFor(RowFormat format) {
    return format == RowFormat.COMPACT ? CompactCodecFormat.INSTANCE : DefaultCodecFormat.INSTANCE;
  }

  /**
   * Use the same resolution context as the row-format codec builders so a type registered as a
   * custom codec is not misclassified as a bean at build time. Without the registry context, a type
   * with a registered custom codec would emit a row codec at build time but route through the
   * custom-codec path at runtime, defeating the precompile.
   */
  private static boolean isBean(TypeRef<?> type) {
    return TypeUtils.isBean(
        type, new TypeResolutionContext(CustomTypeEncoderRegistry.customTypeHandler(), true));
  }
}
