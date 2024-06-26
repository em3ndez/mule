/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.api.runtime.resolver;

import static java.util.Objects.requireNonNull;
import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;

import org.mule.runtime.api.component.location.ComponentLocation;
import org.mule.runtime.core.api.el.ExpressionManager;
import org.mule.runtime.core.api.el.ExpressionManagerSession;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.extension.api.runtime.config.ConfigurationInstance;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Context used to provide all the parameters required for a {@link ValueResolver} to produce a result.
 *
 * @since 4.0
 */
public class ValueResolvingContext implements AutoCloseable {

  private CoreEvent event;
  private final ConfigurationInstance config;
  private final ExpressionManagerSession session;
  private final Map<String, Object> properties;
  private final boolean resolveCursors;
  private final boolean acceptsNullValues;

  private ValueResolvingContext(CoreEvent event,
                                ExpressionManagerSession session,
                                ConfigurationInstance config,
                                boolean resolveCursors,
                                boolean acceptsNullValues,
                                Map<String, Object> properties) {
    this.event = event;
    this.session = session;
    this.config = config;
    this.resolveCursors = resolveCursors;
    this.acceptsNullValues = acceptsNullValues;
    this.properties = properties;
  }

  /**
   * A builder to create {@link ValueResolvingContext} instances.
   *
   * @param event The event used to create this context
   *
   * @return a builder that can create instance of {@link ValueResolvingContext}
   */
  public static Builder builder(CoreEvent event) {
    return new Builder().withEvent(event);
  }

  /**
   * A builder to create {@link ValueResolvingContext} instances.
   *
   * @param event The event used to create this context
   *
   * @return a builder that can create instance of {@link ValueResolvingContext}
   */
  public static Builder builder(CoreEvent event, ExpressionManager expressionManager) {
    return new Builder().withEvent(event).withExpressionManager(expressionManager);
  }

  /**
   * @return the {@link CoreEvent} of the current resolution context
   */
  public CoreEvent getEvent() {
    return event;
  }

  /**
   * @param event the {@link CoreEvent} of the current resolution context. Not null.
   */
  public void changeEvent(CoreEvent event) {
    requireNonNull(event);
    this.event = event;
  }

  /**
   * @return the {@link ConfigurationInstance} of the current resolution context if one is bound to the element to be resolved, or
   *         {@link Optional#empty()} if none is found.
   */
  public Optional<ConfigurationInstance> getConfig() {
    return ofNullable(config);
  }

  /**
   * @param propertyName the name of the property to be retrieved
   * @return the value of the property if found or null if it is not present in the context.
   * @since 4.3.0
   */
  public Object getProperty(String propertyName) {
    return properties.get(propertyName);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ValueResolvingContext)) {
      return false;
    }

    ValueResolvingContext that = (ValueResolvingContext) o;
    return Objects.equals(event, that.event) && Objects.equals(config, that.config);
  }

  @Override
  public int hashCode() {
    return Objects.hash(event, config);
  }

  public boolean resolveCursors() {
    return resolveCursors;
  }

  /**
   * Whether the generated {@link ResolverSetResult} should include null values or not.
   * <p>
   * If set to {@code false}, the output of {@link ResolverSetResult#asMap()} will not include any entries for which the resolved
   * value was {@code null}
   *
   * @return {@code this} builder
   * @since 4.5.0
   */
  public boolean acceptsNullValues() {
    return acceptsNullValues;
  }

  public ExpressionManagerSession getSession() {
    return session;
  }

  @Override
  public void close() {
    if (session != null) {
      session.close();
    }
  }

  public static class Builder {

    private CoreEvent event;
    private Optional<ConfigurationInstance> config = empty();
    private Map<String, Object> properties = new HashMap<>();
    private ExpressionManager manager;
    private boolean resolveCursors = true;
    private boolean acceptsNullValues = true;
    private ComponentLocation location;

    public Builder withEvent(CoreEvent event) {
      this.event = event;
      return this;
    }

    public Builder withConfig(Optional<ConfigurationInstance> config) {
      this.config = config;
      return this;
    }

    public Builder withConfig(ConfigurationInstance config) {
      this.config = ofNullable(config);
      return this;
    }

    public Builder withExpressionManager(ExpressionManager manager) {
      this.manager = manager;
      return this;
    }

    public Builder withLocation(ComponentLocation location) {
      this.location = location;
      return this;
    }

    /**
     * Adds a property to the {@link ValueResolvingContext} to be built.
     *
     * @param propertyName  the name of the property to be stored in the context
     * @param propertyValue the value of the property to be stored in the context
     * @return this builder
     */
    public Builder withProperty(String propertyName, Object propertyValue) {
      this.properties.put(propertyName, propertyValue);
      return this;
    }

    public Builder resolveCursors(boolean resolveCursors) {
      this.resolveCursors = resolveCursors;
      return this;
    }

    /**
     * Whether the generated {@link ResolverSetResult} should include null values or not.
     * <p>
     * If set to {@code false}, the output of {@link ResolverSetResult#asMap()} will not include any entries for which the
     * resolved value was {@code null}.
     * <p>
     * Default value if not specified is {@code true}
     *
     * @return {@code this} builder
     * @since 4.5.0
     */
    public Builder acceptsNullValues(boolean acceptsNullValues) {
      this.acceptsNullValues = acceptsNullValues;
      return this;
    }

    public ValueResolvingContext build() {
      if (event == null) {
        return new ValueResolvingContext(null, null, null, true, acceptsNullValues, properties);
      } else if (manager == null) {
        return new ValueResolvingContext(event, null, config.orElse(null), resolveCursors, acceptsNullValues, properties);
      } else if (location == null) {
        return new ValueResolvingContext(event, manager.openSession(event.asBindingContext()), config.orElse(null),
                                         resolveCursors, acceptsNullValues, properties);
      } else {
        return new ValueResolvingContext(event, manager.openSession(location, null, event.asBindingContext()),
                                         config.orElse(null), resolveCursors, acceptsNullValues, properties);
      }
    }
  }
}
