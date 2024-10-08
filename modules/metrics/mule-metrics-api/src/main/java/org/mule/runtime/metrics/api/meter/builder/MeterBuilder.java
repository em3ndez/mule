/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.metrics.api.meter.builder;

import org.mule.runtime.metrics.api.meter.Meter;

/**
 * A builder for a {@link Meter}.
 *
 * @since 4.5.0
 */
public interface MeterBuilder {

  /**
   * No operation {@link MeterBuilder} implementation. It will always return a no operation {@link Meter} implementation.
   */
  MeterBuilder NO_OP = new MeterBuilder() {

    @Override
    public Meter build() {
      return Meter.NO_OP;
    }

    @Override
    public MeterBuilder withDescription(String description) {
      return this;
    }

    @Override
    public MeterBuilder withMeterAttribute(String key, String value) {
      return this;
    }
  };

  /**
   * @return the meter built.
   */
  Meter build();

  /**
   * @param description the description.
   *
   * @return the {@link MeterBuilder}
   */
  MeterBuilder withDescription(String description);

  /**
   * An attribute associated with the meter.
   *
   * @param key   the key.
   * @param value the value.
   *
   * @return the {@link MeterBuilder}
   */
  MeterBuilder withMeterAttribute(String key, String value);
}
