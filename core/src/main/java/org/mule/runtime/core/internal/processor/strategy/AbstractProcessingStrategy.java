/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.processor.strategy;

import static java.lang.System.currentTimeMillis;
import static java.util.function.Function.identity;
import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.mule.runtime.core.api.construct.BackPressureReason.EVENTS_ACCUMULATED;
import static org.mule.runtime.core.api.error.Errors.ComponentIdentifiers.Unhandleable.OVERLOAD;
import static org.mule.runtime.core.api.rx.Exceptions.propagateWrappingFatal;
import static org.mule.runtime.core.api.rx.Exceptions.unwrap;
import static org.mule.runtime.core.api.transaction.TransactionCoordination.isTransactionActive;
import static org.mule.runtime.core.internal.processor.strategy.AbstractStreamProcessingStrategyFactory.CORES;
import static reactor.util.concurrent.Queues.SMALL_BUFFER_SIZE;

import org.mule.runtime.api.exception.DefaultMuleException;
import org.mule.runtime.api.lifecycle.Disposable;
import org.mule.runtime.core.api.construct.BackPressureReason;
import org.mule.runtime.core.api.construct.FlowConstruct;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.api.processor.ReactiveProcessor;
import org.mule.runtime.core.api.processor.Sink;
import org.mule.runtime.core.api.processor.strategy.ProcessingStrategy;
import org.mule.runtime.core.privileged.exception.MessagingException;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;

import reactor.core.publisher.FluxSink;

/**
 * Abstract base {@link ProcessingStrategy} that creates a basic {@link Sink} that serializes events.
 */
public abstract class AbstractProcessingStrategy implements ProcessingStrategyAdapter {

  public static final String TRANSACTIONAL_ERROR_MESSAGE = "Unable to process a transactional flow asynchronously";
  public static final String PROCESSOR_SCHEDULER_CONTEXT_KEY = "mule.nb.processorScheduler";

  protected static final long SCHEDULER_BUSY_RETRY_INTERVAL_MS = 2;

  private Function<ScheduledExecutorService, ScheduledExecutorService> schedulerDecorator = identity();

  protected Consumer<CoreEvent> onEventConsumer = createDefaultOnEventConsumer();

  @Override
  public Sink createSink(FlowConstruct flowConstruct, ReactiveProcessor pipeline) {
    return new DirectSink(pipeline, createDefaultOnEventConsumer(), SMALL_BUFFER_SIZE);
  }

  @Override
  public void setOnEventConsumer(Consumer<CoreEvent> onEventConsumer) {
    this.onEventConsumer = onEventConsumer;
  }

  protected Consumer<CoreEvent> createDefaultOnEventConsumer() {
    return event -> {
      if (isTransactionActive()) {
        throw propagateWrappingFatal(new MessagingException(event,
                                                            new DefaultMuleException(createStaticMessage(TRANSACTIONAL_ERROR_MESSAGE))));
      }
    };
  }

  protected ScheduledExecutorService decorateScheduler(ScheduledExecutorService scheduler) {
    return schedulerDecorator.apply(scheduler);
  }

  @Override
  public Function<ScheduledExecutorService, ScheduledExecutorService> getSchedulerDecorator() {
    return schedulerDecorator;
  }

  @Override
  public void setSchedulerDecorator(Function<ScheduledExecutorService, ScheduledExecutorService> schedulerDecorator) {
    this.schedulerDecorator = schedulerDecorator;
  }

  /**
   * Extension of {@link Sink} using Reactor's {@link FluxSink} to accept events.
   */
  interface ReactorSink<E> extends Sink, Disposable {

    E intoSink(CoreEvent event);

    void prepareDispose();

  }

  /**
   * Implementation of {@link Sink} using Reactor's {@link FluxSink} to accept events.
   */
  static class DefaultReactorSink<E> implements ReactorSink<E> {

    private final FluxSink<E> fluxSink;
    private final Consumer<Long> disposer;
    private final Consumer<CoreEvent> onEventConsumer;
    private final int bufferSize;

    private long prepareDisposeTimestamp = -1;

    DefaultReactorSink(FluxSink<E> fluxSink, Consumer<Long> disposer,
                       Consumer<CoreEvent> onEventConsumer, int bufferSize) {
      this.fluxSink = fluxSink;
      this.disposer = disposer;
      this.onEventConsumer = onEventConsumer;
      this.bufferSize = bufferSize;
    }

    @Override
    public final void accept(CoreEvent event) {
      onEventConsumer.accept(event);
      fluxSink.next(intoSink(event));
    }

    @Override
    public final BackPressureReason emit(CoreEvent event) {
      onEventConsumer.accept(event);
      // Optimization to avoid using synchronized block for all emissions.
      // See: https://github.com/reactor/reactor-core/issues/1037
      long remainingCapacity = fluxSink.requestedFromDownstream();
      if (remainingCapacity == 0) {
        return EVENTS_ACCUMULATED;
      } else if (remainingCapacity > (bufferSize > CORES * 4 ? CORES : 0)) {
        // If there is sufficient room in buffer to significantly reduce change of concurrent emission when buffer is full then
        // emit without synchronized block.
        fluxSink.next(intoSink(event));
        return null;
      } else {
        // If there is very little room in buffer also emit but synchronized.
        synchronized (fluxSink) {
          if (remainingCapacity > 0) {
            fluxSink.next(intoSink(event));
            return null;
          } else {
            return EVENTS_ACCUMULATED;
          }
        }
      }
    }

    @Override
    public E intoSink(CoreEvent event) {
      return (E) event;
    }

    @Override
    public void prepareDispose() {
      prepareDisposeTimestamp = currentTimeMillis();
      fluxSink.complete();
    }

    @Override
    public final void dispose() {
      if (prepareDisposeTimestamp == -1) {
        fluxSink.complete();
        disposer.accept(currentTimeMillis());
      } else {
        disposer.accept(prepareDisposeTimestamp);
      }
    }

  }
}
