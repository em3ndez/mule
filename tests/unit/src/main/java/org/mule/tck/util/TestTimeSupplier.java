/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.tck.util;

import static java.time.Instant.ofEpochMilli;

import static org.mule.runtime.api.util.Preconditions.checkArgument;

import org.mule.runtime.api.time.TimeSupplier;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * A test {@link TimeSupplier} for externalizing the system time. It is initialised at a given initial {@link #timeInMillis} using
 * the {@link #TestTimeSupplier(long)} constructor. The value can be retrieved through the {@link #get()} method.
 * <p/>
 * The usefulness of ths class comes from the behavior provided by the {@link #move(long, TimeUnit)} method which allows changing
 * that value per the tests needs. After invoking this method, you can retrieved the modified value through the {@link #get()}
 * method, although there's no way to retrieve the original one after {@link #move(long, TimeUnit)} has been invoked.
 *
 * @since 4.0
 */
public class TestTimeSupplier implements TimeSupplier {

  private volatile long timeInMillis;

  /**
   * Creates a new instance
   *
   * @param timeInMillis time in milliseconds to be supplied until its modified by {@link #move(long, TimeUnit)}
   */
  public TestTimeSupplier(long timeInMillis) {
    this.timeInMillis = timeInMillis;
  }

  /**
   * Returns the current virtualized time in milliseconds
   */
  @Override
  public synchronized Long get() {
    return timeInMillis;
  }

  @Override
  public synchronized long getAsLong() {
    return timeInMillis;
  }

  @Override
  public Instant getAsInstant() {
    return ofEpochMilli(timeInMillis);
  }

  /**
   * Moves the current {@link #timeInMillis} by the given {@code time} which is expressed in the given {@code unit}.
   * <p/>
   *
   * @param timeOffset the offset to be applied on {@link #timeInMillis}
   * @param unit       a {@link TimeUnit} which qualifies the {@code timeOffset}
   * @return the updated {@link #timeInMillis}
   * @throws IllegalArgumentException if {@code timeOffset} is negative
   */
  public synchronized long move(long timeOffset, TimeUnit unit) {
    checkArgument(timeOffset >= 0, "I told you not to go into the past McFly...");
    return this.timeInMillis += unit.toMillis(timeOffset);
  }
}
