/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.runtime.source;

import static java.util.Collections.emptyMap;

import org.mule.runtime.api.component.execution.CompletableCallback;
import org.mule.runtime.api.functional.Either;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.privileged.exception.MessagingException;

import java.util.Map;

/**
 * {@code SourceCompletionHandler} that does nothing.
 *
 * @since 4.0
 */
public class NullSourceCompletionHandler implements SourceCompletionHandler {

  @Override
  public void onCompletion(CoreEvent event, Map<String, Object> parameters, CompletableCallback<Void> callback) {
    callback.complete(null);
  }

  @Override
  public void onFailure(MessagingException exception, Map<String, Object> parameters, CompletableCallback<Void> callback) {
    callback.error(exception);
  }

  @Override
  public void onTerminate(Either<MessagingException, CoreEvent> eventOrException) {
    // Nothing to do.
  }

  @Override
  public Map<String, Object> createResponseParameters(CoreEvent event) {
    return emptyMap();
  }

  @Override
  public Map<String, Object> createFailureResponseParameters(CoreEvent event) {
    return emptyMap();
  }
}
