/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.connection;

import static org.mule.runtime.core.api.retry.ReconnectionConfig.defaultReconnectionConfig;

import static java.util.Optional.of;

import org.mule.runtime.api.connection.ConnectionException;
import org.mule.runtime.api.connection.ConnectionProvider;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.lifecycle.Lifecycle;
import org.mule.runtime.core.api.Injector;
import org.mule.runtime.core.api.retry.ReconnectionConfig;
import org.mule.runtime.core.api.retry.policy.RetryPolicyTemplate;

import java.util.Optional;

/**
 * An {@link ConnectionProviderWrapper} which performs base tasks as handling reconnection strategies, DI, etc.
 *
 * @param <C> the generic type of the connections that the {@link #delegate} produces
 * @since 4.0
 */
public class DefaultConnectionProviderWrapper<C> extends AbstractConnectionProviderWrapper<C> {

  private final Injector injector;

  /**
   * Creates a new instance
   *
   * @param delegate the {@link ConnectionProvider} to be wrapped
   * @param injector the owning context injector
   */
  public DefaultConnectionProviderWrapper(ConnectionProvider<C> delegate, Injector injector) {
    super(delegate);
    this.injector = injector;
  }

  /**
   * Obtains a {@code Connection} from the delegate and applies injection dependency and the {@code muleContext}'s completed
   * {@link Lifecycle} phases
   *
   * @return a {@code Connection} with dependencies injected and the correct lifecycle state
   * @throws ConnectionException if an exception was found obtaining the connection or managing it
   */
  @Override
  public C connect() throws ConnectionException {
    C connection = super.connect();
    try {
      injector.inject(connection);
    } catch (MuleException e) {
      throw new ConnectionException("Could not initialise connection", e);
    }

    return connection;
  }

  @Override
  public RetryPolicyTemplate getRetryPolicyTemplate() {
    final ConnectionProvider<C> delegate = getDelegate();
    if (delegate instanceof ConnectionProviderWrapper) {
      return ((ConnectionProviderWrapper) delegate).getRetryPolicyTemplate();
    }

    return super.getRetryPolicyTemplate();
  }

  @Override
  public Optional<ReconnectionConfig> getReconnectionConfig() {
    final ConnectionProvider<C> delegate = getDelegate();
    if (delegate instanceof ConnectionProviderWrapper) {
      return ((ConnectionProviderWrapper) delegate).getReconnectionConfig();
    }

    return of(defaultReconnectionConfig());
  }
}
