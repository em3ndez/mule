/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.connection;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyObject;
import static org.mockito.ArgumentMatchers.anyVararg;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mule.runtime.api.config.PoolingProfile.DEFAULT_MAX_POOL_WAIT;
import static org.mule.runtime.api.config.PoolingProfile.DEFAULT_POOL_EXHAUSTED_ACTION;
import static org.mule.runtime.api.config.PoolingProfile.DEFAULT_POOL_INITIALISATION_POLICY;
import static org.mule.runtime.api.config.PoolingProfile.INITIALISE_ALL;
import static org.mule.runtime.api.config.PoolingProfile.INITIALISE_NONE;
import static org.mule.runtime.api.config.PoolingProfile.WHEN_EXHAUSTED_FAIL;
import static org.mule.runtime.api.config.PoolingProfile.WHEN_EXHAUSTED_WAIT;
import static org.mule.tck.MuleTestUtils.spyInjector;
import org.mule.runtime.api.config.PoolingProfile;
import org.mule.runtime.api.connection.ConnectionException;
import org.mule.runtime.api.connection.ConnectionHandler;
import org.mule.runtime.api.connection.ConnectionProvider;
import org.mule.runtime.api.connection.ConnectionValidationResult;
import org.mule.runtime.api.connection.PoolingListener;
import org.mule.runtime.core.api.Injector;
import org.mule.runtime.api.lifecycle.Lifecycle;
import org.mule.tck.junit4.AbstractMuleContextTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class PoolingConnectionManagementStrategyTestCase extends AbstractMuleContextTestCase {

  private static final int MAX_ACTIVE = 2;

  private ConnectionProvider<Object> connectionProvider;

  private PoolingProfile poolingProfile =
      new PoolingProfile(MAX_ACTIVE, MAX_ACTIVE, DEFAULT_MAX_POOL_WAIT, WHEN_EXHAUSTED_WAIT, INITIALISE_NONE);
  private PoolingConnectionManagementStrategy<Object> strategy;
  private PoolingListener<Object> poolingListener;
  private Injector injector;

  private ConnectionHandler<Object> connection1;
  private ConnectionHandler<Object> connection2;

  @Before
  public void before() throws Exception {
    poolingListener = mock(PoolingListener.class);
    injector = spyInjector(muleContext);
    muleContext.start();
    resetConnectionProvider();
  }

  @Test
  public void getConnection() throws Exception {
    initStrategy();
    connection1 = strategy.getConnectionHandler();
    connection2 = strategy.getConnectionHandler();

    assertThat(connection1, is(not(sameInstance(connection2))));
    assertThat(connection1.getConnection(), is(not(sameInstance(connection2.getConnection()))));
    verify(connectionProvider, times(2)).connect();

    verify(poolingListener).onBorrow(connection1.getConnection());
    verify(poolingListener).onBorrow(connection2.getConnection());
  }

  @Test
  public void poolingListenerFailsOnBorrow() throws Exception {
    initStrategy();
    final RuntimeException exception = new RuntimeException();

    doThrow(exception).when(poolingListener).onBorrow(any(Lifecycle.class));

    try {
      strategy.getConnectionHandler();
      fail("was expecting poolingListener to fail");
    } catch (Exception e) {
      assertThat(e.getCause(), is(sameInstance(exception)));
      verify(connectionProvider).disconnect(any(Lifecycle.class));
    }
  }

  @Test
  public void connectionsDependenciesInjected() throws Exception {
    initStrategy();
    connection1 = strategy.getConnectionHandler();
    connection2 = strategy.getConnectionHandler();

    verify(injector).inject(connection1.getConnection());
    verify(injector).inject(connection2.getConnection());
  }

  @Test
  public void exhaustion() throws Exception {
    poolingProfile = new PoolingProfile(1, 1, DEFAULT_MAX_POOL_WAIT, WHEN_EXHAUSTED_FAIL, INITIALISE_NONE);
    initStrategy();

    ConnectionHandler<Object> connectionHandler = strategy.getConnectionHandler();
    try {
      strategy.getConnectionHandler();
      fail("Was expecting the pool to be exhausted");
    } catch (ConnectionException e) {
      // wiiiii
    }

    connectionHandler.release();
    assertThat(strategy.getConnectionHandler(), is(notNullValue()));
  }

  @Test
  public void release() throws Exception {
    initStrategy();
    connection1 = strategy.getConnectionHandler();
    connection2 = strategy.getConnectionHandler();

    connection1.release();
    connection2.release();

    strategy.close();

    verify(connectionProvider, times(2)).disconnect(any(Object.class));
  }

  @Test(expected = ConnectionException.class)
  public void failDueToInvalidConnection() throws ConnectionException {
    initStrategy();
    connection1 = strategy.getConnectionHandler();
    connection2 = strategy.getConnectionHandler();

    when(connectionProvider.validate(anyVararg())).thenReturn(ConnectionValidationResult
        .failure("Invalid username or password",
                 new Exception("401: UNAUTHORIZED")));
    strategy.getConnectionHandler().getConnection();
  }

  @Test(expected = ConnectionException.class)
  public void failDueToNullConnectionValidationResult() throws ConnectionException {
    initStrategy();
    connection1 = strategy.getConnectionHandler();
    connection2 = strategy.getConnectionHandler();

    when(connectionProvider.validate(anyVararg())).thenReturn(null);
    strategy.getConnectionHandler().getConnection();
  }

  @Test
  public void defaultInitializationPolicyIsHonored() throws ConnectionException {
    poolingProfile =
        new PoolingProfile(5, -1, DEFAULT_MAX_POOL_WAIT, DEFAULT_POOL_EXHAUSTED_ACTION, DEFAULT_POOL_INITIALISATION_POLICY);
    initStrategy();
    verifyConnections(1);
  }

  @Test
  public void initializationPolicyInitialiseAllWithUnlimitedMaxIdle() throws ConnectionException {
    poolingProfile = new PoolingProfile(5, -1, DEFAULT_MAX_POOL_WAIT, DEFAULT_POOL_EXHAUSTED_ACTION, INITIALISE_ALL);
    initStrategy();
    verifyConnections(5);
  }

  @Test
  public void initializationPolicyInitialiseAllWithUnlimitedMaxActive() throws ConnectionException {
    poolingProfile = new PoolingProfile(-1, 2, DEFAULT_MAX_POOL_WAIT, DEFAULT_POOL_EXHAUSTED_ACTION, INITIALISE_ALL);
    initStrategy();
    verifyConnections(2);
  }

  @Test
  public void initializationPolicyInitialiseAllWithUnlimitedMaxActiveAndUnlimitedMaxIdle() throws ConnectionException {
    poolingProfile = new PoolingProfile(-1, -1, DEFAULT_MAX_POOL_WAIT, DEFAULT_POOL_EXHAUSTED_ACTION, INITIALISE_ALL);
    initStrategy();
    verifyConnections(0);
  }

  @Test
  public void initializationPolicyInitialiseAllWithMaxIdleSmallerThanMaxActive() throws ConnectionException {
    poolingProfile = new PoolingProfile(5, 3, DEFAULT_MAX_POOL_WAIT, DEFAULT_POOL_EXHAUSTED_ACTION, INITIALISE_ALL);
    initStrategy();
    verifyConnections(3);
  }

  @Test
  public void initializationPolicyInitialiseAllWithMaxIdleGreaterThanMaxActive() throws ConnectionException {
    poolingProfile = new PoolingProfile(5, 8, DEFAULT_MAX_POOL_WAIT, DEFAULT_POOL_EXHAUSTED_ACTION, INITIALISE_ALL);
    initStrategy();
    verifyConnections(5);
  }

  @Test
  public void initializationPolicyInitialiseNone() throws ConnectionException {
    poolingProfile = new PoolingProfile(5, -1, DEFAULT_MAX_POOL_WAIT, DEFAULT_POOL_EXHAUSTED_ACTION, INITIALISE_NONE);
    initStrategy();
    verifyConnections(0);
  }

  @Test
  public void initializationDoesNotFailWhenEncounteringConnectionException() throws ConnectionException {
    ConnectionProvider<Object> connectionProvider = mock(ConnectionProvider.class);
    when(connectionProvider.connect()).thenReturn(new Object()).thenThrow(ConnectionException.class).thenReturn(new Object());
    this.connectionProvider = spy(new DefaultConnectionProviderWrapper<>(connectionProvider, muleContext));
    poolingProfile = new PoolingProfile(3, -1, DEFAULT_MAX_POOL_WAIT, DEFAULT_POOL_EXHAUSTED_ACTION, INITIALISE_ALL);
    initStrategy();
    verifyConnections(3);
  }

  private void resetConnectionProvider() throws ConnectionException {
    ConnectionProvider<Object> connectionProvider = mock(ConnectionProvider.class);
    when(connectionProvider.connect()).thenAnswer(i -> mock(Lifecycle.class));
    when(connectionProvider.validate(anyObject())).thenReturn(ConnectionValidationResult.success());
    this.connectionProvider = spy(new DefaultConnectionProviderWrapper<>(connectionProvider, muleContext));
  }

  private void initStrategy() {
    strategy = new PoolingConnectionManagementStrategy<>(connectionProvider, poolingProfile, poolingListener, muleContext);
  }

  private void verifyConnections(int numToCreate) throws ConnectionException {
    verify(this.connectionProvider, times(numToCreate)).connect();
    verify(this.connectionProvider, times(0)).disconnect(any());
  }

}
