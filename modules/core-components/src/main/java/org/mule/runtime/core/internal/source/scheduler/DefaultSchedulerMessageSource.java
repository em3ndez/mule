/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.source.scheduler;

import static org.mule.runtime.core.api.config.i18n.CoreMessages.failedToScheduleWork;
import static org.mule.runtime.core.api.source.MessageSource.BackPressureStrategy.FAIL;
import static org.mule.runtime.core.api.util.ClassUtils.withContextClassLoader;
import static org.mule.runtime.core.internal.component.ComponentUtils.getFromAnnotatedObject;
import static org.mule.runtime.core.privileged.event.PrivilegedEvent.setCurrentEvent;

import static java.util.Collections.emptyList;
import static java.util.Optional.empty;

import static org.slf4j.LoggerFactory.getLogger;

import org.mule.runtime.api.component.AbstractComponent;
import org.mule.runtime.api.component.location.ConfigurationComponentLocator;
import org.mule.runtime.api.config.FeatureFlaggingService;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.lifecycle.CreateException;
import org.mule.runtime.api.lifecycle.Disposable;
import org.mule.runtime.api.lifecycle.Initialisable;
import org.mule.runtime.api.lifecycle.InitialisationException;
import org.mule.runtime.api.scheduler.Scheduler;
import org.mule.runtime.api.scheduler.SchedulerConfig;
import org.mule.runtime.api.scheduler.SchedulerService;
import org.mule.runtime.api.source.SchedulerConfiguration;
import org.mule.runtime.api.source.SchedulerMessageSource;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.construct.FlowConstruct;
import org.mule.runtime.core.api.context.MuleContextAware;
import org.mule.runtime.core.api.processor.Processor;
import org.mule.runtime.core.api.source.MessageSource;
import org.mule.runtime.core.api.source.scheduler.PeriodicScheduler;
import org.mule.runtime.core.internal.exception.MessagingExceptionResolver;
import org.mule.runtime.core.internal.execution.FlowProcessTemplate;
import org.mule.runtime.core.internal.execution.MessageProcessContext;
import org.mule.runtime.core.internal.execution.MessageProcessingManager;
import org.mule.runtime.core.privileged.exception.ErrorTypeLocator;
import org.mule.runtime.core.privileged.transaction.TransactionConfig;

import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

import jakarta.inject.Inject;

import org.slf4j.Logger;

/**
 * <p>
 * Polling {@link org.mule.runtime.core.api.source.MessageSource}.
 * </p>
 * <p>
 * The {@link DefaultSchedulerMessageSource} is responsible of creating a {@link org.mule.runtime.api.scheduler.Scheduler} at the
 * initialization phase. This {@link org.mule.runtime.api.scheduler.Scheduler} can be stopped/started and executed by using the
 * {@link org.mule.runtime.core.internal.registry.MuleRegistry} interface, this way users can manipulate poll from outside mule
 * server.
 * </p>
 */
public class DefaultSchedulerMessageSource extends AbstractComponent
    implements MessageSource, SchedulerMessageSource, MuleContextAware, Initialisable, Disposable {

  private final static Logger LOGGER = getLogger(DefaultSchedulerMessageSource.class);

  private final PeriodicScheduler scheduler;
  private final boolean disallowConcurrentExecution;

  private Scheduler pollingExecutor;
  private ScheduledFuture<?> schedulingJob;
  private Processor listener;
  private FlowConstruct flowConstruct;

  @Inject
  private MuleContext muleContext;

  @Inject
  private SchedulerService schedulerService;

  @Inject
  private ConfigurationComponentLocator componentLocator;

  @Inject
  private ErrorTypeLocator errorTypeLocator;

  @Inject
  private MessageProcessingManager messageProcessingManager;

  @Inject
  private FeatureFlaggingService featureFlaggingService;

  private boolean started;
  private volatile boolean executing = false;
  private FlowProcessTemplate flowProcessingTemplate;
  private SchedulerProcessContext flowProcessContext;

  /**
   * @param muleContext application's context
   * @param scheduler   the scheduler
   */
  public DefaultSchedulerMessageSource(PeriodicScheduler scheduler,
                                       boolean disallowConcurrentExecution) {
    this.scheduler = scheduler;
    this.disallowConcurrentExecution = disallowConcurrentExecution;
  }

  @Override
  public synchronized void start() throws MuleException {
    if (started) {
      return;
    }
    try {
      // Check if context is stopping before scheduling new task
      if (muleContext.isStopping()) {
        LOGGER.info("Not starting scheduler as MuleContext is stopping");
        return;
      }
      // The initialization phase if handled by the scheduler
      schedulingJob =
          withContextClassLoader(muleContext.getExecutionClassLoader(), () -> scheduler.schedule(pollingExecutor, () -> run()));
      this.started = true;
    } catch (Exception ex) {
      this.stop();
      throw new CreateException(failedToScheduleWork(), ex, this);
    }
  }

  @Override
  public synchronized void stop() throws MuleException {
    if (!started) {
      return;
    }
    // Stop the scheduler to address the case when the flow is stop but not the application
    if (schedulingJob != null) {
      schedulingJob.cancel(false);
      schedulingJob = null;
    }
    this.started = false;
  }

  @Override
  public void trigger() {
    pollingExecutor.execute(() -> withContextClassLoader(muleContext.getExecutionClassLoader(), () -> poll()));
  }

  @Override
  public boolean isStarted() {
    return started;
  }

  @Override
  public SchedulerConfiguration getConfiguration() {
    return scheduler;
  }

  /**
   * Checks whether polling should take place on this instance.
   */
  private final void run() {
    // Make sure we start with a clean state.
    setCurrentEvent(null);

    // Check if context is stopping before executing
    if (muleContext.isStopping()) {
      LOGGER.info("Skipping scheduler execution as MuleContext is stopping");
      return;
    }

    if (muleContext.isPrimaryPollingInstance()) {
      poll();
    }
  }

  /**
   * Triggers the forced execution of the polling message processor ignoring the configured scheduler.
   */
  private void poll() {
    // Check if context is stopping before polling
    if (muleContext.isStopping()) {
      LOGGER.info("Skipping poll as MuleContext is stopping");
      return;
    }

    boolean execute = false;
    synchronized (this) {
      if (disallowConcurrentExecution && executing) {
        execute = false;
      } else {
        execute = true;
        executing = true;
      }
    }

    if (execute) {
      doPoll();
    } else {
      LOGGER.info("Flow '{}' is already running and 'disallowConcurrentExecution' is set to 'true'. Execution skipped.",
                  getLocation().getRootContainerName());
    }
  }

  private void doPoll() {
    try {
      messageProcessingManager.processMessage(flowProcessingTemplate, flowProcessContext);
    } catch (Exception e) {
      muleContext.getExceptionListener().handleException(e);
    }
  }

  protected void setIsExecuting(boolean value) {
    synchronized (this) {
      executing = value;
    }
  }


  /**
   * <p>
   * On the Initialization phase it.
   * <ul>
   * <li>Calls the {@link PeriodicScheduler} to create the scheduler</li>
   * <li>Gets the Poll the message source</li>
   * <li>Gets the Poll override</li>
   * </ul>
   * </p>
   */
  @Override
  public void initialise() throws InitialisationException {
    getFromAnnotatedObject(componentLocator, this)
        .ifPresent(flow -> this.flowConstruct = flow);

    // Flow execution configurations
    this.flowProcessingTemplate = new SchedulerFlowProcessingTemplate(listener, emptyList(), this, featureFlaggingService);
    this.flowProcessContext = new SchedulerProcessContext();

    createScheduler();
  }

  @Override
  public void dispose() {
    disposeScheduler();
  }

  private void createScheduler() throws InitialisationException {
    pollingExecutor = schedulerService
        .cpuLightScheduler(SchedulerConfig.config()
            .withName(this.getClass().getName() + ".pollingExecutor - " + getLocation().getLocation()));
  }

  private void disposeScheduler() {
    if (pollingExecutor != null) {
      pollingExecutor.stop();
      pollingExecutor = null;
    }
  }

  @Override
  public void setMuleContext(MuleContext context) {
    this.muleContext = context;
  }


  @Override
  public void setListener(Processor listener) {
    this.listener = listener;
  }

  @Override
  public BackPressureStrategy getBackPressureStrategy() {
    return FAIL;
  }

  private class SchedulerProcessContext implements MessageProcessContext {

    private final MessagingExceptionResolver messagingExceptionResolver = new MessagingExceptionResolver(getMessageSource());

    @Override
    public MessageSource getMessageSource() {
      return DefaultSchedulerMessageSource.this;
    }

    @Override
    public Optional<TransactionConfig> getTransactionConfig() {
      return empty();
    }

    @Override
    public ClassLoader getExecutionClassLoader() {
      return muleContext.getExecutionClassLoader();
    }

    @Override
    public ErrorTypeLocator getErrorTypeLocator() {
      return errorTypeLocator;
    }

    @Override
    public MessagingExceptionResolver getMessagingExceptionResolver() {
      return messagingExceptionResolver;
    }

    @Override
    public FlowConstruct getFlowConstruct() {
      return flowConstruct;
    }
  }
}
