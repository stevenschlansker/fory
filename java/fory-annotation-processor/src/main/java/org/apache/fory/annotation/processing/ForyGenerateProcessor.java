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

package org.apache.fory.annotation.processing;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.format.annotation.ForyGenerate;
import org.apache.fory.format.annotation.RowFormat;
import org.apache.fory.format.encoder.ArrayEncoder;
import org.apache.fory.format.encoder.MapEncoder;
import org.apache.fory.format.encoder.PrecompileApi;
import org.apache.fory.format.encoder.RowEncoder;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.TypeUtils;

/**
 * Annotation processor for {@code @ForyGenerate}. For each annotated interface, emits:
 *
 * <ul>
 *   <li>Source for each row, array, and map codec class referenced by the interface methods, by
 *       invoking the existing row-codec builders at build time. These are written under stable (no
 *       class-loader hashcode suffix) names so the runtime probes pick them up.
 *   <li>An {@code <Interface>_Fory} implementation of the user's interface, exposed as a singleton
 *       via {@code instance()} and reachable from user code via {@code
 *       RowCodecs.factory(Interface.class)}. Each method body delegates to the corresponding
 *       encoder.
 * </ul>
 *
 * <p>The bean classes referenced from the interface must be loadable from the annotation-processor
 * classpath: typically a dependency jar produced by a prior compilation step. They cannot live in
 * the same compilation unit as the {@code @ForyGenerate} interface, because the processor calls
 * {@link Class#forName} on them to drive the existing reflection-based codec builders.
 */
public final class ForyGenerateProcessor extends AbstractProcessor {

  private static final String UNIQUE_ID_PROPERTY = "fory.enable_fory_generated_class_unique_id";

  private Messager messager;
  private Filer filer;
  private Elements elements;
  private Types types;
  private final Set<String> processedInterfaces = new HashSet<>();
  private final ClassLoader runtimeClassLoader;

  /** Standard processor used by SPI: resolves bean classes via the processor's own classloader. */
  public ForyGenerateProcessor() {
    this(ForyGenerateProcessor.class.getClassLoader());
  }

  /**
   * Constructor used by tests that need to resolve bean classes (and read META-INF descriptors)
   * through a non-default classloader. Production callers should use the no-arg constructor.
   */
  ForyGenerateProcessor(ClassLoader runtimeClassLoader) {
    this.runtimeClassLoader = runtimeClassLoader;
  }

  private Class<?> resolve(String name) {
    try {
      return Class.forName(name, false, runtimeClassLoader);
    } catch (ClassNotFoundException e) {
      return null;
    }
  }

  @Override
  public synchronized void init(ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    this.messager = processingEnv.getMessager();
    this.filer = processingEnv.getFiler();
    this.elements = processingEnv.getElementUtils();
    this.types = processingEnv.getTypeUtils();
    // The unique-id flag must be off before any code in this loader observes it; otherwise the
    // emitted names won't match the runtime probes.
    if (CodeGenerator.isClassUniqueIdEnabled()) {
      throw new IllegalStateException(
          "Fory generated-class unique-id suffix is enabled in this JVM. The @ForyGenerate "
              + "processor cannot reproduce the runtime suffix at build time, so the emitted "
              + "class names would not match the runtime probes. Do not set -D"
              + UNIQUE_ID_PROPERTY
              + " on the javac invocation, or call CodeGenerator.setClassUniqueIdEnabled(false) "
              + "before this processor is initialized.");
    }
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  @Override
  public Set<String> getSupportedAnnotationTypes() {
    LinkedHashSet<String> set = new LinkedHashSet<>();
    set.add(ForyGenerate.class.getName());
    set.add("org.apache.fory.format.annotation.ForyCustomCodec");
    set.add("org.apache.fory.format.annotation.ForyCustomCollection");
    return set;
  }

  /**
   * Accumulated across rounds: codecs and collection factories discovered via class-level
   * annotations. Registrations on {@code CustomTypeEncoderRegistry} are process-static, so they
   * naturally persist across rounds; we keep a parallel list here only because the generated
   * factory's static initializer needs the registration calls echoed out.
   */
  private final List<CustomCodecRegistration> discoveredCodecs = new ArrayList<>();

  private final List<CustomCollectionRegistration> discoveredCollections = new ArrayList<>();
  private final Set<String> seenCodecClasses = new HashSet<>();
  private final Set<String> seenCollectionClasses = new HashSet<>();

  /** Source-level registrations from this build; written to META-INF for downstream modules. */
  private final List<CustomCodecRegistration> localSourceCodecs = new ArrayList<>();

  private final List<CustomCollectionRegistration> localSourceCollections = new ArrayList<>();

  /** True once we have scanned the processor classpath for upstream META-INF descriptors. */
  private boolean classpathDescriptorsScanned = false;

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    // Pull in codec/collection registrations from upstream modules (META-INF descriptors on the
    // processor classpath) once per build; their registration on the build-time registry must
    // happen before any @ForyGenerate factory is processed.
    if (!classpathDescriptorsScanned) {
      classpathDescriptorsScanned = true;
      loadClasspathCodecDescriptors();
      loadClasspathCollectionDescriptors();
    }
    // Source-level codecs/collections in this round register and accumulate.
    discoverCustomCodecs(roundEnv);
    discoverCustomCollections(roundEnv);

    if (roundEnv.processingOver()) {
      writeLocalDescriptors();
    }

    TypeElement annotation = elements.getTypeElement(ForyGenerate.class.getName());
    if (annotation == null) {
      return false;
    }
    java.util.Set<? extends Element> generates = roundEnv.getElementsAnnotatedWith(annotation);
    // Source codecs aren't loadable via Class.forName until they compile, so a @ForyGenerate
    // factory in the same round cannot wire them in. Name the conflicting codecs so the user
    // knows what to move.
    if (!generates.isEmpty()
        && (!localSourceCodecs.isEmpty() || !localSourceCollections.isEmpty())) {
      StringBuilder names = new StringBuilder();
      for (CustomCodecRegistration c : localSourceCodecs) {
        if (names.length() > 0) {
          names.append(", ");
        }
        names.append(c.codecClassName);
      }
      for (CustomCollectionRegistration c : localSourceCollections) {
        if (names.length() > 0) {
          names.append(", ");
        }
        names.append(c.factoryClassName);
      }
      for (Element gen : generates) {
        if (gen instanceof TypeElement) {
          messager.printMessage(
              Diagnostic.Kind.ERROR,
              "@ForyGenerate on "
                  + ((TypeElement) gen).getQualifiedName()
                  + " cannot share a compilation unit with @ForyCustomCodec or "
                  + "@ForyCustomCollection ("
                  + names
                  + "); move the codec classes to a dependency module so they compile first.",
              gen);
        }
      }
      return false;
    }
    for (Element element : generates) {
      if (!(element instanceof TypeElement)) {
        continue;
      }
      TypeElement iface = (TypeElement) element;
      if (iface.getKind() != ElementKind.INTERFACE) {
        messager.printMessage(
            Diagnostic.Kind.ERROR, "@ForyGenerate may only be applied to interfaces", iface);
        continue;
      }
      String qualified = elements.getBinaryName(iface).toString();
      if (!processedInterfaces.add(qualified)) {
        continue;
      }
      try {
        processInterface(iface);
      } catch (InvalidGenerateException e) {
        messager.printMessage(Diagnostic.Kind.ERROR, e.getMessage(), e.element);
      } catch (RuntimeException e) {
        StringBuilder sb = new StringBuilder();
        sb.append("Failed to generate Fory codec factory for ").append(qualified).append(": ");
        sb.append(e.getClass().getName()).append(": ").append(e.getMessage());
        for (StackTraceElement frame : e.getStackTrace()) {
          sb.append("\n  at ").append(frame);
        }
        messager.printMessage(Diagnostic.Kind.ERROR, sb.toString(), iface);
      }
    }
    // Return false so downstream processors can also see @ForyCustomCodec and
    // @ForyCustomCollection. The @ForyGenerate annotation is unique to this processor by
    // convention; no other tool consumes it.
    return false;
  }

  /**
   * Source-level codec discovery. Records the descriptor entry from {@link TypeElement} data only —
   * no class loading, no instantiation. The codec source is being compiled in the current round, so
   * its .class file is not yet available on the processor's classloader. The descriptor we emit
   * lets downstream modules discover and register the codec; same-module @ForyGenerate factories
   * are not supported because the codec is not loadable at this point.
   */
  private void discoverCustomCodecs(RoundEnvironment roundEnv) {
    TypeElement annotation =
        elements.getTypeElement("org.apache.fory.format.annotation.ForyCustomCodec");
    if (annotation == null) {
      return;
    }
    for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
      if (!(element instanceof TypeElement)) {
        continue;
      }
      TypeElement codecElem = (TypeElement) element;
      String qualified = codecElem.getQualifiedName().toString();
      if (!seenCodecClasses.add(qualified)) {
        continue;
      }
      String beanType =
          readAnnotationClassName(codecElem, annotation, "beanType", "java.lang.Object");
      String fieldType = readCodecFieldTypeFromSource(codecElem);
      if (fieldType == null) {
        messager.printMessage(
            Diagnostic.Kind.ERROR,
            "Cannot determine CustomCodec field type for " + qualified,
            codecElem);
        continue;
      }
      localSourceCodecs.add(new CustomCodecRegistration(qualified, beanType, fieldType));
    }
  }

  private void discoverCustomCollections(RoundEnvironment roundEnv) {
    TypeElement annotation =
        elements.getTypeElement("org.apache.fory.format.annotation.ForyCustomCollection");
    if (annotation == null) {
      return;
    }
    for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
      if (!(element instanceof TypeElement)) {
        continue;
      }
      TypeElement factoryElem = (TypeElement) element;
      String qualified = factoryElem.getQualifiedName().toString();
      if (!seenCollectionClasses.add(qualified)) {
        continue;
      }
      String[] elemAndColl = readCollectionTypesFromSource(factoryElem);
      if (elemAndColl == null) {
        messager.printMessage(
            Diagnostic.Kind.ERROR,
            "Cannot determine element/collection types for " + qualified,
            factoryElem);
        continue;
      }
      localSourceCollections.add(
          new CustomCollectionRegistration(qualified, elemAndColl[1], elemAndColl[0]));
    }
  }

  /**
   * Read the field-type (T) parameter from a {@code @ForyCustomCodec}-annotated class's {@code
   * CustomCodec<T, E>} interface. Walks {@link TypeElement#getInterfaces()} so the codec class need
   * not be loaded. Returns the qualified class name or null if not found.
   */
  private String readCodecFieldTypeFromSource(TypeElement codecElem) {
    return readGenericInterfaceArg(codecElem, "org.apache.fory.format.encoder.CustomCodec", 0);
  }

  /**
   * Returns [elementTypeName, collectionTypeName] from a factory's {@code
   * CustomCollectionFactory<E, C>} interface, or null if not found.
   */
  private String[] readCollectionTypesFromSource(TypeElement factoryElem) {
    String e =
        readGenericInterfaceArg(
            factoryElem, "org.apache.fory.format.encoder.CustomCollectionFactory", 0);
    String c =
        readGenericInterfaceArg(
            factoryElem, "org.apache.fory.format.encoder.CustomCollectionFactory", 1);
    if (e == null || c == null) {
      return null;
    }
    return new String[] {e, c};
  }

  /**
   * Walks the class hierarchy of {@code typeElem} for an interface whose erasure matches {@code
   * interfaceName} and returns the qualified name of its {@code typeArgIndex}'th type argument.
   * Erasure-only on the type argument (parameterized type args become their raw class).
   */
  private String readGenericInterfaceArg(
      TypeElement typeElem, String interfaceName, int typeArgIndex) {
    java.util.ArrayDeque<TypeMirror> queue = new java.util.ArrayDeque<>();
    for (TypeMirror i : typeElem.getInterfaces()) {
      queue.add(i);
    }
    if (typeElem.getSuperclass() != null
        && typeElem.getSuperclass().getKind() == TypeKind.DECLARED) {
      queue.add(typeElem.getSuperclass());
    }
    while (!queue.isEmpty()) {
      TypeMirror t = queue.poll();
      if (t.getKind() != TypeKind.DECLARED) {
        continue;
      }
      DeclaredType dt = (DeclaredType) t;
      Element e = dt.asElement();
      if (e instanceof TypeElement) {
        TypeElement te = (TypeElement) e;
        if (te.getQualifiedName().contentEquals(interfaceName)) {
          List<? extends TypeMirror> args = dt.getTypeArguments();
          if (typeArgIndex >= args.size()) {
            return null;
          }
          return erasureName(args.get(typeArgIndex));
        }
        for (TypeMirror i : te.getInterfaces()) {
          queue.add(i);
        }
        TypeMirror sc = te.getSuperclass();
        if (sc != null && sc.getKind() == TypeKind.DECLARED) {
          queue.add(sc);
        }
      }
    }
    return null;
  }

  private String erasureName(TypeMirror type) {
    if (type.getKind() != TypeKind.DECLARED) {
      return type.toString();
    }
    Element e = ((DeclaredType) type).asElement();
    if (e instanceof TypeElement) {
      return ((TypeElement) e).getQualifiedName().toString();
    }
    return type.toString();
  }

  /**
   * Read a class-valued annotation member by name, returning the qualified class name. Returns
   * {@code defaultName} if the member is absent.
   */
  private String readAnnotationClassName(
      TypeElement target, TypeElement annotationType, String memberName, String defaultName) {
    String annotationName = annotationType.getQualifiedName().toString();
    for (AnnotationMirror mirror : target.getAnnotationMirrors()) {
      Element mirrorElem = mirror.getAnnotationType().asElement();
      if (!(mirrorElem instanceof TypeElement)) {
        continue;
      }
      if (!((TypeElement) mirrorElem).getQualifiedName().contentEquals(annotationName)) {
        continue;
      }
      for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
          mirror.getElementValues().entrySet()) {
        if (memberName.equals(entry.getKey().getSimpleName().toString())) {
          Object value = entry.getValue().getValue();
          if (value instanceof TypeMirror) {
            TypeMirror tm = (TypeMirror) value;
            if (tm.getKind() == TypeKind.DECLARED) {
              Element e = ((DeclaredType) tm).asElement();
              if (e instanceof TypeElement) {
                return ((TypeElement) e).getQualifiedName().toString();
              }
            }
          }
        }
      }
    }
    return defaultName;
  }

  // --------------------------------------------------------------------------
  // Cross-module discovery via META-INF/fory/* descriptors. The processor writes the descriptor
  // for codecs and factories that live in source for the current build, so downstream modules
  // can discover them by scanning their classpath. The two file formats are line-oriented:
  //   META-INF/fory/custom-codecs.txt     -> "<codecClassName>\t<beanTypeClassName>"
  //   META-INF/fory/custom-collections.txt -> "<factoryClassName>"
  // Blank lines and lines starting with '#' are ignored on the consumer side.

  private static final String CODEC_DESCRIPTOR = "META-INF/fory/custom-codecs.txt";
  private static final String COLLECTION_DESCRIPTOR = "META-INF/fory/custom-collections.txt";

  private void writeLocalDescriptors() {
    if (!localSourceCodecs.isEmpty()) {
      StringBuilder sb = new StringBuilder();
      sb.append("# Generated by Fory @ForyCustomCodec processor; do not edit.\n");
      for (CustomCodecRegistration r : localSourceCodecs) {
        sb.append(r.codecClassName).append('\t').append(r.beanType).append('\n');
      }
      writeResource(CODEC_DESCRIPTOR, sb.toString());
    }
    if (!localSourceCollections.isEmpty()) {
      StringBuilder sb = new StringBuilder();
      sb.append("# Generated by Fory @ForyCustomCollection processor; do not edit.\n");
      for (CustomCollectionRegistration r : localSourceCollections) {
        sb.append(r.factoryClassName).append('\n');
      }
      writeResource(COLLECTION_DESCRIPTOR, sb.toString());
    }
  }

  private void writeResource(String relativePath, String content) {
    try {
      javax.tools.FileObject f =
          filer.createResource(javax.tools.StandardLocation.CLASS_OUTPUT, "", relativePath);
      try (Writer w = f.openWriter()) {
        w.write(content);
      }
    } catch (IOException e) {
      messager.printMessage(
          Diagnostic.Kind.WARNING,
          "Failed to write " + relativePath + " descriptor: " + e.getMessage());
    }
  }

  private void loadClasspathCodecDescriptors() {
    for (String line : readClasspathDescriptors(CODEC_DESCRIPTOR)) {
      int tab = line.indexOf('\t');
      if (tab < 0) {
        messager.printMessage(
            Diagnostic.Kind.WARNING, "Skipping malformed " + CODEC_DESCRIPTOR + " line: " + line);
        continue;
      }
      String codecClassName = line.substring(0, tab).trim();
      String beanTypeName = line.substring(tab + 1).trim();
      if (!seenCodecClasses.add(codecClassName)) {
        continue;
      }
      Class<?> beanType = resolve(beanTypeName);
      if (beanType == null) {
        messager.printMessage(
            Diagnostic.Kind.WARNING,
            "Skipping " + CODEC_DESCRIPTOR + " entry; cannot load beanType " + beanTypeName);
        continue;
      }
      try {
        CustomCodecRegistration reg = registerCodec(codecClassName, beanType, null);
        discoveredCodecs.add(reg);
      } catch (InvalidGenerateException e) {
        messager.printMessage(
            Diagnostic.Kind.WARNING,
            "Skipping " + CODEC_DESCRIPTOR + " entry " + codecClassName + ": " + e.getMessage());
      }
    }
  }

  private void loadClasspathCollectionDescriptors() {
    for (String line : readClasspathDescriptors(COLLECTION_DESCRIPTOR)) {
      String factoryClassName = line.trim();
      if (factoryClassName.isEmpty() || !seenCollectionClasses.add(factoryClassName)) {
        continue;
      }
      try {
        CustomCollectionRegistration reg = registerCollection(factoryClassName, null);
        discoveredCollections.add(reg);
      } catch (InvalidGenerateException e) {
        messager.printMessage(
            Diagnostic.Kind.WARNING,
            "Skipping "
                + COLLECTION_DESCRIPTOR
                + " entry "
                + factoryClassName
                + ": "
                + e.getMessage());
      }
    }
  }

  private List<String> readClasspathDescriptors(String relativePath) {
    List<String> lines = new ArrayList<>();
    try {
      java.util.Enumeration<java.net.URL> resources = runtimeClassLoader.getResources(relativePath);
      while (resources.hasMoreElements()) {
        java.net.URL url = resources.nextElement();
        try (java.io.InputStream in = url.openStream();
            java.io.BufferedReader reader =
                new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
          String line;
          while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
              continue;
            }
            lines.add(trimmed);
          }
        }
      }
    } catch (IOException e) {
      messager.printMessage(
          Diagnostic.Kind.WARNING,
          "Failed to scan classpath for " + relativePath + ": " + e.getMessage());
    }
    return lines;
  }

  private void processInterface(TypeElement iface) {
    Config config = readConfig(iface);
    PackageElement pkgElement = elements.getPackageOf(iface);
    String packageName = pkgElement.isUnnamed() ? "" : pkgElement.getQualifiedName().toString();
    // Build the factory name from the binary name so a nested interface like pkg.Outer.Codecs
    // generates pkg.Outer_Codecs_Fory rather than colliding with pkg.Other.Codecs's factory at
    // pkg.Codecs_Fory.
    String binary = elements.getBinaryName(iface).toString();
    String simpleWithEnclosing =
        packageName.isEmpty() ? binary : binary.substring(packageName.length() + 1);
    String factoryClassName = simpleWithEnclosing.replace('$', '_') + "_Fory";

    // Codec and collection-factory registrations were discovered earlier in this round (or in a
    // prior round) when their @ForyCustomCodec / @ForyCustomCollection declarations were
    // processed. Echo the registrations into this factory's static initializer so the runtime
    // registry mirrors the build-time one before any encoder is constructed.
    //
    // Sort by codec/factory class name so any two @ForyGenerate factories in this module emit
    // their registrations in the same order; the runtime registry is last-write-wins per key,
    // and identical ordering removes the only avoidable source of non-determinism when more
    // than one factory loads at runtime.
    List<CustomCodecRegistration> codecRegs = new ArrayList<>(discoveredCodecs);
    codecRegs.sort((a, b) -> a.codecClassName.compareTo(b.codecClassName));
    List<CustomCollectionRegistration> collRegs = new ArrayList<>(discoveredCollections);
    collRegs.sort((a, b) -> a.factoryClassName.compareTo(b.factoryClassName));

    List<InterfaceMethod> bindings = new ArrayList<>();
    Set<CodecKey> codecKeys = new LinkedHashSet<>();
    // Each method on the interface produces exactly one binding. Methods inherited from Object
    // (toString/hashCode/equals) are silently skipped — we re-implement them as defaults later.
    for (Element member : iface.getEnclosedElements()) {
      if (member.getKind() != ElementKind.METHOD) {
        continue;
      }
      ExecutableElement method = (ExecutableElement) member;
      if (method.getModifiers().contains(Modifier.DEFAULT)
          || method.getModifiers().contains(Modifier.STATIC)) {
        continue;
      }
      InterfaceMethod binding = classifyMethod(method);
      bindings.add(binding);
      codecKeys.add(binding.codecKey);
    }

    Map<CodecKey, String> encoderFieldNames = new LinkedHashMap<>();
    int idx = 0;
    for (CodecKey key : codecKeys) {
      encoderFieldNames.put(key, "encoder" + idx);
      idx++;
    }

    Set<String> writtenClassNames = new HashSet<>();
    for (CodecKey key : codecKeys) {
      emitCodecSource(key, config, iface, writtenClassNames);
    }
    reportCrossProductSize(iface, writtenClassNames.size());

    emitFactoryImpl(
        iface,
        packageName,
        factoryClassName,
        config,
        bindings,
        encoderFieldNames,
        codecRegs,
        collRegs);
  }

  /**
   * Instantiate one {@code @ForyCustomCodec}-annotated class, register it on the build-time
   * registry, and capture the runtime registration tuple to be echoed into each generated factory's
   * static initializer. {@code beanType} is read from the annotation (defaults to {@code
   * Object.class}).
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private CustomCodecRegistration registerCodec(
      String codecClassName, Class<?> beanType, TypeElement codecElem) {
    Class<?> codecClass = loadClass(codecClassName, codecElem);
    if (!org.apache.fory.format.encoder.CustomCodec.class.isAssignableFrom(codecClass)) {
      throw new InvalidGenerateException(
          codecClassName + " is annotated @ForyCustomCodec but does not implement CustomCodec",
          codecElem);
    }
    org.apache.fory.reflect.TypeRef<?> codecRef = org.apache.fory.reflect.TypeRef.of(codecClass);
    List<org.apache.fory.reflect.TypeRef<?>> args =
        codecRef
            .getSupertype((Class) org.apache.fory.format.encoder.CustomCodec.class)
            .getTypeArguments();
    if (args.size() != 2) {
      throw new InvalidGenerateException(
          codecClassName + " must close over both type parameters of CustomCodec<T,E>", codecElem);
    }
    Class<?> fieldType = args.get(0).getRawType();
    Class<?> scope = beanType != null ? beanType : Object.class;
    Object codecInstance = newInstanceOrFail(codecClass, codecElem);
    org.apache.fory.format.encoder.CustomCodec<Object, ?> ccast =
        (org.apache.fory.format.encoder.CustomCodec) codecInstance;
    org.apache.fory.format.encoder.Encoders.registerCustomCodec(scope, (Class) fieldType, ccast);
    return new CustomCodecRegistration(codecClassName, scope.getName(), fieldType.getName());
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private CustomCollectionRegistration registerCollection(
      String factoryClassName, TypeElement factoryElem) {
    Class<?> factoryClass = loadClass(factoryClassName, factoryElem);
    if (!org.apache.fory.format.encoder.CustomCollectionFactory.class.isAssignableFrom(
        factoryClass)) {
      throw new InvalidGenerateException(
          factoryClassName
              + " is annotated @ForyCustomCollection but does not implement CustomCollectionFactory",
          factoryElem);
    }
    org.apache.fory.reflect.TypeRef<?> factoryRef =
        org.apache.fory.reflect.TypeRef.of(factoryClass);
    List<org.apache.fory.reflect.TypeRef<?>> args =
        factoryRef
            .getSupertype((Class) org.apache.fory.format.encoder.CustomCollectionFactory.class)
            .getTypeArguments();
    if (args.size() != 2) {
      throw new InvalidGenerateException(
          factoryClassName
              + " must close over both type parameters of CustomCollectionFactory<E,C>",
          factoryElem);
    }
    Class<?> elementType = args.get(0).getRawType();
    Class<?> collectionType = args.get(1).getRawType();
    Object factoryInstance = newInstanceOrFail(factoryClass, factoryElem);
    org.apache.fory.format.encoder.CustomCollectionFactory<Object, java.util.Collection<Object>>
        fcast = (org.apache.fory.format.encoder.CustomCollectionFactory) factoryInstance;
    org.apache.fory.format.encoder.Encoders.registerCustomCollectionFactory(
        (Class) collectionType, (Class) elementType, fcast);
    return new CustomCollectionRegistration(
        factoryClassName, collectionType.getName(), elementType.getName());
  }

  private Object newInstanceOrFail(Class<?> cls, TypeElement iface) {
    try {
      return cls.getDeclaredConstructor().newInstance();
    } catch (ReflectiveOperationException e) {
      throw new InvalidGenerateException(
          cls.getName()
              + " must have a public no-arg constructor to be used in @ForyGenerate. ("
              + e.getClass().getSimpleName()
              + ": "
              + e.getMessage()
              + ")",
          iface);
    }
  }

  private static final class CustomCodecRegistration {
    final String codecClassName;
    final String beanType;
    final String fieldType;

    CustomCodecRegistration(String codecClassName, String beanType, String fieldType) {
      this.codecClassName = codecClassName;
      this.beanType = beanType;
      this.fieldType = fieldType;
    }
  }

  private static final class CustomCollectionRegistration {
    final String factoryClassName;
    final String collectionType;
    final String elementType;

    CustomCollectionRegistration(
        String factoryClassName, String collectionType, String elementType) {
      this.factoryClassName = factoryClassName;
      this.collectionType = collectionType;
      this.elementType = elementType;
    }
  }

  private Config readConfig(TypeElement iface) {
    RowFormat format = RowFormat.DEFAULT;
    boolean evolution = false;
    for (AnnotationMirror mirror : iface.getAnnotationMirrors()) {
      if (!isForyGenerateAnnotation(mirror)) {
        continue;
      }
      for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
          mirror.getElementValues().entrySet()) {
        String name = entry.getKey().getSimpleName().toString();
        Object value = entry.getValue().getValue();
        if ("format".equals(name)) {
          if (value != null && "COMPACT".equals(value.toString())) {
            format = RowFormat.COMPACT;
          }
        } else if ("evolution".equals(name)) {
          if (value instanceof Boolean) {
            evolution = (Boolean) value;
          }
        }
      }
    }
    return new Config(format, evolution);
  }

  private static final class Config {
    final RowFormat format;
    final boolean evolution;

    Config(RowFormat format, boolean evolution) {
      this.format = format;
      this.evolution = evolution;
    }
  }

  private boolean isForyGenerateAnnotation(AnnotationMirror mirror) {
    Element annoElement = mirror.getAnnotationType().asElement();
    return annoElement instanceof TypeElement
        && ((TypeElement) annoElement)
            .getQualifiedName()
            .contentEquals(ForyGenerate.class.getName());
  }

  // ---------------------------------------------------------------------------
  // Method classification

  /**
   * Classify a method on a {@code @ForyGenerate} interface by its signature. The classification
   * looks only at parameter and return types — method names are never parsed.
   */
  private InterfaceMethod classifyMethod(ExecutableElement method) {
    List<? extends VariableElement> params = method.getParameters();
    TypeMirror returnType = method.getReturnType();

    // Zero-arg encoder getters: returns RowEncoder<T> / ArrayEncoder<List<T>> /
    // MapEncoder<Map<K,V>>.
    if (params.isEmpty()) {
      if (isErasureOf(returnType, RowEncoder.class)) {
        TypeMirror bean = singleTypeArg(returnType, method);
        return InterfaceMethod.encoderGetter(method, CodecKey.row(bean));
      }
      if (isErasureOf(returnType, ArrayEncoder.class)) {
        TypeMirror collectionType = singleTypeArg(returnType, method);
        return InterfaceMethod.encoderGetter(
            method, CodecKey.array(collectionType, elementOfCollection(collectionType, method)));
      }
      if (isErasureOf(returnType, MapEncoder.class)) {
        TypeMirror mapType = singleTypeArg(returnType, method);
        TypeMirror[] kv = mapKeyValue(mapType, method);
        return InterfaceMethod.encoderGetter(method, CodecKey.map(mapType, kv[0], kv[1]));
      }
      throw new InvalidGenerateException(
          "Zero-arg method must return RowEncoder<T>, ArrayEncoder<C<T>>, or MapEncoder<M<K,V>>; "
              + "found "
              + returnType,
          method);
    }

    if (params.size() == 1) {
      VariableElement p0 = params.get(0);
      TypeMirror p0Type = p0.asType();
      // byte[] encode(T)
      if (isReturnByteArray(returnType)) {
        TypeMirror bean = beanFromTypeArg(p0Type, method);
        return InterfaceMethod.encodeBytes(method, CodecKey.row(bean));
      }
      // T decode(byte[])
      if (isByteArray(p0Type)) {
        return InterfaceMethod.decodeBytes(method, CodecKey.row(returnType));
      }
      // List<T> readList(MemoryBuffer) / Map<K,V> readMap(MemoryBuffer) / T readBean(MemoryBuffer)
      if (isMemoryBuffer(p0Type)) {
        if (isCollection(returnType)) {
          TypeMirror element = elementOfCollection(returnType, method);
          return InterfaceMethod.decodeBuffer(method, CodecKey.array(returnType, element));
        }
        if (isMap(returnType)) {
          TypeMirror[] kv = mapKeyValue(returnType, method);
          return InterfaceMethod.decodeBuffer(method, CodecKey.map(returnType, kv[0], kv[1]));
        }
        // Otherwise: T decode(MemoryBuffer)
        return InterfaceMethod.decodeBuffer(method, CodecKey.row(returnType));
      }
      throw new InvalidGenerateException(
          "Single-arg method must return byte[]/T or take byte[]/MemoryBuffer; found ("
              + p0Type
              + ") -> "
              + returnType,
          method);
    }

    if (params.size() == 2) {
      VariableElement p0 = params.get(0);
      VariableElement p1 = params.get(1);
      if (!isMemoryBuffer(p0.asType())) {
        throw new InvalidGenerateException(
            "Two-arg method must take (MemoryBuffer, T) or (MemoryBuffer, List<T>) or "
                + "(MemoryBuffer, Map<K,V>); first arg was "
                + p0.asType(),
            method);
      }
      if (returnType.getKind() != TypeKind.VOID && returnType.getKind() != TypeKind.INT) {
        throw new InvalidGenerateException(
            "Two-arg buffer methods must return void or int; found " + returnType, method);
      }
      TypeMirror payload = p1.asType();
      if (isCollection(payload)) {
        TypeMirror element = elementOfCollection(payload, method);
        return InterfaceMethod.encodeBuffer(method, CodecKey.array(payload, element));
      }
      if (isMap(payload)) {
        TypeMirror[] kv = mapKeyValue(payload, method);
        return InterfaceMethod.encodeBuffer(method, CodecKey.map(payload, kv[0], kv[1]));
      }
      return InterfaceMethod.encodeBuffer(method, CodecKey.row(payload));
    }

    throw new InvalidGenerateException(
        "@ForyGenerate methods must have 0, 1, or 2 parameters; found "
            + params.size()
            + " on "
            + method.getSimpleName(),
        method);
  }

  // ---------------------------------------------------------------------------
  // TypeMirror helpers

  private boolean isErasureOf(TypeMirror type, Class<?> raw) {
    TypeElement rawElem = elements.getTypeElement(raw.getName());
    if (rawElem == null) {
      return false;
    }
    return types.isSameType(types.erasure(type), types.erasure(rawElem.asType()));
  }

  private boolean isReturnByteArray(TypeMirror returnType) {
    return isByteArray(returnType);
  }

  private boolean isByteArray(TypeMirror type) {
    if (type.getKind() != TypeKind.ARRAY) {
      return false;
    }
    TypeMirror component = ((javax.lang.model.type.ArrayType) type).getComponentType();
    return component.getKind() == TypeKind.BYTE;
  }

  private boolean isMemoryBuffer(TypeMirror type) {
    if (type.getKind() != TypeKind.DECLARED) {
      return false;
    }
    Element e = ((DeclaredType) type).asElement();
    return e instanceof TypeElement
        && ((TypeElement) e)
            .getQualifiedName()
            .contentEquals("org.apache.fory.memory.MemoryBuffer");
  }

  private boolean isCollection(TypeMirror type) {
    if (type.getKind() != TypeKind.DECLARED) {
      return false;
    }
    TypeElement collectionElem = elements.getTypeElement("java.util.Collection");
    if (collectionElem == null) {
      return false;
    }
    TypeMirror rawCollection = types.erasure(collectionElem.asType());
    return types.isAssignable(types.erasure(type), rawCollection);
  }

  private boolean isMap(TypeMirror type) {
    if (type.getKind() != TypeKind.DECLARED) {
      return false;
    }
    TypeElement mapElem = elements.getTypeElement("java.util.Map");
    if (mapElem == null) {
      return false;
    }
    return types.isAssignable(types.erasure(type), types.erasure(mapElem.asType()));
  }

  private TypeMirror singleTypeArg(TypeMirror type, ExecutableElement method) {
    if (type.getKind() != TypeKind.DECLARED) {
      throw new InvalidGenerateException("Expected a parameterized type, got " + type, method);
    }
    List<? extends TypeMirror> args = ((DeclaredType) type).getTypeArguments();
    if (args.size() != 1) {
      throw new InvalidGenerateException(
          "Expected exactly one type argument on " + type + " for " + method.getSimpleName(),
          method);
    }
    return args.get(0);
  }

  private TypeMirror elementOfCollection(TypeMirror collectionType, ExecutableElement method) {
    if (collectionType.getKind() != TypeKind.DECLARED) {
      throw new InvalidGenerateException(
          "Cannot infer collection element type from " + collectionType, method);
    }
    List<? extends TypeMirror> args = ((DeclaredType) collectionType).getTypeArguments();
    if (args.size() != 1) {
      throw new InvalidGenerateException(
          "Collection-typed method must use a single-type-arg form like List<T>; found "
              + collectionType,
          method);
    }
    return args.get(0);
  }

  private TypeMirror[] mapKeyValue(TypeMirror mapType, ExecutableElement method) {
    if (mapType.getKind() != TypeKind.DECLARED) {
      throw new InvalidGenerateException(
          "Cannot infer map key/value types from " + mapType, method);
    }
    List<? extends TypeMirror> args = ((DeclaredType) mapType).getTypeArguments();
    if (args.size() != 2) {
      throw new InvalidGenerateException(
          "Map-typed method must use a two-type-arg form like Map<K,V>; found " + mapType, method);
    }
    return new TypeMirror[] {args.get(0), args.get(1)};
  }

  private TypeMirror beanFromTypeArg(TypeMirror declared, ExecutableElement method) {
    if (declared.getKind() == TypeKind.DECLARED) {
      return declared;
    }
    throw new InvalidGenerateException(
        "Bean-typed parameter must be a declared class; found " + declared, method);
  }

  // ---------------------------------------------------------------------------
  // Codec source emission

  private void emitCodecSource(
      CodecKey key, Config config, TypeElement iface, Set<String> written) {
    Class<?> beanClass = loadClass(key.beanQualifiedName(), iface);
    switch (key.kind) {
      case ROW:
        emitRowCodecSources(beanClass, config, iface, written);
        break;
      case ARRAY:
        Class<? extends java.util.Collection<?>> arrayClass = loadCollectionClass(key, iface);
        Class<?> elementClass = loadClass(key.elementQualifiedName(), iface);
        emitArrayCodecSources(arrayClass, elementClass, config, iface, written);
        break;
      case MAP:
        Class<? extends java.util.Map<?, ?>> mapClass = loadMapClass(key, iface);
        Class<?> keyClass = loadClass(key.elementQualifiedName(), iface);
        Class<?> valueClass = loadClass(key.valueQualifiedName(), iface);
        emitMapCodecSources(mapClass, keyClass, valueClass, config, iface, written);
        break;
      default:
        throw new IllegalStateException(key.kind.toString());
    }
  }

  private void emitRowCodecSources(
      Class<?> beanClass, Config config, TypeElement iface, Set<String> written) {
    for (PrecompileApi.GeneratedSource gs :
        PrecompileApi.precompileRowCodecs(beanClass, config.format, config.evolution)) {
      if (written.add(gs.qualifiedClassName())) {
        writeJavaSource(gs.qualifiedClassName(), gs.source(), iface);
      }
    }
  }

  private void emitArrayCodecSources(
      Class<? extends java.util.Collection<?>> arrayClass,
      Class<?> elementClass,
      Config config,
      TypeElement iface,
      Set<String> written) {
    @SuppressWarnings({"rawtypes", "unchecked"})
    TypeRef<? extends java.util.Collection<?>> parameterized =
        (TypeRef) TypeUtils.collectionOf(arrayClass, TypeRef.of(elementClass), null);
    for (PrecompileApi.GeneratedSource gs :
        PrecompileApi.precompileArrayCodec(
            parameterized, TypeRef.of(elementClass), config.format, config.evolution)) {
      if (written.add(gs.qualifiedClassName())) {
        writeJavaSource(gs.qualifiedClassName(), gs.source(), iface);
      }
    }
  }

  private void emitMapCodecSources(
      Class<? extends java.util.Map<?, ?>> mapClass,
      Class<?> keyClass,
      Class<?> valueClass,
      Config config,
      TypeElement iface,
      Set<String> written) {
    @SuppressWarnings({"rawtypes", "unchecked"})
    TypeRef<? extends java.util.Map<?, ?>> parameterized =
        (TypeRef) TypeUtils.mapOf(mapClass, keyClass, valueClass);
    for (PrecompileApi.GeneratedSource gs :
        PrecompileApi.precompileMapCodec(
            parameterized,
            TypeRef.of(keyClass),
            TypeRef.of(valueClass),
            config.format,
            config.evolution)) {
      if (written.add(gs.qualifiedClassName())) {
        writeJavaSource(gs.qualifiedClassName(), gs.source(), iface);
      }
    }
  }

  private void writeJavaSource(String qualifiedName, String source, TypeElement originating) {
    try {
      JavaFileObject file = filer.createSourceFile(qualifiedName, originating);
      try (Writer w = file.openWriter()) {
        w.write(source);
      }
    } catch (IOException e) {
      throw new RuntimeException("Failed to write source for " + qualifiedName, e);
    }
  }

  /** Surface the cross-product class count so the build log makes the cost visible. */
  private static final int CROSS_PRODUCT_NOTE_THRESHOLD = 50;

  private static final int CROSS_PRODUCT_WARN_THRESHOLD = 200;

  private void reportCrossProductSize(TypeElement iface, int classCount) {
    if (classCount < CROSS_PRODUCT_NOTE_THRESHOLD) {
      return;
    }
    Diagnostic.Kind kind =
        classCount >= CROSS_PRODUCT_WARN_THRESHOLD ? Diagnostic.Kind.WARNING : Diagnostic.Kind.NOTE;
    messager.printMessage(
        kind,
        "@ForyGenerate on "
            + iface.getQualifiedName()
            + " produced "
            + classCount
            + " codec classes (product of per-bean version counts); retire unused @ForyVersion "
            + "history entries to shrink the cross product.",
        iface);
  }

  // ---------------------------------------------------------------------------
  // Factory implementation emission

  private void emitFactoryImpl(
      TypeElement iface,
      String packageName,
      String factoryClassName,
      Config config,
      List<InterfaceMethod> bindings,
      Map<CodecKey, String> encoderFieldNames,
      List<CustomCodecRegistration> codecRegs,
      List<CustomCollectionRegistration> collRegs) {
    String qualified =
        packageName.isEmpty() ? factoryClassName : packageName + "." + factoryClassName;
    StringBuilder sb = new StringBuilder();
    if (!packageName.isEmpty()) {
      sb.append("package ").append(packageName).append(";\n\n");
    }
    // Generated code refers to the runtime helpers (GeneratedRowCodecs) and, on the evolution
    // path, Encoders by fully-qualified name, so no encoder import is needed for them. Importing
    // Encoders unconditionally would force the code-generation module onto a runtime-only
    // consumer's classpath, defeating the split; the encoder-interface imports below are all that
    // the method signatures require.
    sb.append("import org.apache.fory.format.encoder.ArrayEncoder;\n");
    sb.append("import org.apache.fory.format.encoder.MapEncoder;\n");
    sb.append("import org.apache.fory.format.encoder.RowEncoder;\n");
    sb.append("import org.apache.fory.memory.MemoryBuffer;\n");
    sb.append("import org.apache.fory.reflect.TypeRef;\n\n");
    sb.append("/** Generated by @ForyGenerate; do not edit. */\n");
    sb.append("public final class ")
        .append(factoryClassName)
        .append(" implements ")
        .append(iface.getQualifiedName())
        .append(" {\n");
    // Static block must precede the INSTANCE field initializer so registrations run before any
    // encoder construction; Java guarantees source order for static initializers.
    if (!codecRegs.isEmpty() || !collRegs.isEmpty()) {
      sb.append("  static {\n");
      for (CustomCodecRegistration r : codecRegs) {
        // Use the 3-arg overload so @ForyCustomCodec(beanType=...) scope is preserved at runtime.
        sb.append("    org.apache.fory.format.encoder.GeneratedRowCodecs.registerCustomCodec(")
            .append(r.beanType)
            .append(".class, ")
            .append(r.fieldType)
            .append(".class, new ")
            .append(r.codecClassName)
            .append("());\n");
      }
      for (CustomCollectionRegistration r : collRegs) {
        sb.append(
                "    org.apache.fory.format.encoder.GeneratedRowCodecs."
                    + "registerCustomCollectionFactory(")
            .append(r.collectionType)
            .append(".class, ")
            .append(r.elementType)
            .append(".class, new ")
            .append(r.factoryClassName)
            .append("());\n");
      }
      sb.append("  }\n\n");
    }
    sb.append("  private static final ")
        .append(factoryClassName)
        .append(" INSTANCE = new ")
        .append(factoryClassName)
        .append("();\n\n");
    sb.append("  public static ")
        .append(iface.getQualifiedName())
        .append(" instance() { return INSTANCE; }\n\n");

    for (Map.Entry<CodecKey, String> entry : encoderFieldNames.entrySet()) {
      CodecKey key = entry.getKey();
      String fieldName = entry.getValue();
      sb.append("  private final ").append(encoderFieldType(key)).append(" ").append(fieldName);
      sb.append(" = ").append(encoderFactoryCall(key, config)).append(";\n");
    }
    sb.append("\n");

    sb.append("  private ").append(factoryClassName).append("() {}\n\n");

    for (InterfaceMethod b : bindings) {
      String fieldName = encoderFieldNames.get(b.codecKey);
      sb.append("  @Override\n  public ");
      sb.append(b.method.getReturnType().toString()).append(" ");
      sb.append(b.method.getSimpleName()).append("(");
      List<? extends VariableElement> params = b.method.getParameters();
      for (int i = 0; i < params.size(); i++) {
        if (i > 0) {
          sb.append(", ");
        }
        sb.append(params.get(i).asType().toString()).append(" ");
        sb.append(params.get(i).getSimpleName());
      }
      sb.append(") {\n    ");
      sb.append(methodBody(b, fieldName));
      sb.append("\n  }\n\n");
    }

    sb.append("}\n");

    writeJavaSource(qualified, sb.toString(), iface);
  }

  private String encoderFieldType(CodecKey key) {
    switch (key.kind) {
      case ROW:
        return "RowEncoder<" + key.beanType + ">";
      case ARRAY:
        return "ArrayEncoder<" + key.beanType + ">";
      case MAP:
        return "MapEncoder<" + key.beanType + ">";
      default:
        throw new IllegalStateException(key.kind.toString());
    }
  }

  private String encoderFactoryCall(CodecKey key, Config config) {
    // Schema evolution still constructs through the code-generation builders (Encoders): the
    // runtime projection-dispatch assembly is not yet emitted standalone, so an evolution-enabled
    // factory needs fory-format-codegen at runtime. Non-evolution factories construct through
    // GeneratedRowCodecs, which resolves the precompiled codec classes with no code-generation
    // dependency, so they run with only fory-format-runtime on the classpath.
    if (config.evolution) {
      return evolutionFactoryCall(key, config);
    }
    String formatArg = "org.apache.fory.format.annotation.RowFormat." + config.format.name();
    switch (key.kind) {
      case ROW:
        return "org.apache.fory.format.encoder.GeneratedRowCodecs.rowEncoder("
            + erasure(key.beanType)
            + ".class, "
            + formatArg
            + ", null)";
      case ARRAY:
        return "org.apache.fory.format.encoder.GeneratedRowCodecs.arrayEncoder(new TypeRef<"
            + key.beanType
            + ">() {}, "
            + formatArg
            + ", null)";
      case MAP:
        return "org.apache.fory.format.encoder.GeneratedRowCodecs.mapEncoder(new TypeRef<"
            + key.beanType
            + ">() {}, "
            + formatArg
            + ", null)";
      default:
        throw new IllegalStateException(key.kind.toString());
    }
  }

  private String evolutionFactoryCall(CodecKey key, Config config) {
    // Use Encoders.buildXxxCodec()...build().get() everywhere so the generated source does not
    // get caught by overload resolution on (Class, null) — Encoders.bean has both Fory and
    // BinaryRowWriter overloads.
    StringBuilder sb;
    switch (key.kind) {
      case ROW:
        sb =
            new StringBuilder("org.apache.fory.format.encoder.Encoders.buildBeanCodec(")
                .append(erasure(key.beanType))
                .append(".class)");
        break;
      case ARRAY:
        sb =
            new StringBuilder(
                    "org.apache.fory.format.encoder.Encoders.buildArrayCodec(new TypeRef<")
                .append(key.beanType)
                .append(">() {})");
        break;
      case MAP:
        sb =
            new StringBuilder("org.apache.fory.format.encoder.Encoders.buildMapCodec(new TypeRef<")
                .append(key.beanType)
                .append(">() {})");
        break;
      default:
        throw new IllegalStateException(key.kind.toString());
    }
    if (config.format == RowFormat.COMPACT) {
      sb.append(".compactEncoding()");
    }
    sb.append(".withSchemaEvolution()");
    return sb.append(".build().get()").toString();
  }

  private String erasure(String typeName) {
    int lt = typeName.indexOf('<');
    return lt < 0 ? typeName : typeName.substring(0, lt);
  }

  private String methodBody(InterfaceMethod b, String fieldName) {
    switch (b.shape) {
      case ENCODER_GETTER:
        return "return " + fieldName + ";";
      case ENCODE_BYTES:
        return "return "
            + fieldName
            + ".encode("
            + b.method.getParameters().get(0).getSimpleName()
            + ");";
      case DECODE_BYTES:
        return "return "
            + fieldName
            + ".decode("
            + b.method.getParameters().get(0).getSimpleName()
            + ");";
      case ENCODE_BUFFER:
        {
          String buf = b.method.getParameters().get(0).getSimpleName().toString();
          String payload = b.method.getParameters().get(1).getSimpleName().toString();
          TypeMirror ret = b.method.getReturnType();
          if (ret.getKind() == TypeKind.INT) {
            return "return " + fieldName + ".encode(" + buf + ", " + payload + ");";
          }
          return fieldName + ".encode(" + buf + ", " + payload + ");";
        }
      case DECODE_BUFFER:
        return "return "
            + fieldName
            + ".decode("
            + b.method.getParameters().get(0).getSimpleName()
            + ");";
      default:
        throw new IllegalStateException(b.shape.toString());
    }
  }

  // ---------------------------------------------------------------------------
  // Class loading

  private Class<?> loadClass(String qualifiedName, TypeElement iface) {
    Class<?> cls = resolve(qualifiedName);
    if (cls != null) {
      return cls;
    }
    throw new InvalidGenerateException(
        "@ForyGenerate requires "
            + qualifiedName
            + " to be on the annotation-processor classpath; put the bean classes in a "
            + "dependency module so they are compiled before this processor runs.",
        iface);
  }

  @SuppressWarnings("unchecked")
  private Class<? extends java.util.Collection<?>> loadCollectionClass(
      CodecKey key, TypeElement iface) {
    Class<?> raw = loadClass(key.collectionRawName(), iface);
    if (!java.util.Collection.class.isAssignableFrom(raw)) {
      throw new InvalidGenerateException(
          "Collection-typed method uses " + raw + " which is not a Collection", iface);
    }
    return (Class<? extends java.util.Collection<?>>) raw;
  }

  @SuppressWarnings("unchecked")
  private Class<? extends java.util.Map<?, ?>> loadMapClass(CodecKey key, TypeElement iface) {
    Class<?> raw = loadClass(key.collectionRawName(), iface);
    if (!java.util.Map.class.isAssignableFrom(raw)) {
      throw new InvalidGenerateException(
          "Map-typed method uses " + raw + " which is not a Map", iface);
    }
    return (Class<? extends java.util.Map<?, ?>>) raw;
  }

  // ---------------------------------------------------------------------------
  // Data

  private enum CodecKind {
    ROW,
    ARRAY,
    MAP
  }

  private enum Shape {
    ENCODER_GETTER,
    ENCODE_BYTES,
    DECODE_BYTES,
    ENCODE_BUFFER,
    DECODE_BUFFER
  }

  private static final class CodecKey {
    final CodecKind kind;
    final String beanType; // verbatim user-visible type, used in encoder field types
    final String rawName; // raw class name to load via Class.forName for the row bean
    final String elementName; // for array: element class; for map: key class
    final String valueName; // for map: value class
    private final String structuralKey; // for equality: fully-qualified structural form

    private CodecKey(
        CodecKind kind,
        String beanType,
        String rawName,
        String elementName,
        String valueName,
        String structuralKey) {
      this.kind = kind;
      this.beanType = beanType;
      this.rawName = rawName;
      this.elementName = elementName;
      this.valueName = valueName;
      this.structuralKey = structuralKey;
    }

    static CodecKey row(TypeMirror beanType) {
      return new CodecKey(
          CodecKind.ROW,
          beanType.toString(),
          eraseToName(beanType),
          null,
          null,
          structuralName(beanType));
    }

    static CodecKey array(TypeMirror collectionType, TypeMirror element) {
      return new CodecKey(
          CodecKind.ARRAY,
          collectionType.toString(),
          eraseToName(collectionType),
          eraseToName(element),
          null,
          structuralName(collectionType));
    }

    static CodecKey map(TypeMirror mapType, TypeMirror k, TypeMirror v) {
      return new CodecKey(
          CodecKind.MAP,
          mapType.toString(),
          eraseToName(mapType),
          eraseToName(k),
          eraseToName(v),
          structuralName(mapType));
    }

    String beanQualifiedName() {
      return rawName;
    }

    String collectionRawName() {
      return rawName;
    }

    String elementQualifiedName() {
      return elementName;
    }

    String valueQualifiedName() {
      return valueName;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof CodecKey)) {
        return false;
      }
      CodecKey k = (CodecKey) o;
      return kind == k.kind && structuralKey.equals(k.structuralKey);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(kind, structuralKey);
    }
  }

  private static String eraseToName(TypeMirror type) {
    if (type.getKind() == TypeKind.DECLARED) {
      Element e = ((DeclaredType) type).asElement();
      if (e instanceof TypeElement) {
        return ((TypeElement) e).getQualifiedName().toString();
      }
    }
    return type.toString();
  }

  /**
   * Walk a TypeMirror to produce a deterministic fully-qualified structural form. Different
   * import-scope renderings of the same logical type ({@code List<MyBean>} vs {@code
   * java.util.List<test.MyBean>}) collapse to the same string, so two methods that close over the
   * same logical codec share an encoder field.
   */
  private static String structuralName(TypeMirror type) {
    StringBuilder sb = new StringBuilder();
    appendStructural(type, sb);
    return sb.toString();
  }

  private static void appendStructural(TypeMirror type, StringBuilder sb) {
    if (type.getKind() == TypeKind.DECLARED) {
      DeclaredType dt = (DeclaredType) type;
      Element e = dt.asElement();
      if (e instanceof TypeElement) {
        sb.append(((TypeElement) e).getQualifiedName());
      } else {
        sb.append(type);
      }
      List<? extends TypeMirror> args = dt.getTypeArguments();
      if (!args.isEmpty()) {
        sb.append('<');
        for (int i = 0; i < args.size(); i++) {
          if (i > 0) {
            sb.append(',');
          }
          appendStructural(args.get(i), sb);
        }
        sb.append('>');
      }
    } else if (type.getKind() == TypeKind.ARRAY) {
      appendStructural(((javax.lang.model.type.ArrayType) type).getComponentType(), sb);
      sb.append("[]");
    } else {
      sb.append(type);
    }
  }

  private static final class InterfaceMethod {
    final ExecutableElement method;
    final CodecKey codecKey;
    final Shape shape;

    private InterfaceMethod(ExecutableElement method, CodecKey codecKey, Shape shape) {
      this.method = method;
      this.codecKey = codecKey;
      this.shape = shape;
    }

    static InterfaceMethod encoderGetter(ExecutableElement method, CodecKey key) {
      return new InterfaceMethod(method, key, Shape.ENCODER_GETTER);
    }

    static InterfaceMethod encodeBytes(ExecutableElement method, CodecKey key) {
      return new InterfaceMethod(method, key, Shape.ENCODE_BYTES);
    }

    static InterfaceMethod decodeBytes(ExecutableElement method, CodecKey key) {
      return new InterfaceMethod(method, key, Shape.DECODE_BYTES);
    }

    static InterfaceMethod encodeBuffer(ExecutableElement method, CodecKey key) {
      return new InterfaceMethod(method, key, Shape.ENCODE_BUFFER);
    }

    static InterfaceMethod decodeBuffer(ExecutableElement method, CodecKey key) {
      return new InterfaceMethod(method, key, Shape.DECODE_BUFFER);
    }
  }

  private static final class InvalidGenerateException extends RuntimeException {
    final Element element;

    InvalidGenerateException(String message, Element element) {
      super(message);
      this.element = element;
    }
  }
}
