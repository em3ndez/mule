/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.util.journal.queue;

import org.mule.runtime.api.serialization.SerializationProtocol;
import org.mule.runtime.core.internal.util.journal.JournalEntry;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serializable;

/**
 * {@link JournalEntry} for a local queue transaction
 */
public class LocalQueueTxJournalEntry extends AbstractQueueTxJournalEntry<Integer> {

  public LocalQueueTxJournalEntry(int txId, byte operation, String queueName, Serializable value) {
    super(txId, operation, queueName, value);
  }

  protected LocalQueueTxJournalEntry(int txId, byte operation) {
    super(txId, operation);
  }

  public LocalQueueTxJournalEntry(DataInputStream inputStream, SerializationProtocol serializer) throws IOException {
    super(inputStream, serializer);
  }

  @Override
  protected Integer deserializeTxId(DataInputStream inputStream) throws IOException {
    return inputStream.readInt();
  }

  @Override
  protected void serializeTxId(DataOutputStream outputStream) throws IOException {
    outputStream.writeInt(getTxId());
  }

}

