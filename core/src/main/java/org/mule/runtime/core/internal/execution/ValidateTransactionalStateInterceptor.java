/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.execution;

import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;

import org.mule.runtime.core.api.config.i18n.CoreMessages;
import org.mule.runtime.core.api.execution.ExecutionCallback;
import org.mule.runtime.core.api.transaction.Transaction;
import org.mule.runtime.core.api.transaction.TransactionCoordination;
import org.mule.runtime.core.internal.transaction.xa.IllegalTransactionStateException;
import org.mule.runtime.core.privileged.transaction.TransactionConfig;

public class ValidateTransactionalStateInterceptor<T> implements ExecutionInterceptor<T> {

  private final ExecutionInterceptor<T> next;
  private final TransactionConfig transactionConfig;
  private boolean isCompatibility;

  public ValidateTransactionalStateInterceptor(ExecutionInterceptor<T> next, TransactionConfig transactionConfig,
                                               boolean isCompatibility) {
    this.next = next;
    this.transactionConfig = transactionConfig;
    this.isCompatibility = isCompatibility;
  }

  @Override
  public T execute(ExecutionCallback<T> callback, ExecutionContext executionContext) throws Exception {
    Transaction tx = TransactionCoordination.getInstance().getTransaction();
    if (transactionConfig.getAction() == TransactionConfig.ACTION_NEVER && tx != null) {
      throw new IllegalTransactionStateException(CoreMessages.transactionAvailableButActionIs("Never"));
    } else if (transactionConfig.getAction() == TransactionConfig.ACTION_ALWAYS_JOIN && tx == null) {
      throw new IllegalTransactionStateException(CoreMessages.transactionNotAvailableButActionIs("Always Join"));
    } else if (!isCompatibility && transactionConfig.getAction() == TransactionConfig.ACTION_ALWAYS_BEGIN && tx != null
        && !tx.isXA()) {
      throw new IllegalTransactionStateException(CoreMessages.transactionAvailableButActionIs("Always Begin")
          .setNextMessage(createStaticMessage("Non-XA transactions can't be nested.")));
    }
    return this.next.execute(callback, executionContext);
  }
}
