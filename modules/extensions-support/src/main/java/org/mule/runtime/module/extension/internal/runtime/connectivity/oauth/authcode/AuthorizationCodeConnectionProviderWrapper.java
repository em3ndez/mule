/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.runtime.connectivity.oauth.authcode;

import static org.mule.runtime.module.extension.internal.runtime.connectivity.oauth.ExtensionsOAuthUtils.AUTHORIZATION_CODE_STATE_INTERFACES;
import static org.mule.runtime.module.extension.internal.runtime.connectivity.oauth.ExtensionsOAuthUtils.getOAuthStateSetter;
import static org.mule.runtime.module.extension.internal.runtime.connectivity.oauth.ExtensionsOAuthUtils.updateOAuthParameters;

import org.mule.runtime.api.connection.ConnectionException;
import org.mule.runtime.api.connection.ConnectionProvider;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.core.api.retry.ReconnectionConfig;
import org.mule.runtime.core.api.util.func.Once;
import org.mule.runtime.core.api.util.func.Once.RunOnce;
import org.mule.runtime.core.internal.connection.ReconnectableConnectionProviderWrapper;
import org.mule.runtime.extension.api.annotation.connectivity.oauth.OAuthCallbackValue;
import org.mule.runtime.extension.api.connectivity.NoConnectivityTest;
import org.mule.runtime.extension.api.connectivity.oauth.AuthorizationCodeState;
import org.mule.runtime.extension.api.connectivity.oauth.OAuthGrantType;
import org.mule.runtime.module.extension.internal.runtime.connectivity.oauth.BaseOAuthConnectionProviderWrapper;
import org.mule.runtime.module.extension.internal.util.FieldSetter;
import org.mule.oauth.client.api.AuthorizationCodeOAuthDancer;
import org.mule.oauth.client.api.state.ResourceOwnerOAuthContext;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A {@link ReconnectableConnectionProviderWrapper} which makes sure that by the time the {@link ConnectionProvider#connect()}
 * method is invoked on the delegate, the authorization dance has been completed and the {@link AuthorizationCodeState} and
 * {@link OAuthCallbackValue} fields have been properly injected
 *
 * @since 4.0
 */
public class AuthorizationCodeConnectionProviderWrapper<C> extends BaseOAuthConnectionProviderWrapper<C>
    implements NoConnectivityTest {

  private final AuthorizationCodeConfig oauthConfig;
  private final AuthorizationCodeOAuthHandler oauthHandler;
  private final FieldSetter<Object, Object> authCodeStateSetter;
  private final RunOnce dance;
  private Supplier<Boolean> forceInvalidateStatusRetrievalSupplier;

  private AuthorizationCodeOAuthDancer dancer;

  public AuthorizationCodeConnectionProviderWrapper(ConnectionProvider<C> delegate,
                                                    AuthorizationCodeConfig oauthConfig,
                                                    Map<Field, String> callbackValues,
                                                    AuthorizationCodeOAuthHandler oauthHandler,
                                                    ReconnectionConfig reconnectionConfig,
                                                    Supplier<Boolean> forceInvalidateStatusRetrievalSupplier) {
    super(delegate, reconnectionConfig, callbackValues);
    this.oauthConfig = oauthConfig;
    this.oauthHandler = oauthHandler;
    authCodeStateSetter = resolveOauthStateSetter(oauthConfig);
    dance = Once.of(this::updateAuthState);
    this.forceInvalidateStatusRetrievalSupplier = forceInvalidateStatusRetrievalSupplier;
  }

  @Override
  public C connect() throws ConnectionException {
    dance.runOnce();
    return super.connect();
  }

  private void updateAuthState() {
    final Object delegate = getDelegateForInjection();
    ResourceOwnerOAuthContext context = getContext();
    authCodeStateSetter
        .set(delegate, new UpdatingAuthorizationCodeState(oauthConfig,
                                                          dancer,
                                                          context,
                                                          updatedContext -> updateOAuthParameters(delegate,
                                                                                                  callbackValues,
                                                                                                  updatedContext),
                                                          forceInvalidateStatusRetrievalSupplier.get()));
    updateOAuthParameters(delegate, callbackValues, context);
  }

  @Override
  public void refreshToken(String resourceOwnerId) {
    oauthHandler.refreshToken(oauthConfig.getOwnerConfigName(), resourceOwnerId);
  }

  @Override
  public void invalidate(String resourceOwnerId) {
    oauthHandler.setForceInvalidateStatusRetrieval(forceInvalidateStatusRetrievalSupplier.get());
    oauthHandler.invalidate(oauthConfig.getOwnerConfigName(), resourceOwnerId);
  }

  @Override
  public OAuthGrantType getGrantType() {
    return oauthConfig.getGrantType();
  }

  @Override
  protected ResourceOwnerOAuthContext getContext() {
    return oauthHandler.getOAuthContext(oauthConfig)
        .orElseThrow(() -> new IllegalArgumentException("OAuth authorization dance not yet performed for resourceOwnerId "
            + oauthConfig.getResourceOwnerId()));
  }

  @Override
  public void start() throws MuleException {
    dancer = oauthHandler.register(oauthConfig);
    super.start();
  }

  protected FieldSetter<Object, Object> resolveOauthStateSetter(AuthorizationCodeConfig oauthConfig) {
    return getOAuthStateSetter(getDelegateForInjection(), AUTHORIZATION_CODE_STATE_INTERFACES, oauthConfig.getGrantType());
  }
}
