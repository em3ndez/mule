/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.privileged.metadata;

import static com.github.benmanes.caffeine.cache.Caffeine.newBuilder;
import static com.google.common.collect.Lists.newArrayList;
import static java.util.Objects.requireNonNull;
import static org.mule.runtime.api.metadata.DataType.OBJECT;
import static org.mule.runtime.core.api.util.StringUtils.isEmpty;
import static org.mule.runtime.core.internal.util.generics.GenericsUtils.getCollectionType;
import static org.mule.runtime.core.internal.util.generics.GenericsUtils.getMapKeyType;
import static org.mule.runtime.core.internal.util.generics.GenericsUtils.getMapValueType;

import org.mule.runtime.api.el.ExpressionFunction;
import org.mule.runtime.api.message.Message;
import org.mule.runtime.api.metadata.CollectionDataType;
import org.mule.runtime.api.metadata.DataType;
import org.mule.runtime.api.metadata.DataTypeBuilder;
import org.mule.runtime.api.metadata.DataTypeParamsBuilder;
import org.mule.runtime.api.metadata.FunctionDataType;
import org.mule.runtime.api.metadata.FunctionParameter;
import org.mule.runtime.api.metadata.MapDataType;
import org.mule.runtime.api.metadata.MediaType;
import org.mule.runtime.core.api.message.OutputHandler;
import org.mule.runtime.core.api.util.ClassUtils;

import java.io.InputStream;
import java.io.Reader;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.github.benmanes.caffeine.cache.LoadingCache;

/**
 * Provides a way to build immutable {@link DataType} objects.
 *
 * @since 4.0
 */
public class DefaultDataTypeBuilder
    implements DataTypeBuilder, DataTypeBuilder.DataTypeCollectionTypeBuilder, DataTypeBuilder.DataTypeFunctionTypeBuilder,
    DataTypeBuilder.DataTypeMapTypeBuilder {

  private static final String DYNAMIC_CLASS_BUILDER_MATCH = "$ByteBuddy$";
  private static ConcurrentHashMap<String, ProxyIndicator> proxyClassCache = new ConcurrentHashMap<>();
  private static ConcurrentHashMap<String, ProxyIndicator> dynamicClassCache = new ConcurrentHashMap<>();

  /**
   * W-16205376: Since this cache only sets its values to weak and not its keys, entries will only be cleaned up as part of
   * Caffeine's routine maintenance. This implies that some references to class loaders will be kept until the cache is used
   * again. Ex: after an application is undeployed, a DataType cache entry could maintain a reference to the
   * MuleApplicationClassLoader until the application is redeployed.
   */
  private static LoadingCache<DefaultDataTypeBuilder, DataType> dataTypeCache =
      newBuilder().weakValues().build(key -> key.doBuild());

  private Reference<Class<?>> typeRef = new WeakReference<>(Object.class);
  private DataTypeBuilder itemTypeBuilder;
  private MediaType mediaType = MediaType.ANY;
  private DataType returnType;
  private List<FunctionParameter> parametersType;
  private DataTypeBuilder keyTypeBuilder;
  private DataTypeBuilder valueTypeBuilder;

  private DataType keyType = OBJECT;
  private DataType itemType = OBJECT;
  private DataType valueType = OBJECT;

  private DataType original = OBJECT;
  private boolean mutated = false;
  private boolean built = false;

  public DefaultDataTypeBuilder() {

  }

  public DefaultDataTypeBuilder(DataType dataType) {
    this.original = dataType;
    if (dataType instanceof CollectionDataType) {
      this.typeRef = new WeakReference<>(dataType.getType());
      this.itemTypeBuilder = DataType.builder(((CollectionDataType) dataType).getItemDataType());
    } else if (dataType instanceof MapDataType) {
      this.typeRef = new WeakReference<>(dataType.getType());
      this.keyTypeBuilder = DataType.builder(((MapDataType) dataType).getKeyDataType());
      this.valueTypeBuilder = DataType.builder(((MapDataType) dataType).getValueDataType());
    } else if (dataType instanceof FunctionDataType) {
      this.typeRef = new WeakReference<>(dataType.getType());
      Optional<DataType> returnType = ((FunctionDataType) dataType).getReturnType();
      if (returnType.isPresent()) {
        this.returnType = returnType.get();
      }
      this.parametersType = ((FunctionDataType) dataType).getParameters();
    } else {
      this.typeRef = new WeakReference<>(dataType.getType());
    }
    this.mediaType = dataType.getMediaType();
  }

  /**
   * Sets the given typeRef for the {@link DataType} to be built. See {@link DataType#getType()}.
   *
   * @param type the java typeRef to set.
   * @return this builder.
   */
  @Override
  public DataTypeParamsBuilder type(Class<?> type) {
    validateAlreadyBuilt();

    requireNonNull(type, "'type' cannot be null.");
    this.typeRef = new WeakReference<>(handleProxy(type));

    mutated = true;
    return this;
  }

  /*
   * Special case where proxies are used for testing.
   */
  protected Class<?> handleProxy(Class<?> type) {
    if (isProxyClass(type)) {
      return type.getInterfaces()[0];
    } else if (isDynamicallyBuiltClass(type)) {
      return type.getSuperclass().equals(Object.class) ? type.getInterfaces()[0] : type.getSuperclass();
    } else {
      return type;
    }
  }

  /**
   * Cache which classes are proxies. Very experimental
   */
  protected static <T> boolean isProxyClass(Class<T> type) {
    String typeName = type.getName();
    ProxyIndicator indicator = proxyClassCache.get(typeName);
    if (indicator != null) {
      Class classInMap = indicator.getTargetClass();
      if (classInMap == type) {
        return indicator.isProxy();
      } else if (classInMap != null) {
        // We have duplicate class names from different active classloaders. Skip the
        // optimization for this one
        return Proxy.isProxyClass(type);
      }
    }
    // Either there's no indicator in the map or there's one that is due to be replaced
    boolean isProxy = Proxy.isProxyClass(type);
    proxyClassCache.put(typeName, new ProxyIndicator(type, isProxy));
    return isProxy;
  }

  /**
   * Cache which classes are generated by The Dynamic Class Builder (ByteBuddy).
   */
  protected static <T> boolean isDynamicallyBuiltClass(Class<T> type) {
    String typeName = type.getName();
    ProxyIndicator indicator = dynamicClassCache.get(typeName);
    if (indicator != null) {
      Class classInMap = indicator.getTargetClass();
      if (classInMap == type) {
        return indicator.isProxy();
      } else if (classInMap != null) {
        // We have duplicate class names from different active classloaders. Skip the
        // optimization for this one
        return type.getName().contains(DYNAMIC_CLASS_BUILDER_MATCH);
      }
    }
    // Either there's no indicator in the map or there's one that is due to be replaced
    // Use the approach by name for 2 reasons:
    // * to avoid having a class created by the Dynamic Class Builder (Byte Buddy) dependency
    // * since many libs shade Byte Buddy, this accounts for those
    boolean isProxy = type.getName().contains(DYNAMIC_CLASS_BUILDER_MATCH);
    dynamicClassCache.put(typeName, new ProxyIndicator(type, isProxy));
    return isProxy;
  }

  /**
   * map value
   */
  private static final class ProxyIndicator {

    private final WeakReference<Class> targetClassRef;
    private final boolean isProxy;

    ProxyIndicator(Class targetClass, boolean proxy) {
      this.targetClassRef = new WeakReference<>(targetClass);
      isProxy = proxy;
    }

    public Class getTargetClass() {
      return targetClassRef.get();
    }

    public boolean isProxy() {
      return isProxy;
    }
  }

  @Override
  public DataTypeCollectionTypeBuilder streamType(Class<? extends Iterator> iteratorType) {
    validateAlreadyBuilt();

    requireNonNull(iteratorType, "'iteratorType' cannot be null.");
    if (!Iterator.class.isAssignableFrom(iteratorType)) {
      throw new IllegalArgumentException("iteratorType " + iteratorType.getName() + " is not an Iterator type");
    }

    this.typeRef = new WeakReference<>(handleProxy(iteratorType));

    if (this.itemTypeBuilder == null) {
      this.itemTypeBuilder = DataType.builder();
    }

    return asCollectionTypeBuilder();
  }

  /**
   * Sets the given type for the {@link DefaultCollectionDataType} to be built. See {@link DefaultCollectionDataType#getType()}.
   *
   * @param collectionType the java collection type to set.
   * @return this builder.
   * @throws IllegalArgumentException if the given collectionType is not a descendant of {@link Collection}.
   */
  @Override
  public DataTypeCollectionTypeBuilder collectionType(Class<? extends Collection> collectionType) {
    validateAlreadyBuilt();

    requireNonNull(collectionType, "'collectionType' cannot be null.");
    if (!Collection.class.isAssignableFrom(collectionType)) {
      throw new IllegalArgumentException("collectionType " + collectionType.getName() + " is not a Collection type");
    }

    this.typeRef = new WeakReference<>(handleProxy(collectionType));

    if (this.itemTypeBuilder == null) {
      this.itemTypeBuilder = DataType.builder();
    }
    final Class<?> itemType = getCollectionType((Class<? extends Iterable<?>>) typeRef.get());
    if (itemType != null) {
      this.itemTypeBuilder.type(itemType);
    }

    return asCollectionTypeBuilder();
  }

  @Override
  public DataTypeCollectionTypeBuilder asCollectionTypeBuilder() {
    mutated = true;
    return this;
  }

  @Override
  public DataTypeFunctionTypeBuilder functionType(Class<? extends ExpressionFunction> functionType) {
    validateAlreadyBuilt();

    requireNonNull(functionType, "'functionType' cannot be null.");
    if (!ExpressionFunction.class.isAssignableFrom(functionType)) {
      throw new IllegalArgumentException("functionType " + functionType.getName() + " is not an ExpressionFunction type");
    }

    this.typeRef = new WeakReference<>(handleProxy(functionType));

    return asFunctionTypeBuilder();
  }

  @Override
  public DataTypeFunctionTypeBuilder asFunctionTypeBuilder() {
    mutated = true;
    return this;
  }

  @Override
  public DataTypeMapTypeBuilder mapType(Class<? extends Map> mapType) {
    validateAlreadyBuilt();

    requireNonNull(mapType, "'mapType' cannot be null.");
    if (!Map.class.isAssignableFrom(mapType)) {
      throw new IllegalArgumentException("mapType " + mapType.getName() + " is not a Map type");
    }

    this.typeRef = new WeakReference<>(handleProxy(mapType));

    if (this.keyTypeBuilder == null) {
      this.keyTypeBuilder = DataType.builder();
    }
    final Class<?> keyType = getMapKeyType((Class<? extends Map<?, ?>>) typeRef.get());
    if (keyType != null) {
      this.keyTypeBuilder.type(keyType);
    }
    if (this.valueTypeBuilder == null) {
      this.valueTypeBuilder = DataType.builder();
    }
    final Class<?> valueType = getMapValueType((Class<? extends Map<?, ?>>) typeRef.get());
    if (valueType != null) {
      this.valueTypeBuilder.type(valueType);
    }

    return asMapTypeBuilder();
  }

  @Override
  public DataTypeMapTypeBuilder asMapTypeBuilder() {
    mutated = true;
    return this;
  }

  /**
   * Sets the given types for the {@link DefaultCollectionDataType} to be built. See {@link DefaultCollectionDataType#getType()}
   * and {@link DefaultCollectionDataType#getItemDataType()}.
   *
   * @param itemType the java type to set.
   * @return this builder.
   * @throws IllegalArgumentException if the given collectionType is not a descendant of {@link Iterable}.
   */
  @Override
  public DataTypeCollectionTypeBuilder itemType(Class<?> itemType) {
    validateAlreadyBuilt();

    requireNonNull(itemType, "'itemTypeBuilder' cannot be null.");

    if (this.itemTypeBuilder == null) {
      this.itemTypeBuilder = DataType.builder();
    }
    this.itemTypeBuilder.type(handleProxy(itemType));
    mutated = true;
    return this;
  }

  @Override
  public DataTypeFunctionTypeBuilder returnType(DataType dataType) {
    this.returnType = dataType;
    mutated = true;
    return this;
  }

  @Override
  public DataTypeFunctionTypeBuilder parametersType(List<FunctionParameter> list) {
    this.parametersType = list;
    mutated = true;
    return this;
  }

  @Override
  public DataTypeMapTypeBuilder keyType(Class<?> keyType) {
    validateAlreadyBuilt();

    requireNonNull(keyType, "'keyType' cannot be null.");

    if (this.keyTypeBuilder == null) {
      this.keyTypeBuilder = DataType.builder();
    }
    this.keyTypeBuilder.type(handleProxy(keyType));
    mutated = true;
    return this;
  }

  @Override
  public DataTypeMapTypeBuilder valueType(Class<?> valueType) {
    validateAlreadyBuilt();

    requireNonNull(valueType, "'valueType' cannot be null.");

    if (this.valueTypeBuilder == null) {
      this.valueTypeBuilder = DataType.builder();
    }
    this.valueTypeBuilder.type(handleProxy(valueType));
    mutated = true;
    return this;
  }

  /**
   * Sets the given mediaType string. See {@link DataType#getMediaType()}.
   * <p>
   * If the media type for the given string has a {@code charset} parameter, that will be set as the charset for the
   * {@link DataType} being built. That charset can be overridden by calling {@link #charset(String)}.
   *
   * @param mediaType the media type string to set
   * @return this builder.
   * @throws IllegalArgumentException if the given media type string is invalid.
   */
  @Override
  public DataTypeBuilder mediaType(String mediaType) {
    requireNonNull(mediaType);
    validateAlreadyBuilt();

    this.mediaType = MediaType.parse(mediaType);
    mutated = true;
    return this;
  }

  @Override
  public DataTypeBuilder mediaType(MediaType mediaType) {
    requireNonNull(mediaType);
    validateAlreadyBuilt();

    this.mediaType = mediaType;
    mutated = true;
    return this;
  }

  @Override
  public DataTypeCollectionTypeBuilder itemMediaType(String itemMimeType) {
    validateAlreadyBuilt();

    itemTypeBuilder.mediaType(itemMimeType);
    mutated = true;
    return this;
  }

  @Override
  public DataTypeCollectionTypeBuilder itemMediaType(MediaType itemMediaType) {
    validateAlreadyBuilt();

    itemTypeBuilder.mediaType(itemMediaType);
    mutated = true;
    return this;
  }

  @Override
  public DataTypeMapTypeBuilder keyMediaType(String keyMediaType) {
    validateAlreadyBuilt();

    keyTypeBuilder.mediaType(keyMediaType);
    mutated = true;
    return this;
  }

  @Override
  public DataTypeMapTypeBuilder keyMediaType(MediaType keyMediaType) {
    validateAlreadyBuilt();

    keyTypeBuilder.mediaType(keyMediaType);
    mutated = true;
    return this;
  }

  @Override
  public DataTypeMapTypeBuilder valueMediaType(String valueMediaType) {
    validateAlreadyBuilt();

    valueTypeBuilder.mediaType(valueMediaType);
    mutated = true;
    return this;
  }

  @Override
  public DataTypeMapTypeBuilder valueMediaType(MediaType valueMediaType) {
    validateAlreadyBuilt();

    valueTypeBuilder.mediaType(valueMediaType);
    mutated = true;
    return this;
  }

  /**
   * Sets the given charset. See {@link MediaType#getCharset()}.
   *
   * @param charset the encoding to set.
   * @return this builder.
   */
  @Override
  public DataTypeBuilder charset(String charset) {
    validateAlreadyBuilt();

    if (!isEmpty(charset)) {
      mediaType = mediaType.withCharset(Charset.forName(charset));
    } else {
      mediaType = mediaType.withCharset(null);
    }
    mutated = true;
    return this;
  }

  @Override
  public DataTypeBuilder charset(Charset charset) {
    validateAlreadyBuilt();

    mediaType = mediaType.withCharset(charset);
    mutated = true;
    return this;
  }

  @Override
  public DataTypeParamsBuilder fromObject(Object value) {
    validateAlreadyBuilt();

    if (value == null) {
      return type(Object.class);
    } else {
      return type(value.getClass());
    }
  }

  @Override
  public DataTypeFunctionTypeBuilder fromFunction(ExpressionFunction expressionFunction) {
    return functionType(expressionFunction.getClass())
        .returnType(expressionFunction.returnType().orElse(null))
        .parametersType(expressionFunction.parameters());
  }

  /**
   * Builds a new {@link DataType} with the values set in this builder.
   *
   * @return a newly built {@link DataType}.
   */
  @Override
  public DataType build() {
    if (built) {
      throwAlreadyBuilt();
    }
    if (!mutated) {
      return original;
    }

    built = true;
    Class<?> type = this.typeRef.get();
    if (ExpressionFunction.class.isAssignableFrom(type)) {
      return new DefaultFunctionDataType(type, returnType, parametersType != null ? parametersType : newArrayList(), mediaType,
                                         isConsumable(type));
    }

    if (keyTypeBuilder != null) {
      keyType = keyTypeBuilder.build();
    }

    if (itemTypeBuilder != null) {
      itemType = itemTypeBuilder.build();
    }

    if (valueTypeBuilder != null) {
      valueType = valueTypeBuilder.build();
    }

    return dataTypeCache.get(this);
  }

  protected DataType doBuild() {
    Class<?> type = this.typeRef.get();
    if (Collection.class.isAssignableFrom(type) || Iterator.class.isAssignableFrom(type)) {
      return new DefaultCollectionDataType(type, itemType, mediaType, isConsumable(type));
    } else if (Map.class.isAssignableFrom(type)) {
      return new DefaultMapDataType(type, keyType, valueType, mediaType, isConsumable(type));
    } else {
      return new SimpleDataType(type, mediaType, isConsumable(type));
    }
  }

  protected void validateAlreadyBuilt() {
    if (built) {
      throwAlreadyBuilt();
    }
  }

  protected void throwAlreadyBuilt() {
    throw new IllegalStateException("DataType was already built from this builder. Reusing builder instances is not allowed.");
  }

  @Override
  public int hashCode() {
    return Objects.hash(typeRef.get(), itemTypeBuilder, keyTypeBuilder, valueTypeBuilder, returnType, parametersType, mediaType);
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == null) {
      return false;
    }
    if (obj == this) {
      return true;
    }
    if (obj.getClass() != getClass()) {
      return false;
    }
    DefaultDataTypeBuilder other = (DefaultDataTypeBuilder) obj;

    return Objects.equals(typeRef.get(), other.typeRef.get()) && Objects.equals(itemTypeBuilder, other.itemTypeBuilder)
        && Objects.equals(keyTypeBuilder, other.keyTypeBuilder) && Objects.equals(valueTypeBuilder, other.valueTypeBuilder)
        && Objects.equals(returnType, other.returnType) && Objects.equals(parametersType, other.parametersType)
        && Objects.equals(mediaType, other.mediaType);
  }

  private static final List<Class<?>> consumableClasses = new ArrayList<>();

  static {
    addToConsumableClasses("javax.xml.stream.XMLStreamReader");
    addToConsumableClasses("javax.xml.transform.stream.StreamSource");
    consumableClasses.add(OutputHandler.class);
    consumableClasses.add(InputStream.class);
    consumableClasses.add(Reader.class);
    consumableClasses.add(Iterator.class);
  }

  private static void addToConsumableClasses(String className) {
    try {
      consumableClasses.add(ClassUtils.loadClass(className, Message.class));
    } catch (ClassNotFoundException e) {
      // ignore
    }
  }

  /**
   * Determines if the payload of this message is consumable i.e. it can't be read more than once.
   */
  public static boolean isConsumable(Class<?> payloadClass) {
    if (consumableClasses.isEmpty()) {
      return false;
    }

    for (Class<?> c : consumableClasses) {
      if (c.isAssignableFrom(payloadClass)) {
        return true;
      }
    }
    return false;
  }

}
