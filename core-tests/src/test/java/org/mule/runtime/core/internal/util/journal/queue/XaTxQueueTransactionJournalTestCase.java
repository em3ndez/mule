/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.util.journal.queue;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.mule.runtime.api.serialization.ObjectSerializer;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.internal.serialization.JavaObjectSerializer;
import org.mule.runtime.core.internal.util.queue.DefaultQueueStore;
import org.mule.tck.junit4.AbstractMuleTestCase;

import java.util.Collection;
import java.util.Iterator;

import javax.transaction.xa.Xid;

import com.google.common.collect.Multimap;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class XaTxQueueTransactionJournalTestCase extends AbstractMuleTestCase {

  public static final Xid TX_ID = new MuleXid(9, new byte[] {1, 2, 3, 4}, new byte[] {5, 6, 7, 8});

  public static final String QUEUE_NAME = "queueName";
  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  private ObjectSerializer serializer;

  private final DefaultQueueStore mockQueueInfo = mock(DefaultQueueStore.class, RETURNS_DEEP_STUBS);

  @Before
  public void setUp() {
    serializer = new JavaObjectSerializer(this.getClass().getClassLoader());
  }

  @Before
  public void setUpMocks() {
    when(mockQueueInfo.getName()).thenReturn(QUEUE_NAME);
  }

  @Test
  public void logAddAndRetrieve() throws Exception {
    XaTxQueueTransactionJournal transactionJournal = new XaTxQueueTransactionJournal(temporaryFolder.getRoot().getAbsolutePath(),
                                                                                     serializer
                                                                                         .getInternalProtocol());
    transactionJournal.logAdd(TX_ID, mockQueueInfo, testEvent());
    transactionJournal.close();
    transactionJournal = new XaTxQueueTransactionJournal(temporaryFolder.getRoot().getAbsolutePath(),
                                                         serializer.getInternalProtocol());
    Multimap<Xid, XaQueueTxJournalEntry> allEntries = transactionJournal.getAllLogEntries();
    assertThat(allEntries.size(), is(1));
    assertThat(allEntries.get(TX_ID).size(), is(1));
    XaQueueTxJournalEntry logEntry = allEntries.get(TX_ID).iterator().next();
    assertThat(logEntry.getQueueName(), is(QUEUE_NAME));
    assertThat(((CoreEvent) logEntry.getValue()).getMessage().getPayload().getValue(), is(TEST_PAYLOAD));
    assertThat(logEntry.isAdd(), is(true));
  }

  @Test
  public void logAddFirstAndRetrieve() throws Exception {
    XaTxQueueTransactionJournal transactionJournal =
        new XaTxQueueTransactionJournal(temporaryFolder.getRoot().getAbsolutePath(),
                                        serializer.getInternalProtocol());
    transactionJournal.clear();

    transactionJournal.logAddFirst(TX_ID, mockQueueInfo, testEvent());
    transactionJournal.close();
    transactionJournal = new XaTxQueueTransactionJournal(temporaryFolder.getRoot().getAbsolutePath(),
                                                         serializer.getInternalProtocol());
    Multimap<Xid, XaQueueTxJournalEntry> allEntries = transactionJournal.getAllLogEntries();
    assertThat(allEntries.size(), is(1));
    assertThat(allEntries.get(TX_ID).size(), is(1));
    XaQueueTxJournalEntry journalEntry = allEntries.get(TX_ID).iterator().next();
    assertThat(journalEntry.getQueueName(), is(QUEUE_NAME));
    assertThat(((CoreEvent) journalEntry.getValue()).getMessage().getPayload().getValue(), is(TEST_PAYLOAD));
    assertThat(journalEntry.isAddFirst(), is(true));
  }

  @Test
  public void logRemoveAndRetrieve() throws Exception {
    XaTxQueueTransactionJournal transactionJournal =
        new XaTxQueueTransactionJournal(temporaryFolder.getRoot().getAbsolutePath(),
                                        serializer.getInternalProtocol());
    transactionJournal.clear();

    transactionJournal.logRemove(TX_ID, mockQueueInfo, testEvent());
    transactionJournal.close();
    transactionJournal = new XaTxQueueTransactionJournal(temporaryFolder.getRoot().getAbsolutePath(),
                                                         serializer.getInternalProtocol());
    Multimap<Xid, XaQueueTxJournalEntry> allEntries = transactionJournal.getAllLogEntries();
    assertThat(allEntries.size(), is(1));
    assertThat(allEntries.get(TX_ID).size(), is(1));
    XaQueueTxJournalEntry journalEntry = allEntries.get(TX_ID).iterator().next();
    assertThat(journalEntry.getQueueName(), is(QUEUE_NAME));
    assertThat(((CoreEvent) journalEntry.getValue()).getMessage().getPayload().getValue(), is(TEST_PAYLOAD));
    assertThat(journalEntry.isRemove(), is(true));
  }

  @Test
  public void logCommitAndRetrieve() {
    XaTxQueueTransactionJournal transactionJournal =
        new XaTxQueueTransactionJournal(temporaryFolder.getRoot().getAbsolutePath(),
                                        serializer.getInternalProtocol());
    transactionJournal.clear();

    transactionJournal.logCommit(TX_ID);
    transactionJournal.close();
    transactionJournal = new XaTxQueueTransactionJournal(temporaryFolder.getRoot().getAbsolutePath(),
                                                         serializer.getInternalProtocol());
    Multimap<Xid, XaQueueTxJournalEntry> allEntries = transactionJournal.getAllLogEntries();
    assertThat(allEntries.size(), is(0));
  }

  @Test
  public void logRollbackAndRetrieve() {
    XaTxQueueTransactionJournal transactionJournal =
        new XaTxQueueTransactionJournal(temporaryFolder.getRoot().getAbsolutePath(),
                                        serializer.getInternalProtocol());
    transactionJournal.clear();

    transactionJournal.logRollback(TX_ID);
    transactionJournal.close();
    transactionJournal = new XaTxQueueTransactionJournal(temporaryFolder.getRoot().getAbsolutePath(),
                                                         serializer.getInternalProtocol());
    Multimap<Xid, XaQueueTxJournalEntry> allEntries = transactionJournal.getAllLogEntries();
    assertThat(allEntries.size(), is(0));
  }

  @Test
  public void logSeveralAddsThenCommitAndRetrieve() throws Exception {
    XaTxQueueTransactionJournal transactionJournal =
        new XaTxQueueTransactionJournal(temporaryFolder.getRoot().getAbsolutePath(),
                                        serializer.getInternalProtocol());
    transactionJournal.clear();

    int numberOfOffers = 1000;
    for (int i = 0; i < numberOfOffers; i++) {
      transactionJournal.logAdd(TX_ID, mockQueueInfo, testEvent());
    }
    transactionJournal.logCommit(TX_ID);
    transactionJournal.close();
    transactionJournal = new XaTxQueueTransactionJournal(temporaryFolder.getRoot().getAbsolutePath(),
                                                         serializer.getInternalProtocol());
    Multimap<Xid, XaQueueTxJournalEntry> allEntries = transactionJournal.getAllLogEntries();
    assertThat(allEntries.size(), is(0));
  }

  @Test
  public void logSeveralAddsThenRetrieveAndCommit() throws Exception {
    XaTxQueueTransactionJournal transactionJournal =
        new XaTxQueueTransactionJournal(temporaryFolder.getRoot().getAbsolutePath(),
                                        serializer.getInternalProtocol());
    transactionJournal.clear();

    int numberOfOffers = 1000;
    for (int i = 0; i < numberOfOffers; i++) {
      transactionJournal.logAdd(TX_ID, mockQueueInfo, testEvent());
    }
    transactionJournal.close();
    transactionJournal = new XaTxQueueTransactionJournal(temporaryFolder.getRoot().getAbsolutePath(),
                                                         serializer.getInternalProtocol());
    transactionJournal.logCommit(TX_ID);
    Multimap<Xid, XaQueueTxJournalEntry> allEntries = transactionJournal.getAllLogEntries();
    assertThat(allEntries.size(), is(0));
  }

  @Test
  public void logSeveralAddsAndRetrieve() throws Exception {
    XaTxQueueTransactionJournal transactionJournal =
        new XaTxQueueTransactionJournal(temporaryFolder.getRoot().getAbsolutePath(),
                                        serializer.getInternalProtocol());
    transactionJournal.clear();

    int numberOfOffers = 1000;
    for (int i = 0; i < numberOfOffers; i++) {
      transactionJournal.logAdd(TX_ID, mockQueueInfo, testEvent());
    }
    transactionJournal.close();
    transactionJournal = new XaTxQueueTransactionJournal(temporaryFolder.getRoot().getAbsolutePath(),
                                                         serializer.getInternalProtocol());
    Multimap<Xid, XaQueueTxJournalEntry> allEntries = transactionJournal.getAllLogEntries();
    assertThat(allEntries.size(), is(numberOfOffers));
    assertThat(allEntries.get(TX_ID).size(), is(numberOfOffers));
    XaQueueTxJournalEntry journalEntry = allEntries.get(TX_ID).iterator().next();
    assertThat(journalEntry.getQueueName(), is(QUEUE_NAME));
    assertThat(((CoreEvent) journalEntry.getValue()).getMessage().getPayload().getValue(), is(TEST_PAYLOAD));
    assertThat(journalEntry.isAdd(), is(true));
  }

  @Test
  public void logAddAndPrepare() throws Exception {
    XaTxQueueTransactionJournal transactionJournal =
        new XaTxQueueTransactionJournal(temporaryFolder.getRoot().getAbsolutePath(),
                                        serializer.getInternalProtocol());
    transactionJournal.clear();

    transactionJournal.logAdd(TX_ID, mockQueueInfo, testEvent());
    transactionJournal.logPrepare(TX_ID);
    transactionJournal.close();
    transactionJournal = new XaTxQueueTransactionJournal(temporaryFolder.getRoot().getAbsolutePath(),
                                                         serializer.getInternalProtocol());
    Multimap<Xid, XaQueueTxJournalEntry> allEntries = transactionJournal.getAllLogEntries();
    assertThat(allEntries.size(), is(2));
    Collection<XaQueueTxJournalEntry> values = allEntries.values();
    assertThat(values.size(), is(2));
    Iterator<XaQueueTxJournalEntry> iterator = values.iterator();
    XaQueueTxJournalEntry addEntry = iterator.next();
    assertThat(addEntry.isAdd(), is(true));
    XaQueueTxJournalEntry prepareEntry = iterator.next();
    assertThat(prepareEntry.isPrepare(), is(true));
  }


}
