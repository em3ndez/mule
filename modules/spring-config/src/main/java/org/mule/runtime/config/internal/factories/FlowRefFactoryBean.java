/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.config.internal.factories;

import static org.mule.runtime.api.el.BindingContextUtils.NULL_BINDING_CONTEXT;
import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.mule.runtime.api.metadata.DataType.STRING;
import static org.mule.runtime.api.util.MuleSystemProperties.MULE_FLOW_REF_MAX_SUB_FLOWS_SINGLE_CHAIN;
import static org.mule.runtime.config.internal.dsl.spring.ComponentModelHelper.updateAnnotationValue;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.disposeIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.initialiseIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.startIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.stopIfNeeded;
import static org.mule.runtime.core.api.util.ClassUtils.setContextClassLoader;
import static org.mule.runtime.core.internal.event.EventQuickCopy.quickCopy;
import static org.mule.runtime.core.internal.util.rx.Operators.outputToTarget;
import static org.mule.runtime.core.privileged.processor.MessageProcessors.WITHIN_PROCESS_TO_APPLY;
import static org.mule.runtime.core.privileged.processor.MessageProcessors.applyWithChildContextDontPropagateErrors;
import static org.mule.runtime.core.privileged.processor.MessageProcessors.processWithChildContextDontComplete;

import static java.lang.Thread.currentThread;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonMap;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;

import static org.slf4j.LoggerFactory.getLogger;
import static reactor.core.Exceptions.propagate;
import static reactor.core.publisher.Flux.error;
import static reactor.core.publisher.Flux.from;

import org.mule.runtime.api.component.Component;
import org.mule.runtime.api.component.location.ComponentLocation;
import org.mule.runtime.api.component.location.ConfigurationComponentLocator;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.util.LazyValue;
import org.mule.runtime.config.internal.context.MuleArtifactContext;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.construct.Flow;
import org.mule.runtime.core.api.construct.FlowConstruct;
import org.mule.runtime.core.api.el.ExtendedExpressionManager;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.api.processor.Processor;
import org.mule.runtime.core.api.processor.ReactiveProcessor;
import org.mule.runtime.core.api.processor.Sink;
import org.mule.runtime.core.api.processor.strategy.ProcessingStrategy;
import org.mule.runtime.core.internal.event.InternalEvent;
import org.mule.runtime.core.internal.exception.DeepSubFlowNestingFlowRefException;
import org.mule.runtime.core.internal.exception.RecursiveFlowRefException;
import org.mule.runtime.core.internal.processor.chain.SubflowMessageProcessorChainBuilder;
import org.mule.runtime.core.internal.routing.result.RoutePathNotFoundException;
import org.mule.runtime.core.privileged.event.BaseEventContext;
import org.mule.runtime.core.privileged.exception.MessagingException;
import org.mule.runtime.core.privileged.processor.chain.MessageProcessorChain;
import org.mule.runtime.dsl.api.component.AbstractComponentFactory;
import org.mule.runtime.tracer.api.component.ComponentTracerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import javax.xml.namespace.QName;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.beans.factory.support.ManagedMap;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import jakarta.inject.Inject;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

public class FlowRefFactoryBean extends AbstractComponentFactory<Processor> implements ApplicationContextAware {

  private static final Logger LOGGER = getLogger(FlowRefFactoryBean.class);

  private static final String APPLIED_FLOWREFS_KEY = "mule.flowref.appliedFlowrefsInReactorChain";

  private static final int MAX_SUB_FLOWS_SINGLE_CHAIN = Integer.getInteger(MULE_FLOW_REF_MAX_SUB_FLOWS_SINGLE_CHAIN, 10);
  public static final String MULE_TEST_FLOW_REF_MAX_SUB_FLOWS_SINGLE_CHAIN_FAIL = "mule.test.flowRef.maxSubFlowsSingleChain.fail";

  private String refName;
  private String target;
  private String targetValue = "#[payload]";

  private ApplicationContext applicationContext;

  @Inject
  private MuleContext muleContext;

  @Inject
  private ExtendedExpressionManager expressionManager;

  @Inject
  private ConfigurationComponentLocator locator;

  @Inject
  private ComponentTracerFactory componentTracerFactory;

  public void setName(String name) {
    this.refName = name;
  }

  /**
   * The variable where the result from this router should be stored. If this is not set then the result is set in the payload.
   *
   * @param target a variable name.
   */
  public void setTarget(String target) {
    this.target = target;
  }

  /**
   * Defines the target value expression
   *
   * @param targetValue the target value expression
   */
  public void setTargetValue(String targetValue) {
    this.targetValue = targetValue;
  }

  @Override
  public Processor doGetObject() throws Exception {
    if (refName.isEmpty()) {
      throw new IllegalArgumentException("flow-ref name is empty");
    }

    if (expressionManager.isExpression(refName)) {
      return new DynamicFlowRefMessageProcessor(this, event -> (String) expressionManager
          .evaluate(refName, STRING, NULL_BINDING_CONTEXT, event, getLocation(), true).getValue());
    } else {
      return new StaticFlowRefMessageProcessor(this, new DynamicFlowRefMessageProcessor(this, event -> refName));
    }
  }

  protected Processor getReferencedFlow(String name, FlowRefMessageProcessor flowRefMessageProcessor) throws MuleException {
    if (name == null) {
      throw new RoutePathNotFoundException(createStaticMessage("flow-ref name expression returned 'null'"),
                                           flowRefMessageProcessor);
    }

    Component referencedFlow = getReferencedProcessor(name);
    if (referencedFlow == null) {
      throw new RoutePathNotFoundException(createStaticMessage("No flow/sub-flow with name '%s' found", name),
                                           flowRefMessageProcessor);
    }

    // for subflows, we create a new one, so it must be initialised manually
    if (!(referencedFlow instanceof Flow)) {
      if (referencedFlow instanceof SubflowMessageProcessorChainBuilder chainBuilder) {
        chainBuilder.withComponentTracerFactory(componentTracerFactory);
        locator.find(flowRefMessageProcessor.getRootContainerLocation()).filter(Flow.class::isInstance).map(c -> (Flow) c)
            .ifPresent(f -> {
              ProcessingStrategy callerFlowPs = f.getProcessingStrategy();
              chainBuilder.setProcessingStrategy(new ProcessingStrategy() {

                @Override
                public Sink createSink(FlowConstruct flowConstruct, ReactiveProcessor pipeline) {
                  return callerFlowPs.createSink(flowConstruct, pipeline);
                }

                @Override
                public ReactiveProcessor onPipeline(ReactiveProcessor pipeline) {
                  // Do not make any change in `onPipeline`, so it emulates the behavior of copy/pasting the content of the
                  // sub-flow into the caller flow, without applying any additional logic.
                  return pipeline;
                }

                @Override
                public ReactiveProcessor onProcessor(ReactiveProcessor processor) {
                  return callerFlowPs.onProcessor(processor);
                }
              });
            });

        referencedFlow = chainBuilder.build();
      }
      initialiseIfNeeded(referencedFlow, muleContext);

      Map<QName, Object> annotations = new HashMap<>(referencedFlow.getAnnotations());
      annotations.put(ROOT_CONTAINER_NAME_KEY, getRootContainerLocation().toString());
      referencedFlow.setAnnotations(annotations);

      // Set the TCCL to the app one to apply the referenced flow lifecycle, to be consistent on how lifecycle is applied in
      // general.
      // TODO MULE-17598 Use the region classloader instead
      Thread currentThread = currentThread();
      ClassLoader currentClassLoader = currentThread.getContextClassLoader();
      ClassLoader contextClassLoader = muleContext.getExecutionClassLoader();
      setContextClassLoader(currentThread, currentClassLoader, contextClassLoader);
      try {
        startIfNeeded(referencedFlow);
      } finally {
        setContextClassLoader(currentThread, contextClassLoader, currentClassLoader);
      }
    }

    return (Processor) referencedFlow;
  }

  private Component getReferencedProcessor(String name) {
    if (applicationContext instanceof MuleArtifactContext muleArtifactContext) {
      try {
        BeanDefinition processorBeanDefinition = muleArtifactContext.getBeanFactory().getBeanDefinition(name);
        if (processorBeanDefinition.isPrototype()) {
          // Application level synchronization between all FlowRefFactoryBean instances is needed
          // (otherwise two FlowRefFactoryBean instances could try mutate and instantiate the same prototype bean in parallel)
          synchronized (applicationContext) {
            updateBeanDefinitionRootContainerName(getRootContainerLocation().toString(), processorBeanDefinition);
            return (Component) applicationContext.getBean(name);
          }
        }
      } catch (NoSuchBeanDefinitionException e) {
        // Null is handled by the caller method
        return null;
      }
    }
    return (Component) applicationContext.getBean(name);
  }

  private void updateBeanDefinitionRootContainerName(String rootContainerName, BeanDefinition beanDefinition) {
    Class<?> beanClass = null;
    try {
      beanClass = currentThread().getContextClassLoader().loadClass(beanDefinition.getBeanClassName());
    } catch (ClassNotFoundException e) {
      // Nothing to do, spring will break because of this eventually
    }

    if (beanClass == null || Component.class.isAssignableFrom(beanClass)) {
      updateAnnotationValue(ROOT_CONTAINER_NAME_KEY, rootContainerName, beanDefinition);
    }

    for (PropertyValue propertyValue : beanDefinition.getPropertyValues().getPropertyValueList()) {
      Object value = propertyValue.getValue();
      processBeanValue(rootContainerName, value);
    }

    for (ConstructorArgumentValues.ValueHolder valueHolder : beanDefinition.getConstructorArgumentValues()
        .getGenericArgumentValues()) {
      processBeanValue(rootContainerName, valueHolder.getValue());
    }
  }

  private void processBeanValue(String rootContainerName, Object value) {
    if (value instanceof BeanDefinition) {
      updateBeanDefinitionRootContainerName(rootContainerName, (BeanDefinition) value);
    } else if (value instanceof ManagedList managedList) {
      for (Object itemValue : managedList) {
        if (itemValue instanceof BeanDefinition) {
          updateBeanDefinitionRootContainerName(rootContainerName, (BeanDefinition) itemValue);
        }
      }
    } else if (value instanceof ManagedMap managedMap) {
      managedMap.forEach((key, mapValue) -> processBeanValue(rootContainerName, mapValue));
    }
  }

  @Override
  public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
    this.applicationContext = applicationContext;
  }

  /**
   * Flow-ref message processor with a statically (constant along the flow execution) defined target route.
   *
   * @since 4.3, 4.2.3
   */
  private class StaticFlowRefMessageProcessor extends FlowRefMessageProcessor {

    private final DynamicFlowRefMessageProcessor recursiveFallback;
    private final AtomicBoolean stoppedOnce = new AtomicBoolean(false);
    private final LazyValue<ReactiveProcessor> resolvedReferencedProcessorSupplier = new LazyValue<>(() -> {
      try {
        return getReferencedFlow(refName, StaticFlowRefMessageProcessor.this);
      } catch (MuleException e) {
        throw new MuleRuntimeException(e);
      }
    });

    private volatile boolean recursionFound = false;

    protected StaticFlowRefMessageProcessor(FlowRefFactoryBean owner, DynamicFlowRefMessageProcessor recursiveFallback) {
      super(owner);
      this.recursiveFallback = recursiveFallback;
    }

    @Override
    public Publisher<CoreEvent> apply(Publisher<CoreEvent> publisher) {
      if (recursionFound) {
        // If a recursion was found previously, avoid trying to build the chain and revalidating again, use the fallback directly.
        return from(publisher).transform(recursiveFallback);
      }

      final ReactiveProcessor resolvedReferencedProcessor = resolvedReferencedProcessorSupplier.get();

      Flux<CoreEvent> pub = from(publisher)
          .contextWrite(clearCurrentFlowRefFromCycleDetection());

      if (target != null) {
        pub = pub.map(event -> quickCopy(event, singletonMap(originalEventKey(event), event)))
            .cast(CoreEvent.class);
      }

      Optional<ComponentLocation> location = ofNullable(StaticFlowRefMessageProcessor.this.getLocation());

      if (resolvedReferencedProcessor instanceof Flow) {
        pub = from(applyForStaticFlow((Flow) resolvedReferencedProcessor, pub, location));
      } else if (resolvedReferencedProcessor instanceof MessageProcessorChain) {
        pub = from(applyForStaticSubFlow(resolvedReferencedProcessor, pub, location));
      } else {
        pub = from(applyForStaticProcessor(resolvedReferencedProcessor, pub, location));
      }

      // This onErrorResume here is intended to handle the recursive error when it happens during subscription
      // If a recursion is found, do a fallback that avoids prebuilding the whole chain.
      final Flux<CoreEvent> resumed =
          pub.onErrorResume(RecursiveFlowRefException.class::isInstance, t -> {
            recursionFound = true;
            LOGGER.warn(t.toString());
            return from(publisher).transform(recursiveFallback);
          });

      return resumed
          // Same as above, but to avoid building excessive long chains because of nested sub-flows
          .onErrorResume(DeepSubFlowNestingFlowRefException.class::isInstance, t -> {
            LOGGER.debug(t.toString());
            return from(publisher).transform(recursiveFallback);
          });
    }

    private Publisher<CoreEvent> applyForStaticFlow(Flow resolvedTarget, Flux<CoreEvent> pub,
                                                    Optional<ComponentLocation> location) {
      return decoratePublisher(pub
          // Let the error routing be done by the called flow instead of the context hierarchy
          .transform(eventPub -> applyWithChildContextDontPropagateErrors(eventPub, resolvedTarget.referenced(), location)));
    }

    private Publisher<CoreEvent> applyForStaticSubFlow(ReactiveProcessor resolvedTarget, Flux<CoreEvent> pub,
                                                       Optional<ComponentLocation> location) {
      return decoratePublisher(pub.transform(resolvedTarget));
    }

    private Publisher<CoreEvent> applyForStaticProcessor(ReactiveProcessor resolvedTarget, Flux<CoreEvent> pub,
                                                         Optional<ComponentLocation> location) {
      return decoratePublisher(pub.transform(eventPub -> eventPub
          .flatMap(event -> Mono.just(event)
              .transform(resolvedTarget))));
    }

    /**
     * Decorates flowRef publisher with:
     * <ul>
     * <li>Result to target variable mapping</li>
     * <li>FlowRef entry cycle detection</li>
     * </ul>
     *
     * @param pub the current publisher
     * @return the decorated publisher
     */
    private Publisher<CoreEvent> decoratePublisher(Flux<CoreEvent> pub) {
      pub = pub
          .contextWrite(checkAndMarkCurrentFlowRefForCycleDetection());
      return (target != null)
          ? pub.map(eventAfter -> outputToTarget(((InternalEvent) eventAfter)
              .getInternalParameter(originalEventKey(eventAfter)), target, targetValue,
                                                 expressionManager).apply(eventAfter))
          : pub;
    }

    /**
     * Clears the current subflow marker from the {@link Context} that is being propagated from downstream.
     *
     * @return the after-flowref-is-applied {@link Context} transformer
     */
    protected Function<Context, Context> clearCurrentFlowRefFromCycleDetection() {
      return context -> {
        List<String> currentAppliedFlowrefs = new ArrayList<>(context.getOrDefault(APPLIED_FLOWREFS_KEY, emptyList()));
        currentAppliedFlowrefs.remove(refName);
        return context.put(APPLIED_FLOWREFS_KEY, currentAppliedFlowrefs);
      };
    }

    /**
     * Does two things:
     * <ul>
     * <li>If the current flowref marker is found in the {@link Context}, it means it wasn't cleared. This implies that after the
     * inner chain of this flowref was subscribed, the subscriberContext defined in
     * {@link StaticFlowRefMessageProcessor#clearCurrentFlowRefFromCycleDetection} wasn't called, which happens only after the
     * innerChain subscription ends.</li> Since it wasn't called, it means some other flowref refers to the same inner chain,
     * hence the cycle.
     * <li>If this is the first time the flowref is visited, sets the marker.</li>
     * </ul>
     *
     * @return the before-flowref-is-applied {@link Context} transformer
     */
    private Function<Context, Context> checkAndMarkCurrentFlowRefForCycleDetection() {
      return context -> {
        List<String> currentAppliedFlowrefs = new ArrayList<>(context.getOrDefault(APPLIED_FLOWREFS_KEY, emptyList()));
        if (currentAppliedFlowrefs.contains(refName)) {
          List<String> forMessage = new ArrayList<>(currentAppliedFlowrefs);
          forMessage.add(refName);

          throw propagate(new RecursiveFlowRefException(currentAppliedFlowrefs.stream()
              .collect(joining("' -> '", "'", "'")), StaticFlowRefMessageProcessor.this));
        }

        if (currentAppliedFlowrefs.size() > MAX_SUB_FLOWS_SINGLE_CHAIN) {
          List<String> forMessage = new ArrayList<>(currentAppliedFlowrefs);
          forMessage.add(refName);

          throw propagate(new DeepSubFlowNestingFlowRefException(forMessage.stream()
              .collect(joining("' -> '", "'", "'")), StaticFlowRefMessageProcessor.this));
        }

        currentAppliedFlowrefs.add(refName);
        return context.put(APPLIED_FLOWREFS_KEY, currentAppliedFlowrefs);
      };
    }

    protected String originalEventKey(CoreEvent event) {
      return "flowRef.originalEvent." + event.getContext().getId() + getLocation().getLocation();
    }

    @Override
    public void doStart() throws MuleException {
      // Preventing two sequential stop calls in case the flow with a referenced subflow is first constructed, and then started
      if (stoppedOnce.get() && targetIsComputedAndSubFlow()) {
        startIfNeeded(resolvedReferencedProcessorSupplier.get());
      }
    }

    @Override
    public void stop() throws MuleException {
      if (targetIsComputedAndSubFlow()) {
        stopIfNeeded(resolvedReferencedProcessorSupplier.get());
        // Since it was manually stopped, in the next start the target should be started
        stoppedOnce.set(true);
      }
    }

    @Override
    public void dispose() {
      if (targetIsComputedAndSubFlow()) {
        disposeIfNeeded(resolvedReferencedProcessorSupplier.get(), LOGGER);
      }
    }

    protected boolean targetIsComputedAndSubFlow() {
      return resolvedReferencedProcessorSupplier.isComputed() &&
          !(resolvedReferencedProcessorSupplier.get() instanceof Flow);
    }

    @Override
    public void setAnnotations(Map<QName, Object> newAnnotations) {
      super.setAnnotations(newAnnotations);
      recursiveFallback.setAnnotations(newAnnotations);
    }
  }

  /**
   * Flow-ref message processor whose route might change along the flow execution. This means the target route is defined with a
   * data-weave expression.
   *
   * @since 4.3, 4.2.3
   */
  private class DynamicFlowRefMessageProcessor extends FlowRefMessageProcessor {

    private final Function<CoreEvent, String> refNameFromEvent;
    private final LoadingCache<String, Processor> targetsCache;

    public DynamicFlowRefMessageProcessor(FlowRefFactoryBean owner, Function<CoreEvent, String> refNameFromEvent) {
      super(owner);
      this.refNameFromEvent = refNameFromEvent;
      this.targetsCache = CacheBuilder.newBuilder()
          .maximumSize(20)
          .build(new CacheLoader<String, Processor>() {

            @Override
            public Processor load(String key) throws Exception {
              return getReferencedFlow(key, DynamicFlowRefMessageProcessor.this);
            }
          });
    }

    @Override
    public Publisher<CoreEvent> apply(Publisher<CoreEvent> publisher) {
      return from(publisher).flatMap(event -> {
        ReactiveProcessor resolvedTarget;

        try {
          resolvedTarget = resolveTargetFlowOrSubflow(event);
        } catch (MuleException e) {
          return error(e);
        }

        Optional<Flow> targetAsFlow = resolvedTarget instanceof Flow ? of((Flow) resolvedTarget) : empty();
        return Mono
            .from(processWithChildContextFlowOrSubflow(event, resolvedTarget, targetAsFlow))
            .map(outputToTarget(event, target, targetValue, expressionManager));
      });
    }

    protected Publisher<CoreEvent> processWithChildContextFlowOrSubflow(CoreEvent event,
                                                                        ReactiveProcessor resolvedTarget,
                                                                        Optional<Flow> targetAsFlow) {
      Optional<ComponentLocation> componentLocation = ofNullable(DynamicFlowRefMessageProcessor.this.getLocation());
      if (targetAsFlow.isPresent()) {
        return processWithChildContextDontComplete(event, p -> Mono.from(p)
            .transform(targetAsFlow.get().referenced())
            .onErrorMap(MessagingException.class, getMessagingExceptionMapper()), componentLocation);
      } else {
        // If the resolved target is not a flow, it should be a subflow
        return Mono.just(event).transform(resolvedTarget)
            // This is needed for all cases because of the way that flow-ref invokes flows dynamically
            .contextWrite(innerCtx -> innerCtx.put(WITHIN_PROCESS_TO_APPLY, true));
      }
    }

    /**
     * Given the current {@link CoreEvent}, resolved which processor is targeted by it, being this a {@link Flow} or a
     * {@link org.mule.runtime.core.internal.processor.chain.SubflowMessageProcessorChainBuilder.SubFlowMessageProcessorChain}.
     * Also, caches the fetched {@link Processor} for future calls.
     *
     * @param event the {@link CoreEvent} event
     * @return the {@link Processor} targeted by the current event
     * @throws MuleException
     */
    protected Processor resolveTargetFlowOrSubflow(CoreEvent event) throws MuleException {
      try {
        return targetsCache.getUnchecked(refNameFromEvent.apply(event));

      } catch (UncheckedExecutionException e) {
        if (e.getCause() instanceof MuleRuntimeException) {
          throw (MuleRuntimeException) e.getCause();
        } else if (e.getCause() instanceof MuleException) {
          throw (MuleException) e.getCause();
        } else {
          throw e;
        }
      }
    }

    @Override
    public void doStart() throws MuleException {
      for (Processor p : targetsCache.asMap().values()) {
        if (!(p instanceof Flow)) {
          startIfNeeded(p);
        }
      }
    }

    @Override
    public void stop() throws MuleException {
      for (Processor p : targetsCache.asMap().values()) {
        if (!(p instanceof Flow)) {
          stopIfNeeded(p);
        }
      }
    }

    @Override
    public void dispose() {
      for (Processor p : targetsCache.asMap().values()) {
        if (!(p instanceof Flow)) {
          disposeIfNeeded(p, LOGGER);
        }
      }
      targetsCache.invalidateAll();
      targetsCache.cleanUp();
    }

  }

  /**
   * Commonly used {@link MessagingException} mapper.
   *
   * @return a {@link MessagingException} mapper that maps the input exception to one using the wrapped event's parent context.
   */
  private Function<MessagingException, Throwable> getMessagingExceptionMapper() {
    return me -> new MessagingException(quickCopy(((BaseEventContext) me.getEvent().getContext()).getParentContext()
        .get(), me.getEvent()), me);
  }
}
