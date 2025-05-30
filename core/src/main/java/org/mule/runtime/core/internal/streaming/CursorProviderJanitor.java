/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.streaming;

import static org.slf4j.LoggerFactory.getLogger;

import org.mule.runtime.api.streaming.Cursor;
import org.mule.runtime.api.streaming.CursorProvider;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;

/**
 * Performs cleanup tasks for one particular {@link CursorProvider} passed in the constructor.
 * <p>
 * None of the methods in this class fail. Any exceptions are logged only.
 *
 * @since 4.2.0
 */
public class CursorProviderJanitor {

  private static final Logger LOGGER = getLogger(CursorProviderJanitor.class);

  CursorProvider<? extends Cursor> provider;
  private final AtomicInteger openCursorsCount;
  private final MutableStreamingStatistics statistics;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final AtomicBoolean released = new AtomicBoolean(false);

  /**
   * Creates a new instance
   *
   * @param provider         the {@link CursorProvider} which resources are freed
   * @param openCursorsCount an {@link AtomicInteger} to decrement each time a cursor is released
   * @param statistics       a {@link MutableStreamingStatistics}
   */
  public CursorProviderJanitor(CursorProvider<? extends Cursor> provider, AtomicInteger openCursorsCount,
                               MutableStreamingStatistics statistics) {
    this.provider = provider;
    this.openCursorsCount = openCursorsCount;
    this.statistics = statistics;
  }

  /**
   * Closes the underlying {@link CursorProvider}
   */
  public void close() {
    if (closed.compareAndSet(false, true)) {
      try {
        provider.close();
      } finally {
        if (statistics != null) {
          statistics.decrementOpenProviders();
        }
      }
    }
  }

  /**
   * Releases the resources of the underlying {@link CursorProvider}, including its {@link Cursor cursors}
   *
   * @return {@code false} if the resources had been released previously.
   */
  public final boolean releaseResources() {
    if (!released.compareAndSet(false, true)) {
      return false;
    }

    try {
      close();
    } catch (Exception e) {
      LOGGER.warn("Exception was found trying to close CursorProvider. Will try to release its resources anyway", e);
    }

    try {
      provider.releaseResources();
    } finally {
      if (statistics != null) {
        statistics.decrementOpenCursors(openCursorsCount.get());
      }
      provider = null;
    }
    return true;
  }

  /**
   * Releases the resources associated to the given {@code cursor}.
   *
   * @param cursor a {@link Cursor}
   */
  public void releaseCursor(Cursor cursor) {
    try {
      if (statistics != null) {
        statistics.decrementOpenCursors();
      }

      cursor.release();
      if (openCursorsCount.decrementAndGet() == 0 && provider != null && provider.isClosed()) {
        releaseResources();
      }
    } catch (Exception e) {
      LOGGER.warn("Exception was found trying to release cursor resources. Execution will continue", e);
    }
  }
}
