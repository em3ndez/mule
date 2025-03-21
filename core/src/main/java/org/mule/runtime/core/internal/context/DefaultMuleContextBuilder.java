/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.context;

import static org.mule.runtime.core.api.context.notification.ServerNotificationManager.createDefaultNotificationManager;
import static org.mule.runtime.core.internal.registry.SimpleRegistry.createFeatureFlaggingService;

import static java.util.Optional.empty;
import static java.util.Optional.of;

import org.mule.runtime.api.artifact.ArtifactCoordinates;
import org.mule.runtime.api.config.FeatureFlaggingService;
import org.mule.runtime.api.exception.ErrorTypeRepository;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.i18n.I18nMessage;
import org.mule.runtime.api.i18n.I18nMessageFactory;
import org.mule.runtime.api.serialization.ObjectSerializer;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.config.DefaultMuleConfiguration;
import org.mule.runtime.core.api.config.MuleConfiguration;
import org.mule.runtime.core.api.config.bootstrap.ArtifactType;
import org.mule.runtime.core.api.config.bootstrap.BootstrapServiceDiscoverer;
import org.mule.runtime.core.api.config.bootstrap.PropertiesBootstrapServiceDiscoverer;
import org.mule.runtime.core.api.context.MuleContextAware;
import org.mule.runtime.core.api.context.MuleContextBuilder;
import org.mule.runtime.core.api.context.notification.MuleContextListener;
import org.mule.runtime.core.api.context.notification.ServerNotificationManager;
import org.mule.runtime.core.api.exception.SystemExceptionHandler;
import org.mule.runtime.core.api.lifecycle.LifecycleManager;
import org.mule.runtime.core.api.util.ClassUtils;
import org.mule.runtime.core.internal.exception.DefaultSystemExceptionStrategy;
import org.mule.runtime.core.internal.lifecycle.MuleContextLifecycleManager;
import org.mule.runtime.core.internal.registry.SimpleRegistry;
import org.mule.runtime.core.internal.serialization.JavaObjectSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of {@link MuleContextBuilder} that uses {@link DefaultMuleContext} as the default {@link MuleContext}
 * implementation and builds it with defaults values for {@link MuleConfiguration}, {@link LifecycleManager} and
 * {@link ServerNotificationManager}.
 */
public class DefaultMuleContextBuilder implements MuleContextBuilder {

  protected static final Logger logger = LoggerFactory.getLogger(DefaultMuleContextBuilder.class);
  private final ArtifactType artifactType;

  protected MuleConfiguration config;

  private MuleContextLifecycleManager lifecycleManager;

  protected ServerNotificationManager notificationManager;

  protected ClassLoader executionClassLoader;

  protected ObjectSerializer objectSerializer;

  private Optional<Properties> deploymentProperties = empty();

  private List<MuleContextListener> listeners = new ArrayList<>();

  private ArtifactCoordinates artifactCoordinates;

  private Optional<FeatureFlaggingService> featureFlaggingService;

  /**
   * Creates a new builder
   *
   * @param artifactType type of the artifact that will own the context created with this builder
   */
  public DefaultMuleContextBuilder(ArtifactType artifactType) {
    this.artifactType = artifactType;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public MuleContext buildMuleContext() {
    logger.debug("Building new DefaultMuleContext instance with MuleContextBuilder: " + this);
    DefaultMuleContext muleContext = createDefaultMuleContext();
    muleContext.setMuleConfiguration(injectMuleContextIfRequired(getMuleConfiguration(), muleContext));
    muleContext.setNotificationManager(injectMuleContextIfRequired(getNotificationManager(), muleContext));
    muleContext.setLifecycleManager(injectMuleContextIfRequired(getLifecycleManager(), muleContext));
    muleContext.setArtifactType(artifactType);

    configureRegistry(muleContext);

    muleContext.setExceptionListener(createExceptionListener(muleContext));
    muleContext.setExecutionClassLoader(getExecutionClassLoader());
    muleContext
        .setBootstrapServiceDiscoverer(injectMuleContextIfRequired(createBootstrapDiscoverer(), muleContext));
    muleContext.setDeploymentProperties(getDeploymentProperties());
    muleContext.setListeners(listeners);
    getObjectSerializer(muleContext);

    if (artifactCoordinates != null) {
      ((DefaultMuleConfiguration) muleContext.getConfiguration()).setArtifactCoordinates(artifactCoordinates);
    }

    return muleContext;
  }

  protected void configureRegistry(DefaultMuleContext muleContext) {
    final SimpleRegistry registry =
        new SimpleRegistry(muleContext, muleContext.getLifecycleInterceptor(),
                           featureFlaggingService == null ? of(createFeatureFlaggingService(muleContext))
                               : featureFlaggingService);
    muleContext.setRegistry(registry);
    muleContext.setInjector(registry);
  }

  private Properties getDeploymentProperties() {
    if (!deploymentProperties.isPresent()) {
      return new Properties();
    }

    return deploymentProperties.get();
  }

  private void getObjectSerializer(DefaultMuleContext muleContext) {
    if (objectSerializer == null) {
      objectSerializer = new JavaObjectSerializer(muleContext.getExecutionClassLoader());
    }

    muleContext.setObjectSerializer(objectSerializer);
  }

  private SystemExceptionHandler createExceptionListener(DefaultMuleContext muleContext) {
    SystemExceptionHandler systemExceptionHandler = muleContext.getRegistry().get("_exceptionListenerFactory");
    if (systemExceptionHandler == null) {
      systemExceptionHandler = new DefaultSystemExceptionStrategy();
    }
    return systemExceptionHandler;
  }

  protected DefaultMuleContext createDefaultMuleContext() {
    return new DefaultMuleContext();
  }

  @Override
  public void setMuleConfiguration(MuleConfiguration config) {
    this.config = config;
  }

  @Override
  public void setNotificationManager(ServerNotificationManager notificationManager) {
    this.notificationManager = notificationManager;
  }

  public MuleConfiguration getMuleConfiguration() {
    if (config != null) {
      return config;
    } else {
      return createMuleConfiguration();
    }
  }

  @Override
  public void setExecutionClassLoader(ClassLoader executionClassLoader) {
    this.executionClassLoader = executionClassLoader;
  }

  @Override
  public void setObjectSerializer(ObjectSerializer objectSerializer) {
    this.objectSerializer = objectSerializer;
  }

  protected ClassLoader getExecutionClassLoader() {
    if (executionClassLoader != null) {
      return executionClassLoader;
    } else {
      return Thread.currentThread().getContextClassLoader();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setErrorTypeRepository(ErrorTypeRepository errorTypeRepository) {
    // Nothing to do
  }

  private <T> T injectMuleContextIfRequired(T object, MuleContext muleContext) {
    if (object instanceof MuleContextAware) {
      ((MuleContextAware) object).setMuleContext(muleContext);
    }
    return object;
  }

  protected LifecycleManager getLifecycleManager() {
    if (lifecycleManager != null) {
      return lifecycleManager;
    } else {
      return createLifecycleManager();
    }
  }

  @Override
  public void setLifecycleManager(LifecycleManager manager) {
    if (!(manager instanceof MuleContextLifecycleManager)) {
      I18nMessage msg =
          I18nMessageFactory.createStaticMessage("lifecycle manager for MuleContext must be a MuleContextLifecycleManager");
      throw new MuleRuntimeException(msg);
    }

    lifecycleManager = (MuleContextLifecycleManager) manager;
  }

  protected ServerNotificationManager getNotificationManager() {
    if (notificationManager != null) {
      return notificationManager;
    } else {
      return createNotificationManager();
    }
  }

  private BootstrapServiceDiscoverer createBootstrapDiscoverer() {
    return new PropertiesBootstrapServiceDiscoverer(DefaultMuleContextBuilder.class.getClassLoader());
  }

  protected DefaultMuleConfiguration createMuleConfiguration() {
    return new DefaultMuleConfiguration();
  }

  private MuleContextLifecycleManager createLifecycleManager() {
    return new MuleContextLifecycleManager();
  }

  protected ServerNotificationManager createNotificationManager() {
    return createDefaultNotificationManager();
  }

  @Override
  public String toString() {
    return ClassUtils.getClassName(getClass()) + "{muleConfiguration=" + config + ", lifecycleManager=" + lifecycleManager
        + ", notificationManager=" + notificationManager + "}";
  }

  @Override
  public void setDeploymentProperties(Optional<Properties> deploymentProperties) {
    this.deploymentProperties = deploymentProperties;
  }

  @Override
  public void setListeners(List<MuleContextListener> listeners) {
    this.listeners = listeners;
  }

  @Override
  public void setArtifactCoordinates(ArtifactCoordinates artifactCoordinates) {
    this.artifactCoordinates = artifactCoordinates;
  }

  @Override
  public void setFeatureFlaggingService(Optional<FeatureFlaggingService> featureFlaggingService) {
    this.featureFlaggingService = featureFlaggingService;
  }
}
