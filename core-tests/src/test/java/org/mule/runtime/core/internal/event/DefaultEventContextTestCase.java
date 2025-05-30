/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.event;

import static org.mule.runtime.api.component.ComponentIdentifier.buildFromStringRepresentation;
import static org.mule.runtime.api.component.TypedComponentIdentifier.ComponentType.SOURCE;
import static org.mule.runtime.config.internal.dsl.utils.DslConstants.CORE_PREFIX;
import static org.mule.runtime.core.api.event.EventContextFactory.create;
import static org.mule.runtime.core.internal.event.DefaultEventContext.child;
import static org.mule.tck.probe.PollingProber.DEFAULT_POLLING_INTERVAL;
import static org.mule.tck.probe.PollingProber.probe;
import static org.mule.test.allure.AllureConstants.EventContextFeature.EVENT_CONTEXT;
import static org.mule.test.allure.AllureConstants.EventContextFeature.EventContextStory.RESPONSE_AND_COMPLETION_PUBLISHERS;

import static java.util.Arrays.asList;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.concurrent.Executors.newSingleThreadExecutor;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static reactor.core.publisher.Mono.from;
import static reactor.core.scheduler.Schedulers.fromExecutor;

import org.mule.runtime.api.component.TypedComponentIdentifier;
import org.mule.runtime.api.component.location.ComponentLocation;
import org.mule.runtime.api.event.EventContext;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.message.Message;
import org.mule.runtime.api.scheduler.Scheduler;
import org.mule.runtime.api.serialization.ObjectSerializer;
import org.mule.runtime.api.util.concurrent.Latch;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.api.event.EventContextFactory;
import org.mule.runtime.core.api.util.func.CheckedFunction;
import org.mule.runtime.core.api.util.func.CheckedSupplier;
import org.mule.runtime.core.internal.serialization.JavaObjectSerializer;
import org.mule.runtime.core.privileged.event.BaseEventContext;
import org.mule.runtime.dsl.api.component.config.DefaultComponentLocation.DefaultLocationPart;
import org.mule.tck.junit4.AbstractMuleContextTestCase;
import org.mule.tck.probe.JUnitLambdaProbe;
import org.mule.tck.probe.PollingProber;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import org.reactivestreams.Publisher;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import org.hamcrest.Matcher;

import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import io.qameta.allure.Issue;
import io.qameta.allure.Story;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * TODO MULE-14000 Create hamcrest matchers to assert EventContext state
 */
@Feature(EVENT_CONTEXT)
@Story(RESPONSE_AND_COMPLETION_PUBLISHERS)
@RunWith(Parameterized.class)
public class DefaultEventContextTestCase extends AbstractMuleContextTestCase {

  private static final int GC_POLLING_TIMEOUT = 10000;
  private static final String TEST_CORRELATION_ID = "Gracia al fulbo";

  private final Supplier<DefaultEventContext> context;
  private final Function<CompletableFuture<Void>, BaseEventContext> contextWithCompletion;
  private final Function<ComponentLocation, BaseEventContext> contextWithComponentLocation;

  private BaseEventContext parent;
  private BaseEventContext child;

  private final AtomicReference<CoreEvent> parentResultValue = new AtomicReference<>();
  private final AtomicReference<Throwable> parentErrorValue = new AtomicReference<>();
  private final AtomicBoolean parentCompletion = new AtomicBoolean();
  private final AtomicBoolean parentTerminated = new AtomicBoolean();

  private final AtomicReference<CoreEvent> childResultValue = new AtomicReference<>();
  private final AtomicReference<Throwable> childErrorValue = new AtomicReference<>();
  private final AtomicBoolean childCompletion = new AtomicBoolean();

  private ObjectSerializer serializer;

  public DefaultEventContextTestCase(String name, Supplier<DefaultEventContext> context,
                                     Function<CompletableFuture<Void>, BaseEventContext> contextWithCompletion,
                                     Function<ComponentLocation, BaseEventContext> contextWithComponentLocation) {
    this.context = context;
    this.contextWithCompletion = contextWithCompletion;
    this.contextWithComponentLocation = contextWithComponentLocation;
  }

  @Before
  public void setup() {
    this.parent = context.get();
    setupParentListeners(parent);

    this.serializer = new JavaObjectSerializer(this.getClass().getClassLoader());
  }

  private BaseEventContext addChild(BaseEventContext parent) {
    this.child = child(parent, empty());
    setupChildListeners(child);
    return child;
  }

  private void setupParentListeners(BaseEventContext parent) {
    parent.onResponse((event, throwable) -> {
      parentResultValue.set(event);
      parentErrorValue.set(throwable);
    });
    parent.onComplete((response, throwable) -> parentCompletion.set(true));
    parent.onTerminated((response, throwable) -> parentTerminated.set(true));
  }

  private void setupChildListeners(BaseEventContext child) {
    child.onResponse((event, throwable) -> {
      childResultValue.set(event);
      childErrorValue.set(throwable);
    });
    child.onTerminated((response, throwable) -> childCompletion.set(true));
  }

  @Parameters(name = "{0}")
  public static List<Object[]> data() {
    return asList(new Object[][] {
        {
            "FlowContext",
            (CheckedSupplier<EventContext>) () -> create(getTestFlow(muleContext), TEST_CONNECTOR_LOCATION),
            (CheckedFunction<CompletableFuture<Void>, EventContext>) externalCompletion -> create(getTestFlow(muleContext),
                                                                                                  muleContext
                                                                                                      .getEventContextService(),
                                                                                                  TEST_CONNECTOR_LOCATION,
                                                                                                  null,
                                                                                                  of(externalCompletion)),
            (CheckedFunction<ComponentLocation, EventContext>) location -> create(getTestFlow(muleContext),
                                                                                  muleContext.getEventContextService(), location)
        },
        {
            "RawContext",
            (CheckedSupplier<EventContext>) () -> create("id", DefaultEventContextTestCase.class.getName(),
                                                         TEST_CONNECTOR_LOCATION, null, empty()),
            (CheckedFunction<CompletableFuture<Void>, EventContext>) externalCompletion -> create("id",
                                                                                                  DefaultEventContextTestCase.class
                                                                                                      .getName(),
                                                                                                  TEST_CONNECTOR_LOCATION,
                                                                                                  null,
                                                                                                  of(externalCompletion)),
            (CheckedFunction<ComponentLocation, EventContext>) location -> create("id",
                                                                                  DefaultEventContextTestCase.class
                                                                                      .getName(),
                                                                                  location, null, empty())
        }
    });
  }

  @Test
  @Description("EventContext response publisher completes with value of result. Also given response publisher completed and there there are no child contexts the completion publisher also completes.")
  public void successWithResult() throws Exception {
    CoreEvent event = testEvent();
    parent.success(event);

    assertParent(is(event), is(nullValue()), true, true);
  }

  @Test
  @Description("EventContext response publisher completes with null result. Also given response publisher completed and there there are no child contexts the completion publisher also completes.")
  public void successNoResult() throws Exception {
    parent.success();

    assertParent(nullValue(), is(nullValue()), true, true);
  }

  @Test
  @Description("EventContext response publisher completes with error. Also given response publisher completed and there there are no child contexts the completion publisher also completes.")
  public void error() throws Exception {
    Exception exception = new Exception();
    parent.error(exception);

    assertParent(nullValue(), is(exception), true, true);
  }

  @Test
  @Description("Parent EventContext only completes once response publisher completes with a value and all child contexts are complete.")
  public void childSuccessWithResult() throws Exception {
    child = addChild(parent);

    CoreEvent event = testEvent();
    child.success(event);

    assertChild(is(event), is(nullValue()), true);

    assertParent(is(nullValue()), is(nullValue()), false, false);

    parent.success(event);

    assertParent(is(event), is(nullValue()), true, true);
  }

  @Test
  @Description("Once a child context is completed, its event is not kept in memory.")
  public void childSuccessWithResultFreesChild() throws Exception {
    child = addChild(parent);

    CoreEvent eventChild = getEventBuilder().message(Message.of(TEST_PAYLOAD)).build();
    CoreEvent eventParent = getEventBuilder().message(Message.of(TEST_PAYLOAD)).build();

    PhantomReference<CoreEvent> childRef = new PhantomReference<>(eventChild, new ReferenceQueue<>());
    child.success(eventChild);
    eventChild = null;
    childResultValue.set(null);

    new PollingProber(GC_POLLING_TIMEOUT, DEFAULT_POLLING_INTERVAL).check(new JUnitLambdaProbe(() -> {
      System.gc();
      assertThat(childRef.isEnqueued(), is(true));
      return true;
    }, "A hard reference is being mantained to the child event."));

    parent.success(eventParent);
  }

  @Test
  @Description("Once a child context is completed, its event is not kept in memory after the response publisher is consumed.")
  public void childSuccessWithResultForPublisherFreesChild() throws Exception {
    child = addChild(parent);

    CoreEvent eventChild = getEventBuilder().message(Message.of(TEST_PAYLOAD)).build();
    CoreEvent eventParent = getEventBuilder().message(Message.of(TEST_PAYLOAD)).build();

    PhantomReference<CoreEvent> childRef = new PhantomReference<>(eventChild, new ReferenceQueue<>());

    Publisher<CoreEvent> responsePublisher = child.getResponsePublisher();

    child.success(eventChild);
    eventChild = null;
    childResultValue.set(null);

    // Force finalization of the response publisher
    from(responsePublisher).block();
    responsePublisher = null;

    new PollingProber(GC_POLLING_TIMEOUT, DEFAULT_POLLING_INTERVAL).check(new JUnitLambdaProbe(() -> {
      System.gc();
      assertThat(childRef.isEnqueued(), is(true));
      return true;
    }, "A hard reference is being mantained to the child event."));

    parent.success(eventParent);
  }

  @Test
  public void multipleResponsePublisherSubscriptions() throws MuleException {
    CoreEvent event = getEventBuilder().message(Message.of(TEST_PAYLOAD)).build();

    AtomicInteger responseCounter = new AtomicInteger();

    final reactor.core.scheduler.Scheduler singleScheduler = fromExecutor(newSingleThreadExecutor());

    try {
      // Call getResponsePublisher twice to receive the response in 2 different places
      Flux.from(parent.getResponsePublisher())
          .mergeWith(parent.getResponsePublisher())
          .subscribeOn(singleScheduler)
          .subscribe(e -> responseCounter.incrementAndGet());

      parent.success(event);

      probe(() -> responseCounter.get() == 2);
    } finally {
      singleScheduler.dispose();
    }
  }

  @Test
  @Issue("MULE-15257")
  public void parentResponseConsumerCalledWithChildContext() throws MuleException {
    AtomicBoolean parentResponse = new AtomicBoolean();
    AtomicBoolean childResponse = new AtomicBoolean();

    parent.onResponse((e, t) -> parentResponse.set(true));

    child = addChild(parent);

    Mono.from(child.getResponsePublisher())
        .doOnNext(e -> childResponse.set(true))
        .subscribe();

    child.success(getEventBuilder().message(Message.of(TEST_PAYLOAD)).build());

    probe(() -> childResponse.get());
    probe(() -> !parentResponse.get());

    parent.success(getEventBuilder().message(Message.of(TEST_PAYLOAD)).build());

    probe(() -> parentResponse.get());
  }

  @Test
  public void terminatedChildContextsCleared() {
    child = addChild(parent);
    child.success();

    assertThat(child.isTerminated(), is(true));

    PhantomReference<BaseEventContext> childRef = new PhantomReference<>(child, new ReferenceQueue<>());
    child = null;
    new PollingProber(GC_POLLING_TIMEOUT, DEFAULT_POLLING_INTERVAL).check(new JUnitLambdaProbe(() -> {
      System.gc();
      assertThat(childRef.isEnqueued(), is(true));
      return true;
    }, "A hard reference is being mantained to the child eventContext."));
  }

  @Test
  public void terminatedContextClearsCallbacks() {
    PhantomReference<Object> referencedInCallbackRef = configureEventContextCallbacks();

    new PollingProber(GC_POLLING_TIMEOUT, DEFAULT_POLLING_INTERVAL).check(new JUnitLambdaProbe(() -> {
      System.gc();
      assertThat(referencedInCallbackRef.isEnqueued(), is(true));
      return true;
    }, "A hard reference is being mantained to an event context callback."));
  }

  private PhantomReference<Object> configureEventContextCallbacks() {
    final Object referencedInCallback = new Object();

    parent.onResponse((e, t) -> {
    });
    parent.onComplete((e, t) -> {
    });
    parent.onTerminated((e, t) -> {
    });

    parent.success();
    return new PhantomReference<>(referencedInCallback, new ReferenceQueue<>());
  }

  @Test
  @Description("Parent EventContext only completes once response publisher completes with a value and all child contexts are complete, even when child context completes after parent context response.")
  public void childDelayedSuccessWithResult() throws Exception {
    child = addChild(parent);

    CoreEvent event = testEvent();
    parent.success(event);

    assertParent(is(event), is(nullValue()), false, false);

    child.success(event);

    assertChild(is(event), is(nullValue()), true);
    assertParent(is(event), is(nullValue()), true, true);
  }

  @Test
  @Description("Parent EventContext only completes once response publisher completes with no value and all child contexts are complete.")
  public void childSuccessWithNoResult() throws Exception {
    addChild(parent);

    child.success();
    parent.success();

    assertChild(is(nullValue()), is(nullValue()), true);
    assertParent(is(nullValue()), is(nullValue()), true, true);
  }

  @Test
  @Description("Parent EventContext only completes once response publisher completes with no value and all child contexts are complete, even when child context completes after parent context response.")
  public void childDelayedSuccessWithNoResult() throws Exception {
    child = addChild(parent);

    parent.success();

    assertParent(is(nullValue()), is(nullValue()), false, false);

    child.success();

    assertChild(is(nullValue()), is(nullValue()), true);
    assertParent(is(nullValue()), is(nullValue()), true, true);
  }

  @Test
  @Description("Parent EventContext only completes once response publisher completes with error and all child contexts are complete.")
  public void childError() throws Exception {
    child = addChild(parent);

    RuntimeException exception = new RuntimeException();
    child.error(exception);
    parent.error(exception);

    assertParent(is(nullValue()), is(exception), true, true);
    assertChild(is(nullValue()), is(exception), true);
  }

  @Test
  @Description("Parent EventContext only completes once response publisher completes with error and all child contexts are complete, even when child context completes after parent context response.")
  public void childDelayedError() throws Exception {
    child = addChild(parent);

    RuntimeException exception = new RuntimeException();
    parent.error(exception);

    assertParent(is(nullValue()), is(exception), false, false);

    child.error(exception);

    assertParent(is(nullValue()), is(exception), true, true);
    assertChild(is(nullValue()), is(exception), true);
  }

  @Test
  @Description("Parent EventContext only completes once response publisher completes with a value and all child contexts are complete, even when child is run async with a delay.")
  public void asyncChild() throws Exception {
    child = addChild(parent);

    CoreEvent event = testEvent();
    Scheduler testScheduler = muleContext.getSchedulerService().ioScheduler();

    Latch latch1 = new Latch();

    try {
      testScheduler.submit(() -> {
        child.success(event);
        latch1.countDown();
        return null;
      });

      assertParent(is(nullValue()), is(nullValue()), false, false);

      parent.success(event);
      latch1.await();

      assertChild(is(event), is(nullValue()), true);
      assertParent(is(event), is(nullValue()), true, true);
    } finally {
      testScheduler.stop();
    }
  }

  @Test
  @Description("Parent EventContext only completes once response publisher completes with a value and all child and grandchild contexts are complete.")
  public void multipleLevelsGrandchildFirst() throws Exception {
    child = addChild(parent);
    BaseEventContext grandchild = child(child, empty());

    grandchild.success(testEvent());

    assertChild(is(nullValue()), is(nullValue()), false);
    assertParent(is(nullValue()), is(nullValue()), false, false);

    child.success(testEvent());

    assertChild(is(testEvent()), is(nullValue()), true);
    assertParent(is(nullValue()), is(nullValue()), false, false);

    parent.success(testEvent());

    assertChild(is(testEvent()), is(nullValue()), true);
    assertParent(is(testEvent()), is(nullValue()), true, true);

  }

  @Test
  @Description("Parent EventContext only completes once response publisher completes with a value and all child and grandchild contexts are complete, even if parent response is available earlier.")
  public void multipleLevelsParentFirst()
      throws Exception {
    child = addChild(parent);
    BaseEventContext grandchild = child(child, empty());

    parent.success(testEvent());

    assertChild(is(nullValue()), is(nullValue()), false);
    assertParent(is(testEvent()), is(nullValue()), false, false);

    child.success(testEvent());

    assertChild(is(testEvent()), is(nullValue()), false);
    assertParent(is(testEvent()), is(nullValue()), false, false);

    grandchild.success();

    assertChild(is(testEvent()), is(nullValue()), true);
    assertParent(is(testEvent()), is(nullValue()), true, true);
  }

  @Test
  @Description("Parent EventContext only completes once response publisher completes with a value and all child contexts are complete, even if one branch of the tree completes.")
  public void multipleBranches() throws Exception {
    BaseEventContext parent = context.get();
    BaseEventContext child1 = child(parent, empty());
    BaseEventContext child2 = child(parent, empty());

    BaseEventContext grandchild1 = child(child1, empty());
    BaseEventContext grandchild2 = child(child1, empty());
    BaseEventContext grandchild3 = child(child2, empty());
    BaseEventContext grandchild4 = child(child2, empty());

    grandchild1.success();
    grandchild2.success();

    assertThat(grandchild1.isTerminated(), is(true));
    assertThat(grandchild2.isTerminated(), is(true));
    assertThat(child1.isTerminated(), is(false));
    assertThat(parent.isTerminated(), is(false));

    child1.success();
    assertThat(child1.isTerminated(), is(true));
    assertThat(parent.isTerminated(), is(false));

    grandchild3.success();
    grandchild4.success();
    child2.success();

    assertThat(grandchild3.isTerminated(), is(true));
    assertThat(grandchild4.isTerminated(), is(true));
    assertThat(child2.isTerminated(), is(true));
    assertThat(parent.isTerminated(), is(false));

    parent.success();

    assertThat(parent.isTerminated(), is(true));
  }

  @Test
  @Description("EventContext response publisher completes with value of result but the completion publisher only completes once the external publisher completes.")
  public void externalCompletionSuccess() throws Exception {
    CompletableFuture<Void> externalCompletion = new CompletableFuture<>();
    parent = contextWithCompletion.apply(externalCompletion);
    setupParentListeners(parent);

    CoreEvent event = testEvent();
    assertThat(parent.isTerminated(), is(false));
    parent.success(event);

    assertParent(is(event), is(nullValue()), true, false);

    externalCompletion.complete(null);
    assertThat(parent.isTerminated(), is(true));
  }

  @Test
  @Description("EventContext response publisher completes with error but the completion publisher only completes once the external publisher completes.")
  public void externalCompletionError() throws Exception {
    CompletableFuture<Void> externalCompletion = new CompletableFuture<>();
    parent = contextWithCompletion.apply(externalCompletion);
    setupParentListeners(parent);

    RuntimeException exception = new RuntimeException();
    assertThat(parent.isTerminated(), is(false));
    parent.error(exception);

    assertParent(is(nullValue()), is(exception), true, false);

    externalCompletion.complete(null);
    assertThat(parent.isTerminated(), is(true));
  }

  @Test
  @Description("Parent EventContext only completes once response publisher completes with a value and all child contexts are complete and external completion completes.")
  public void externalCompletionWithChild() throws Exception {
    CompletableFuture<Void> externalCompletion = new CompletableFuture<>();
    parent = contextWithCompletion.apply(externalCompletion);
    setupParentListeners(parent);
    child = addChild(parent);

    CoreEvent event = testEvent();

    child.success(event);

    assertChild(is(event), is(nullValue()), true);
    assertThat(parent.isTerminated(), is(false));

    parent.success(event);

    assertParent(is(event), is(nullValue()), true, false);

    externalCompletion.complete(null);
    assertThat(parent.isTerminated(), is(true));
  }

  @Test
  @Description("When a child event context is de-serialized it is decoupled from parent context but response and completion publisher still complete when a response event is available.")
  public void deserializedChild() throws Exception {
    child = addChild(parent);

    byte[] bytes = serializer.getExternalProtocol().serialize(child);
    child = serializer.getExternalProtocol().deserialize(bytes);
    setupChildListeners(child);

    child.success(testEvent());

    assertChild(is(testEvent()), is(nullValue()), true);
  }

  @Test
  @Description("When a parent event context is de-serialized the parent context no longer waits for completion of childcontext.")
  public void deserializedParent()
      throws Exception {
    child = addChild(parent);

    byte[] bytes = serializer.getExternalProtocol().serialize(parent);
    parent = serializer.getExternalProtocol().deserialize(bytes);
    setupParentListeners(parent);

    parent.success(testEvent());

    assertParent(is(testEvent()), is(nullValue()), true, true);
  }

  @Test
  @Description("Verify that a location produces connector and source data.")
  public void componentData() throws Exception {
    TypedComponentIdentifier typedComponentIdentifier = TypedComponentIdentifier.builder()
        .type(SOURCE)
        .identifier(buildFromStringRepresentation("http:listener"))
        .build();
    ComponentLocation location = mock(ComponentLocation.class);
    when(location.getComponentIdentifier()).thenReturn(typedComponentIdentifier);
    when(location.getParts())
        .thenReturn(asList(new DefaultLocationPart("flow", empty(), empty(), OptionalInt.empty(), OptionalInt.empty())));
    BaseEventContext context = contextWithComponentLocation.apply(location);

    assertThat(context.getOriginatingLocation().getComponentIdentifier().getIdentifier().getNamespace(), is("http"));
    assertThat(context.getOriginatingLocation().getComponentIdentifier().getIdentifier().getName(), is("listener"));
  }

  @Test
  @Description("Verify that a single component location produces connector and source data.")
  public void componentDataFromSingleComponent() throws Exception {
    BaseEventContext context = this.context.get();

    assertThat(context.getOriginatingLocation().getComponentIdentifier().getIdentifier().getNamespace(), is(CORE_PREFIX));
    assertThat(context.getOriginatingLocation().getComponentIdentifier().getIdentifier().getName(), is("test"));
  }

  @Test
  public void callbacksOrderSuccess() throws MuleException {
    List<String> callbacks = new ArrayList<>();

    final DefaultEventContext eventContext = context.get();

    eventContext.onResponse((e, t) -> callbacks.add("onResponse"));
    eventContext.onComplete((e, t) -> callbacks.add("onComplete"));
    eventContext.onTerminated((e, t) -> callbacks.add("onTerminated"));

    eventContext.success(testEvent());

    assertThat(callbacks, contains("onResponse", "onComplete", "onTerminated"));
  }

  @Test
  public void callbacksOrderSuccessEmpty() {
    List<String> callbacks = new ArrayList<>();

    final DefaultEventContext eventContext = context.get();

    eventContext.onResponse((e, t) -> callbacks.add("onResponse"));
    eventContext.onComplete((e, t) -> callbacks.add("onComplete"));
    eventContext.onTerminated((e, t) -> callbacks.add("onTerminated"));

    eventContext.success();

    assertThat(callbacks, contains("onResponse", "onComplete", "onTerminated"));
  }

  @Test
  public void callbacksOrderError() {
    List<String> callbacks = new ArrayList<>();

    final DefaultEventContext eventContext = context.get();

    eventContext.onResponse((e, t) -> callbacks.add("onResponse"));
    eventContext.onComplete((e, t) -> callbacks.add("onComplete"));
    eventContext.onTerminated((e, t) -> callbacks.add("onTerminated"));

    eventContext.error(new NullPointerException());

    assertThat(callbacks, contains("onResponse", "onComplete", "onTerminated"));
  }

  @Test
  public void rootIdIsCorrelationId() {
    EventContext context = EventContextFactory.create("someId", "theServer", null, TEST_CORRELATION_ID, empty());
    assertThat(context.getId(), is("someId"));
    assertThat(context.getCorrelationId(), is(TEST_CORRELATION_ID));
    assertThat(context.getRootId(), is(TEST_CORRELATION_ID));
  }

  private void assertParent(Matcher<Object> eventMatcher, Matcher<Object> errorMatcher, boolean complete, boolean terminated) {
    assertThat(parentResultValue.get(), eventMatcher);
    assertThat(parentErrorValue.get(), errorMatcher);
    assertThat(parentCompletion.get(), is(complete));
    assertThat(parentTerminated.get(), is(terminated));
  }

  private void assertChild(Matcher<Object> eventMatcher, Matcher<Object> errorMatcher, boolean complete) {
    assertThat(childResultValue.get(), eventMatcher);
    assertThat(childErrorValue.get(), errorMatcher);
    assertThat(childCompletion.get(), is(complete));
  }

}
