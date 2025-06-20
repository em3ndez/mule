/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.http.support.internal.server;

import static org.mule.test.allure.AllureConstants.HttpFeature.HTTP_FORWARD_COMPATIBILITY;

import static org.mockito.Mockito.verify;

import org.mule.sdk.api.http.server.async.ResponseStatusCallback;

import io.qameta.allure.Feature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@Feature(HTTP_FORWARD_COMPATIBILITY)
@ExtendWith(MockitoExtension.class)
class ResponseStatusCallbackWrapperTestCase {

  @Mock
  private ResponseStatusCallback mockCallback;

  private ResponseStatusCallbackWrapper callbackWrapper;

  @BeforeEach
  void setUp() {
    callbackWrapper = new ResponseStatusCallbackWrapper(mockCallback);
  }

  @Test
  void responseSendFailure() {
    var error = new RuntimeException("error");
    callbackWrapper.responseSendFailure(error);
    verify(mockCallback).responseSendFailure(error);
  }

  @Test
  void responseSendSuccessfully() {
    callbackWrapper.responseSendSuccessfully();
    verify(mockCallback).responseSendSuccessfully();
  }

  @Test
  void onErrorSendingResponse() {
    var error = new RuntimeException("error");
    callbackWrapper.onErrorSendingResponse(error);
    verify(mockCallback).onErrorSendingResponse(error);
  }
}
