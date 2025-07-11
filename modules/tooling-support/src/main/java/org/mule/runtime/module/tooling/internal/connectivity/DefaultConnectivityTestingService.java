/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.tooling.internal.connectivity;

import static org.mule.runtime.api.connection.ConnectionValidationResult.failure;
import static org.mule.runtime.api.connectivity.ConnectivityTestingStrategy.lookupConnectivityTestingStrategies;
import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;

import static java.util.stream.Collectors.toList;

import org.mule.runtime.api.component.location.ConfigurationComponentLocator;
import org.mule.runtime.api.component.location.Location;
import org.mule.runtime.api.connection.ConnectionValidationResult;
import org.mule.runtime.api.connectivity.ConnectivityTestingService;
import org.mule.runtime.api.connectivity.ConnectivityTestingStrategy;
import org.mule.runtime.api.connectivity.UnsupportedConnectivityTestingObjectException;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.exception.ObjectNotFoundException;
import org.mule.runtime.api.lifecycle.Initialisable;
import org.mule.runtime.api.lifecycle.InitialisationException;
import org.mule.runtime.core.api.MuleContext;

import java.util.Collection;
import java.util.function.Supplier;
import java.util.stream.Stream;

import jakarta.inject.Inject;

/**
 * Default implementation of {@link ConnectivityTestingService}.
 * <p>
 * It searches for the {@link ConnectivityTestingStrategy} instances registered in mule to find the possible strategies to do
 * connection testing over mule component instances
 *
 * @since 4.0
 */
public class DefaultConnectivityTestingService implements ConnectivityTestingService, Initialisable {

  private Supplier<Stream<ConnectivityTestingStrategy>> serviceRegistry =
      () -> lookupConnectivityTestingStrategies(this.getClass().getClassLoader());
  private Collection<ConnectivityTestingStrategy> connectivityTestingStrategies;
  private MuleContext muleContext;
  private ConfigurationComponentLocator locator;

  protected void setServiceRegistry(Supplier<Stream<ConnectivityTestingStrategy>> serviceRegistry) {
    this.serviceRegistry = serviceRegistry;
  }

  @Inject
  protected void setMuleContext(MuleContext muleContext) {
    this.muleContext = muleContext;
  }

  @Inject
  public void setLocator(ConfigurationComponentLocator locator) {
    this.locator = locator;
  }

  @Override
  public void initialise() throws InitialisationException {
    connectivityTestingStrategies = serviceRegistry.get().collect(toList());

    for (ConnectivityTestingStrategy connectivityTestingStrategy : connectivityTestingStrategies) {
      try {
        muleContext.getInjector().inject(connectivityTestingStrategy);
      } catch (MuleException e) {
        throw new InitialisationException(createStaticMessage("Could not initialise connectivity testing strategy of type "
            + connectivityTestingStrategy.getClass().getName()),
                                          e,
                                          this);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ConnectionValidationResult testConnection(Location location) {
    Object connectivityTestingObject =
        locator.find(location).orElseThrow((() -> new ObjectNotFoundException("No object found with path: " + location)));
    for (ConnectivityTestingStrategy connectivityTestingStrategy : connectivityTestingStrategies) {
      if (connectivityTestingStrategy.accepts(connectivityTestingObject)) {
        try {
          return connectivityTestingStrategy.testConnectivity(connectivityTestingObject);
        } catch (Exception e) {
          return failure(e.getMessage(), e);
        }
      }
    }
    throw new UnsupportedConnectivityTestingObjectException(createStaticMessage("Could not do connectivity testing over object of type "
        + connectivityTestingObject.getClass().getName()));
  }
}
