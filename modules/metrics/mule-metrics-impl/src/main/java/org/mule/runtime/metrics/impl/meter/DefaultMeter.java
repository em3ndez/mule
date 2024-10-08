/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.metrics.impl.meter;

import org.mule.runtime.metrics.api.instrument.builder.LongCounterBuilder;
import org.mule.runtime.metrics.api.instrument.builder.LongUpDownCounterBuilder;
import org.mule.runtime.metrics.api.meter.Meter;
import org.mule.runtime.metrics.exporter.api.MeterExporter;
import org.mule.runtime.metrics.impl.instrument.DefaultLongCounter;
import org.mule.runtime.metrics.impl.instrument.DefaultLongUpDownCounter;
import org.mule.runtime.metrics.impl.instrument.repository.InstrumentRepository;
import org.mule.runtime.metrics.impl.meter.builder.MeterBuilderWithRepository;

import java.util.Map;
import java.util.function.BiConsumer;

/**
 * A default implementation of a {@link Meter}
 */
public class DefaultMeter implements Meter {

  private final MeterExporter meterExporter;
  private final Map<String, String> meterAttributes;

  public static MeterBuilderWithRepository builder(String meterName) {
    return new BaseMeterBuilder(meterName) {

      @Override
      protected DefaultMeter doBuild(String meterName, String description, MeterExporter meterExporter,
                                     Map<String, String> meterAttributes) {
        return new DefaultMeter(meterName, description, meterExporter, meterAttributes);
      }
    };
  }

  private final String meterName;
  private final String description;
  private final InstrumentRepository instrumentRepository = new InstrumentRepository();

  private DefaultMeter(String meterName, String description, MeterExporter meterExporter, Map<String, String> meterAttributes) {
    this.meterName = meterName;
    this.description = description;
    this.meterExporter = meterExporter;
    this.meterAttributes = meterAttributes;
    meterExporter.registerMeterToExport(this);
  }

  public String getName() {
    return meterName;
  }

  public String getDescription() {
    return description;
  }

  @Override
  public void forEachAttribute(BiConsumer<String, String> biConsumer) {
    meterAttributes.forEach(biConsumer);
  }

  @Override
  public LongUpDownCounterBuilder upDownCounterBuilder(String counterName) {
    return DefaultLongUpDownCounter.builder(counterName, this)
        .withInstrumentRepository(instrumentRepository)
        .withMeterExporter(meterExporter);
  }

  @Override
  public LongCounterBuilder counterBuilder(String counterName) {
    return DefaultLongCounter.builder(counterName, this)
        .withInstrumentRepository(instrumentRepository)
        .withMeterExporter(meterExporter);
  }

}
