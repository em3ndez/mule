/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.config.dsl;

import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.mule.runtime.api.util.NameUtils.hyphenize;
import static org.mule.runtime.module.extension.internal.config.dsl.ExtensionParsingUtils.isChildKey;
import static org.mule.runtime.module.extension.internal.config.dsl.ExtensionParsingUtils.unwrapChildKey;

import static java.lang.String.format;

import org.mule.metadata.api.model.ObjectType;
import org.mule.runtime.api.component.location.ComponentLocation;
import org.mule.runtime.api.util.LazyValue;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.config.ConfigurationException;
import org.mule.runtime.core.api.el.ExpressionManager;
import org.mule.runtime.dsl.api.component.AbstractComponentFactory;
import org.mule.runtime.dsl.api.component.ObjectFactory;
import org.mule.runtime.module.extension.internal.runtime.exception.RequiredParameterNotSetException;
import org.mule.runtime.module.extension.internal.runtime.objectbuilder.DefaultObjectBuilder;
import org.mule.runtime.module.extension.internal.runtime.resolver.ObjectTypeParametersResolver;
import org.mule.runtime.module.extension.internal.runtime.resolver.ParametersResolver;
import org.mule.runtime.module.extension.internal.util.ReflectionCache;

import java.util.HashMap;
import java.util.Map;

import jakarta.inject.Inject;

/**
 * Base class for {@link ObjectFactory} implementation which create extension components.
 * <p>
 * Contains behavior to obtain and manage components parameters.
 *
 * @param <T> the generic type of the instances to be built
 * @since 4.0
 */
public abstract class AbstractExtensionObjectFactory<T> extends AbstractComponentFactory<T>
    implements ObjectTypeParametersResolver {

  @Inject
  protected ReflectionCache reflectionCache;

  @Inject
  protected ExpressionManager expressionManager;

  protected final MuleContext muleContext;

  protected Map<String, Object> parameters = new HashMap<>();
  private LazyValue<ParametersResolver> parametersResolver;

  public AbstractExtensionObjectFactory(MuleContext muleContext) {
    this.muleContext = muleContext;
    this.parametersResolver = new LazyValue<>(() -> parametersResolverFromValues(muleContext));
  }

  @Override
  public T getObject() throws Exception {
    try {
      return super.getObject();
    } catch (RequiredParameterNotSetException e) {
      throw handleMissingRequiredParameter(e);
    }
  }

  private Exception handleMissingRequiredParameter(RequiredParameterNotSetException e) {
    String description = getAnnotations().values().stream()
        .filter(ComponentLocation.class::isInstance)
        .map(v -> (ComponentLocation) v)
        .findFirst()
        .map(v -> format("Element <%s:%s> in line %s of file %s is missing required parameter '%s'",
                         v.getComponentIdentifier().getIdentifier().getNamespace(),
                         v.getComponentIdentifier().getIdentifier().getName(),
                         v.getLineInFile().map(n -> "" + n).orElse("<UNKNOWN>"),
                         v.getFileName().orElse("<UNKNOWN>"),
                         hyphenize(e.getParameterName())))
        .orElse(e.getMessage());

    return new ConfigurationException(createStaticMessage(description), e);
  }

  protected ParametersResolver getParametersResolver() {
    return parametersResolver.get();
  }

  private ParametersResolver parametersResolverFromValues(MuleContext muleContext) {
    return ParametersResolver.fromValues(parameters, muleContext, reflectionCache, expressionManager,
                                         this.getRepresentation());
  }

  public Map<String, Object> getParameters() {
    return parameters;
  }

  public void setParameters(Map<String, Object> parameters) {
    this.parameters = normalize(parameters);
    this.parametersResolver = new LazyValue<>(parametersResolverFromValues(muleContext));
  }

  @Override
  public void resolveParameterGroups(ObjectType objectType, DefaultObjectBuilder builder) {
    parametersResolver.get().resolveParameterGroups(objectType, builder);
  }

  @Override
  public void resolveParameters(ObjectType objectType, DefaultObjectBuilder builder) {
    parametersResolver.get().resolveParameters(objectType, builder);
  }

  private Map<String, Object> normalize(Map<String, Object> parameters) {
    Map<String, Object> normalized = new HashMap<>();
    parameters.forEach((key, value) -> {
      String normalizedKey = key;

      if (isChildKey(key)) {
        normalizedKey = unwrapChildKey(key);
        normalized.put(normalizedKey, value);
      } else {
        if (!normalized.containsKey(normalizedKey)) {
          normalized.put(normalizedKey, value);
        }
      }
    });

    return normalized;
  }

}
