/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.util.journal.queue;

import org.mule.runtime.api.serialization.SerializationProtocol;
import org.mule.runtime.core.internal.util.journal.JournalEntrySerializer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collection;

import javax.transaction.xa.Xid;

public class XaTxQueueTransactionJournal extends AbstractQueueTransactionJournal<Xid, XaQueueTxJournalEntry> {

  public XaTxQueueTransactionJournal(String logFilesDirectory, final SerializationProtocol serializer,
                                     Integer maximumFileSizeInMegabytes) {
    super(logFilesDirectory, new JournalEntrySerializer<Xid, XaQueueTxJournalEntry>() {

      @Override
      public XaQueueTxJournalEntry deserialize(DataInputStream inputStream) throws IOException {
        return new XaQueueTxJournalEntry(inputStream, serializer);
      }

      @Override
      public void serialize(XaQueueTxJournalEntry journalEntry, DataOutputStream dataOutputStream) {
        journalEntry.write(dataOutputStream, serializer);
      }
    }, maximumFileSizeInMegabytes);
  }

  public XaTxQueueTransactionJournal(String logFilesDirectory, final SerializationProtocol serializer) {
    this(logFilesDirectory, serializer, null);
  }

  @Override
  protected XaQueueTxJournalEntry createUpdateJournalEntry(Xid txId, byte operation, String queueName, Serializable serialize) {
    return new XaQueueTxJournalEntry(txId, operation, queueName, serialize);
  }

  @Override
  protected XaQueueTxJournalEntry createCheckpointJournalEntry(Xid txId, byte operation) {
    return new XaQueueTxJournalEntry(txId, operation);
  }

  public void logPrepare(Xid xid) {
    getJournal().logCheckpointOperation(createCheckpointJournalEntry(xid, AbstractQueueTxJournalEntry.Operation.PREPARE
        .getByteRepresentation()));
  }

  @Override
  public Collection<XaQueueTxJournalEntry> getLogEntriesForTx(Xid txId) {
    return super.getLogEntriesForTx(new MuleXid(txId));
  }


}
