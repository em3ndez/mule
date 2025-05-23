/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.util.journal;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

import org.mule.runtime.core.internal.serialization.JavaObjectSerializer;
import org.mule.runtime.core.internal.util.journal.queue.LocalQueueTxJournalEntry;
import org.mule.runtime.core.internal.util.journal.queue.LocalTxQueueTransactionJournal;
import org.mule.tck.junit4.AbstractMuleTestCase;

import java.io.File;
import java.io.Serializable;
import java.util.Collection;

import org.apache.commons.lang3.RandomStringUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TransactionJournalFileTestCase extends AbstractMuleTestCase {

  private static final long KB_500 = 500 * 1024l;

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void largeQueueName() throws Exception {
    final String queueName = RandomStringUtils.insecure().nextAlphanumeric(129);
    final Serializable payload = "Hello World!";
    final int txId = 1;

    TransactionJournalFile<Integer, LocalQueueTxJournalEntry> journal = openJournal();
    journal.logOperation(new LocalQueueTxJournalEntry(txId, (byte) 6, queueName, payload));
    journal.close();

    journal = openJournal();

    Collection<LocalQueueTxJournalEntry> entries = journal.getLogEntries(txId);
    assertThat(entries, is(notNullValue()));
    assertThat(entries.size(), equalTo(1));

    LocalQueueTxJournalEntry entry = entries.iterator().next();
    assertThat(entry.getQueueName(), equalTo(queueName));
    assertThat(entry.getValue(), equalTo(payload));
  }

  private TransactionJournalFile<Integer, LocalQueueTxJournalEntry> openJournal() {
    File journalFile = new File(temporaryFolder.getRoot(), "journal");
    JournalEntrySerializer<Integer, LocalQueueTxJournalEntry> serializer = LocalTxQueueTransactionJournal
        .createLocalTxQueueJournalEntrySerializer(new JavaObjectSerializer(this.getClass().getClassLoader())
            .getInternalProtocol());

    return new TransactionJournalFile<>(journalFile, serializer, journalEntry -> false, KB_500);
  }

}
