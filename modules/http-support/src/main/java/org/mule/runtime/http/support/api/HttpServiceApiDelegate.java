/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.http.support.api;

import org.mule.runtime.http.api.HttpService;
import org.mule.runtime.http.api.client.HttpClientConfiguration;
import org.mule.runtime.http.api.server.HttpServerConfiguration;
import org.mule.runtime.http.api.server.ServerCreationException;
import org.mule.runtime.http.support.internal.client.HttpClientConfigToBuilder;
import org.mule.runtime.http.support.internal.client.HttpClientWrapper;
import org.mule.runtime.http.support.internal.message.HttpEntityFactoryImpl;
import org.mule.runtime.http.support.internal.message.HttpRequestBuilderWrapper;
import org.mule.runtime.http.support.internal.message.HttpResponseBuilderWrapper;
import org.mule.runtime.http.support.internal.server.HttpServerConfigToBuilder;
import org.mule.runtime.http.support.internal.server.HttpServerWrapper;
import org.mule.sdk.api.http.client.ClientCreationException;
import org.mule.sdk.api.http.client.HttpClient;
import org.mule.sdk.api.http.client.HttpClientConfig;
import org.mule.sdk.api.http.domain.entity.HttpEntityFactory;
import org.mule.sdk.api.http.domain.message.request.HttpRequestBuilder;
import org.mule.sdk.api.http.domain.message.response.HttpResponse;
import org.mule.sdk.api.http.domain.message.response.HttpResponseBuilder;
import org.mule.sdk.api.http.server.HttpServer;
import org.mule.sdk.api.http.server.HttpServerConfig;

import java.util.Optional;
import java.util.function.Consumer;

import jakarta.inject.Inject;

public class HttpServiceApiDelegate implements org.mule.sdk.api.http.HttpService {

  private final HttpEntityFactory httpEntityFactory = new HttpEntityFactoryImpl();

  private HttpService httpService;

  @Inject
  public void setHttpService(Optional<HttpService> httpService) {
    this.httpService = httpService.orElse(null);
  }

  @Override
  public HttpClient client(Consumer<HttpClientConfig> configCallback) throws ClientCreationException {
    if (httpService == null) {
      throw new ClientCreationException("There is no implementation of HttpService available");
    }
    var builder = new HttpClientConfiguration.Builder();
    var configurer = new HttpClientConfigToBuilder(builder);
    configCallback.accept(configurer);
    HttpClientConfiguration configuration = builder.build();
    try {
      return new HttpClientWrapper(httpService.getClientFactory().create(configuration));
    } catch (Exception e) {
      throw new ClientCreationException("Couldn't create client", e);
    }
  }

  @Override
  public HttpServer server(Consumer<HttpServerConfig> configCallback)
      throws org.mule.sdk.api.http.server.ServerCreationException {
    if (httpService == null) {
      throw new org.mule.sdk.api.http.server.ServerCreationException("There is no implementation of HttpService available");
    }
    var builder = new HttpServerConfiguration.Builder();
    var configurer = new HttpServerConfigToBuilder(builder);
    configCallback.accept(configurer);
    HttpServerConfiguration configuration = builder.build();
    try {
      return new HttpServerWrapper(httpService.getServerFactory().create(configuration));
    } catch (ServerCreationException e) {
      throw new org.mule.sdk.api.http.server.ServerCreationException(e.getMessage(), e);
    }
  }

  @Override
  public HttpResponseBuilder responseBuilder() {
    return new HttpResponseBuilderWrapper();
  }

  @Override
  public HttpResponseBuilder responseBuilder(HttpResponse original) {
    return responseBuilder().statusCode(original.getStatusCode()).reasonPhrase(original.getReasonPhrase());
  }

  @Override
  public HttpRequestBuilder requestBuilder() {
    return new HttpRequestBuilderWrapper();
  }

  @Override
  public HttpRequestBuilder requestBuilder(boolean preserveHeaderCase) {
    return new HttpRequestBuilderWrapper(preserveHeaderCase);
  }

  @Override
  public HttpEntityFactory entityFactory() {
    return httpEntityFactory;
  }
}
