/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.deployment.impl.internal.application;

import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.mule.runtime.core.api.context.notification.MuleContextNotification.CONTEXT_INITIALISED;
import static org.mule.test.allure.AllureConstants.LifecycleAndDependencyInjectionFeature.LIFECYCLE_AND_DEPENDENCY_INJECTION;
import static org.mule.test.allure.AllureConstants.LifecycleAndDependencyInjectionFeature.ApplicationStatus.APPLICATION_STATUS_STORY;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.notNullValue;

import static org.junit.Assert.fail;

import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.memory.management.MemoryManagementService;
import org.mule.runtime.api.notification.NotificationDispatcher;
import org.mule.runtime.api.service.ServiceRepository;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.context.notification.MuleContextNotification;
import org.mule.runtime.core.internal.registry.DefaultRegistry;
import org.mule.runtime.deployment.model.api.application.ApplicationDescriptor;
import org.mule.runtime.deployment.model.api.application.ApplicationStatus;
import org.mule.runtime.deployment.model.api.artifact.ArtifactConfigurationProcessor;
import org.mule.runtime.deployment.model.api.artifact.ArtifactContext;
import org.mule.runtime.module.artifact.activation.api.extension.discovery.ExtensionModelLoaderRepository;
import org.mule.runtime.module.artifact.activation.internal.classloader.MuleApplicationClassLoader;
import org.mule.runtime.module.artifact.api.descriptor.ClassLoaderConfiguration.ClassLoaderConfigurationBuilder;
import org.mule.tck.junit4.AbstractMuleContextTestCase;
import org.mule.tck.probe.JUnitProbe;
import org.mule.tck.probe.PollingProber;

import java.io.File;
import java.net.URL;

import org.junit.Test;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import jakarta.inject.Inject;

/**
 * These tests verify that the {@link DefaultMuleApplication} status is set correctly depending on its
 * {@link org.mule.runtime.core.api.MuleContext}'s lifecycle phase
 */
@Feature(LIFECYCLE_AND_DEPENDENCY_INJECTION)
@Story(APPLICATION_STATUS_STORY)
public class DefaultMuleApplicationStatusTestCase extends AbstractMuleContextTestCase {

  private static final int PROBER_TIMEOUT = 1000;
  private static final int PROBER_INTERVAL = 100;

  @Inject
  private NotificationDispatcher notificationDispatcher;

  private ArtifactContext mockArtifactContext;

  private DefaultMuleApplication application;
  private final File appLocation = new File("fakeLocation");

  @Override
  protected void doSetUp() throws Exception {
    MuleApplicationClassLoader parentArtifactClassLoader = mock(MuleApplicationClassLoader.class);
    mockArtifactContext = mock(ArtifactContext.class);
    when(mockArtifactContext.getMuleContext()).thenReturn(muleContext);
    when(mockArtifactContext.getRegistry()).thenReturn(new DefaultRegistry(muleContext));
    ApplicationDescriptor applicationDescriptorMock = mock(ApplicationDescriptor.class, RETURNS_DEEP_STUBS);
    when(applicationDescriptorMock.getClassLoaderConfiguration())
        .thenReturn(new ClassLoaderConfigurationBuilder().containing(new URL("file:/target/classes")).build());
    application = new DefaultMuleApplication(applicationDescriptorMock, parentArtifactClassLoader, emptyList(),
                                             null, mock(ServiceRepository.class),
                                             mock(ExtensionModelLoaderRepository.class),
                                             appLocation, null, null,
                                             mock(MemoryManagementService.class),
                                             mock(ArtifactConfigurationProcessor.class));
    application.setArtifactContext(mockArtifactContext);

    muleContext.getInjector().inject(this);
  }

  @Test
  public void initialState() {
    assertStatus(ApplicationStatus.CREATED);
  }

  @Test
  public void initialised() {
    // the context was initialised before we gave it to the application, so we need
    // to fire the notification again since the listener wasn't there
    notificationDispatcher.dispatch(new MuleContextNotification(muleContext, CONTEXT_INITIALISED));
    assertStatus(ApplicationStatus.INITIALISED);
  }

  @Test
  public void started() throws Exception {
    muleContext.start();
    assertStatus(ApplicationStatus.STARTED);
  }

  @Test
  public void stopped() throws Exception {
    muleContext.start();
    muleContext.stop();
    assertStatus(ApplicationStatus.STOPPED);
  }

  @Test
  public void destroyed() {
    muleContext.dispose();
    assertStatus(ApplicationStatus.DESTROYED);
  }

  @Test
  public void nullDeploymentClassLoaderAfterDispose() {
    ApplicationDescriptor descriptor = mock(ApplicationDescriptor.class);
    when(descriptor.getConfigResources()).thenReturn(emptySet());

    DefaultMuleApplication application =
        new DefaultMuleApplication(descriptor, mock(MuleApplicationClassLoader.class), emptyList(), null,
                                   null, null, appLocation, null, null, null, null, null);
    application.install();
    assertThat(application.getDeploymentClassLoader(), is(notNullValue()));
    application.dispose();
    assertThat(application.getDeploymentClassLoader(), is(nullValue()));
  }

  @Test
  public void deploymentFailedOnInit() {
    try {
      application.init();
      fail("Was expecting init to fail");
    } catch (Exception e) {
      assertStatus(ApplicationStatus.DEPLOYMENT_FAILED);
    }
  }

  @Test
  public void deploymentFailedOnStart() throws Exception {
    MuleContext mockedMuleContext = mock(MuleContext.class);
    when(mockArtifactContext.getMuleContext()).thenReturn(mockedMuleContext);
    doThrow(new MuleRuntimeException(createStaticMessage("error"))).when(mockedMuleContext).start();

    try {
      application.start();
      fail("Was expecting start to fail");
    } catch (Exception e) {
      assertStatus(ApplicationStatus.DEPLOYMENT_FAILED);
    }
  }

  private void assertStatus(final ApplicationStatus status) {
    PollingProber prober = new PollingProber(PROBER_TIMEOUT, PROBER_INTERVAL);
    prober.check(new JUnitProbe() {

      @Override
      protected boolean test() throws Exception {
        assertThat(application.getStatus(), is(status));
        return true;
      }

      @Override
      public String describeFailure() {
        return String.format("Application remained at status %s instead of moving to %s", application.getStatus().name(),
                             status.name());
      }
    });

  }
}
