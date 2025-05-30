/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.time;

import static java.lang.System.currentTimeMillis;
import static java.time.Instant.now;

import org.mule.runtime.api.time.TimeSupplier;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * A {@link Supplier} which provides the current system time in milliseconds.
 *
 * @since 4.0
 */
public class LocalTimeSupplier implements TimeSupplier {

  /**
   * Returns {@link System#currentTimeMillis()}
   *
   * @return the current time in milliseconds
   */
  @Override
  public Long get() {
    return currentTimeMillis();
  }

  @Override
  public long getAsLong() {
    return currentTimeMillis();
  }

  @Override
  public Instant getAsInstant() {
    return now();
  }
}
