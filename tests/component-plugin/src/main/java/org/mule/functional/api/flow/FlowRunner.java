/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.functional.api.flow;

import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.startIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.stopIfNeeded;
import static org.mule.runtime.core.privileged.execution.TransactionalExecutionTemplate.createTransactionalExecutionTemplate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;

import org.mule.runtime.api.artifact.Registry;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.scheduler.Scheduler;
import org.mule.runtime.api.scheduler.SchedulerService;
import org.mule.runtime.api.streaming.Cursor;
import org.mule.runtime.api.util.Reference;
import org.mule.runtime.core.api.construct.Flow;
import org.mule.runtime.core.api.construct.FlowConstruct;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.api.execution.ExecutionCallback;
import org.mule.runtime.core.api.execution.ExecutionTemplate;
import org.mule.runtime.core.api.lifecycle.LifecycleStateEnabled;
import org.mule.runtime.core.api.transaction.Transaction;
import org.mule.runtime.core.internal.transaction.MuleTransactionConfig;
import org.mule.runtime.core.privileged.exception.EventProcessingException;
import org.mule.runtime.core.privileged.transaction.TransactionConfig;
import org.mule.runtime.core.privileged.transaction.TransactionFactory;
import org.mule.tck.junit4.matcher.ErrorTypeMatcher;
import org.mule.tck.junit4.matcher.EventMatcher;
import org.mule.tck.processor.FlowAssert;
import org.mule.tck.testmodels.mule.TestTransactionFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import org.hamcrest.Matcher;

import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * Provides a fluent API for running events through flows.
 * <p>
 * This runner is <b>not</b> thread-safe.
 */
public class FlowRunner extends FlowConstructRunner<FlowRunner> {

  private final String flowName;
  private final Flow flow;

  private ExecutionTemplate<CoreEvent> txExecutionTemplate = callback -> callback.process();

  private final Function<CoreEvent, CoreEvent> responseEventTransformer = input -> input;

  private Scheduler scheduler;

  private final CompletableFuture<Void> externalCompletionCallback = new CompletableFuture<>();

  private boolean wasFlowOriginallyStopped = false;

  /**
   * Initializes this flow runner.
   *
   * @param registry the registry for the currently running test
   * @param flowName the name of the flow to run events through
   */
  public FlowRunner(Registry registry, String flowName) {
    super(registry);
    this.flowName = flowName;

    flow = (Flow) getFlowConstruct();
    if (((LifecycleStateEnabled) flow).getLifecycleState().isStopped()) {
      wasFlowOriginallyStopped = true;
      try {
        startIfNeeded(flow);
      } catch (MuleException e) {
        throw new MuleRuntimeException(e);
      }
    }
  }

  /**
   * Configures the flow to run inside a transaction.
   *
   * @param action  The action to do at the start of the transactional block. See {@link TransactionConfig} constants.
   * @param factory See {@link MuleTransactionConfig#setFactory(TransactionFactory)}.
   * @return this {@link FlowRunner}
   */
  public FlowRunner transactionally(TransactionConfigEnum action, Transaction transaction) {
    txExecutionTemplate = createTransactionalExecutionTemplate(registry,
                                                               action.getAction(),
                                                               new TestTransactionFactory(transaction));

    return this;
  }

  /**
   * Makes all open {@link Cursor cursors} to not be closed when the executed flow is finished but when the test is disposed
   *
   * @return {@code this} {@link FlowRunner}
   */
  public FlowRunner keepStreamsOpen() {
    eventBuilder.setExternalCompletionCallback(externalCompletionCallback);
    return this;
  }

  /**
   * Run {@link Flow} as a task of a given {@link Scheduler}.
   *
   * @param scheduler the scheduler to use to run the {@link Flow}.
   * @return this {@link FlowRunner}
   * @see {@link SchedulerService}
   */
  public FlowRunner withScheduler(Scheduler scheduler) {
    this.scheduler = scheduler;
    return this;
  }

  /**
   * Runs the specified flow with the provided event and configuration, and performs a {@link FlowAssert#verify(String))}
   * afterwards.
   * <p>
   * If this is called multiple times, the <b>same</b> event will be sent. To force the creation of a new event, use
   * {@link #reset()}.
   *
   * @return the resulting <code>MuleEvent</code>
   * @throws Exception
   */
  public CoreEvent run() throws Exception {
    return runAndVerify(flowName);
  }

  /**
   * Runs the specified flow with the provided event and configuration.
   * <p>
   * If this is called multiple times, the <b>same</b> event will be sent. To force the creation of a new event, use
   * {@link #reset()}.
   *
   * @return the resulting <code>MuleEvent</code>
   * @throws Exception
   */
  public CoreEvent runNoVerify() throws Exception {
    return runAndVerify(new String[] {});
  }

  /**
   * Runs the specified flow with the provided event and configuration, and performs a {@link FlowAssert#verify(String))} for each
   * {@code flowNamesToVerify} afterwards.
   * <p>
   * If this is called multiple times, the <b>same</b> event will be sent. To force the creation of a new event, use
   * {@link #reset()}.
   *
   * @param flowNamesToVerify the names of the flows to {@link FlowAssert#verify(String))} afterwards.
   * @return the resulting <code>MuleEvent</code>
   * @throws Exception
   */
  public CoreEvent runAndVerify(String... flowNamesToVerify) throws Exception {
    CoreEvent response;
    if (scheduler == null) {
      response = txExecutionTemplate.execute(getFlowRunCallback());
    } else {
      try {
        response = scheduler.submit(() -> txExecutionTemplate.execute(getFlowRunCallback())).get();
      } catch (ExecutionException executionException) {
        Throwable cause = executionException.getCause();
        throw cause instanceof Exception ? (Exception) cause : new RuntimeException(cause);
      }
    }
    verify(flowNamesToVerify);
    return responseEventTransformer.apply(response);
  }

  /**
   * Dispatches to the specified flow with the provided event and configuration, and performs a {@link FlowAssert#verify(String))}
   * afterwards.
   * <p>
   * If this is called multiple times, the <b>same</b> event will be sent. To force the creation of a new event, use
   * {@link #reset()}.
   * <p>
   * Dispatch behaves differently to {@link FlowRunner#run()} in that it does not propagate any exceptions to the test case or
   * return a result.
   */
  public void dispatch() throws Exception {
    try {
      txExecutionTemplate.execute(getFlowDispatchCallback());
    } catch (Exception e) {
      // Ignore
    }
    FlowAssert.verify(flowName);
  }

  /**
   * Dispatches to the specified flow with the provided event and configuration in a new IO thread, and performs a
   * {@link FlowAssert#verify(String))} afterwards.
   * <p>
   * If this is called multiple times, the <b>same</b> event will be sent. To force the creation of a new event, use
   * {@link #reset()}.
   * <p>
   * Dispatch behaves differently to {@link FlowRunner#run()} in that it does not propagate any exceptions to the test case or
   * return a result.
   */
  public void dispatchAsync(Scheduler scheduler) throws Exception {
    this.scheduler = scheduler;
    try {
      scheduler.submit(() -> txExecutionTemplate.execute(getFlowDispatchCallback()));
    } catch (Exception e) {
      // Ignore
    }
    FlowAssert.verify(flowName);
  }

  private ExecutionCallback<CoreEvent> getFlowRunCallback() {
    return () -> {
      Reference<FluxSink<CoreEvent>> sinkReference = new Reference<>(null);

      CoreEvent result;
      try {
        result = Flux.<CoreEvent>create(fluxSink -> {
          fluxSink.next(getOrBuildEvent());
          sinkReference.set(fluxSink);
        }).transform(flow::apply).blockFirst();
      } catch (RuntimeException ex) {
        if (ex.getCause() instanceof MuleException) {
          throw (MuleException) ex.getCause();
        } else {
          throw ex;
        }
      }

      sinkReference.get().complete();
      return result;
    };
  }

  private ExecutionCallback<CoreEvent> getFlowDispatchCallback() {
    return () -> {
      Reference<FluxSink<CoreEvent>> sinkReference = new Reference<>(null);

      try {
        Flux.<CoreEvent>create(fluxSink -> {
          fluxSink.next(getOrBuildEvent());
          sinkReference.set(fluxSink);
        }).transform(flow::apply).blockFirst();
      } catch (RuntimeException ex) {
        if (ex.getCause() instanceof MuleException) {
          throw (MuleException) ex.getCause();
        } else {
          throw ex;
        }
      }

      sinkReference.get().complete();
      return null;
    };
  }

  /**
   * Verifies asserts on flowNamesToVerify
   *
   * @param flowNamesToVerify
   * @throws Exception
   */
  private void verify(String... flowNamesToVerify) throws Exception {
    for (String flowNameToVerify : flowNamesToVerify) {
      FlowAssert.verify(flowNameToVerify);
    }
  }

  /**
   * Runs the specified flow with the provided event and configuration expecting a failure. Will fail if there's no failure
   * running the flow.
   *
   * @return the processing exception return by the flow
   * @throws Exception
   */
  public Exception runExpectingException() throws Exception {
    try {
      runNoVerify();
      fail("Flow executed successfully. Expecting exception");
      return null;
    } catch (EventProcessingException e) {
      verify(getFlowConstructName());
      return e;
    }
  }

  /**
   * Runs the specified flow with the provided event and configuration expecting a failure with an error type that matches the
   * given {@code matcher}.
   * <p>
   * Will fail if there's no failure running the flow.
   */
  public void runExpectingException(ErrorTypeMatcher matcher) throws Exception {
    try {
      runNoVerify();
      fail("Flow executed successfully. Expecting exception");
    } catch (EventProcessingException e) {
      verify(getFlowConstructName());
      assertThat(e.getEvent().getError().get().getErrorType(), matcher);
    }
  }

  /**
   * Runs the specified flow with the provided event and configuration expecting a failure with a cause that matches the given
   * {@code matcher}.
   * <p>
   * Will fail if there's no failure running the flow.
   */
  public void runExpectingException(Matcher<Throwable> causeMatcher) throws Exception {
    try {
      runNoVerify();
      fail("Flow executed successfully. Expecting exception");
    } catch (EventProcessingException e) {
      verify(getFlowConstructName());
      assertThat(e.getEvent().getError().get().getCause(), causeMatcher);
    }
  }

  /**
   * Runs the specified flow with the provided event and configuration expecting a failure with an {@link CoreEvent} that matches
   * the given {@code errorEventMatcher}.
   * <p>
   * Will fail if there's no failure running the flow.
   */
  public void runExpectingException(EventMatcher errorEventMatcher) throws Exception {
    try {
      runNoVerify();
      fail("Flow executed successfully. Expecting exception");
    } catch (EventProcessingException e) {
      verify(getFlowConstructName());
      assertThat(e.getEvent(), errorEventMatcher);
    }
  }

  /**
   * Runs the specified flow with the provided event and configuration expecting a failure with an {@link CoreEvent} that matches
   * the given {@code errorEventMatcher}.
   * <p>
   * Will fail if there's no failure running the flow.
   */
  public void runExpectingException(ErrorTypeMatcher matcher, Matcher<CoreEvent> errorEventMatcher) throws Exception {
    try {
      runNoVerify();
      fail("Flow executed successfully. Expecting exception");
    } catch (EventProcessingException e) {
      verify(getFlowConstructName());
      assertThat(e.getEvent().getError().get().getErrorType(), matcher);
      assertThat(e.getEvent(), errorEventMatcher);
    }
  }

  /**
   * Runs the specified flow with the provided event and configuration expecting a failure with an {@link CoreEvent} that matches
   * the given {@code errorEventMatcher}.
   * <p>
   * Will fail if there's no failure running the flow.
   */
  public void runExpectingException(Matcher<Throwable> causeMatcher, Matcher<CoreEvent> errorEventMatcher) throws Exception {
    try {
      runNoVerify();
      fail("Flow executed successfully. Expecting exception");
    } catch (EventProcessingException e) {
      verify(getFlowConstructName());
      assertThat(e.getEvent().getError().get().getCause(), causeMatcher);
      assertThat(e.getEvent(), errorEventMatcher);
    }
  }

  @Override
  public String getFlowConstructName() {
    return flowName;
  }

  @Override
  public void dispose() {
    if (scheduler != null) {
      scheduler.stop();
    }

    externalCompletionCallback.complete(null);
    super.dispose();

    if (wasFlowOriginallyStopped) {
      try {
        stopIfNeeded(flow);
      } catch (MuleException e) {
        throw new MuleRuntimeException(e);
      }
    }
  }

  @Override
  protected FlowConstruct getFlowConstruct() {
    if (flow != null) {
      return flow;
    } else {
      return super.getFlowConstruct();
    }
  }

}
