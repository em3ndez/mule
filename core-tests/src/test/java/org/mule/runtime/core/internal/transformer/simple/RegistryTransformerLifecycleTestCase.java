/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.transformer.simple;

import static org.mule.test.allure.AllureConstants.RegistryFeature.REGISTRY;
import static org.mule.test.allure.AllureConstants.RegistryFeature.TransfromersStory.TRANSFORMERS;

import static org.junit.Assert.assertEquals;

import org.mule.runtime.api.lifecycle.Disposable;
import org.mule.runtime.api.lifecycle.InitialisationException;
import org.mule.runtime.core.api.transformer.AbstractTransformer;
import org.mule.runtime.core.api.transformer.TransformerException;
import org.mule.runtime.core.internal.context.MuleContextWithRegistry;
import org.mule.runtime.core.internal.transformer.TransformersRegistry;
import org.mule.tck.junit4.AbstractMuleContextTestCase;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import io.qameta.allure.Feature;
import io.qameta.allure.Issue;
import io.qameta.allure.Story;

/**
 * Highlights the issue: MULE-4599 where dispose cannot be called on a transformer since it is a prototype in Spring, so spring
 * does not manage the object.
 */
@Issue("MULE-4599")
@Feature(REGISTRY)
@Story(TRANSFORMERS)
public class RegistryTransformerLifecycleTestCase extends AbstractMuleContextTestCase {

  @Test
  public void testLifecycleInTransientRegistry() throws Exception {
    TransformerLifecycleTracker transformer = new TransformerLifecycleTracker();
    transformer.setProperty("foo");
    ((MuleContextWithRegistry) muleContext).getRegistry().lookupObject(TransformersRegistry.class)
        .registerTransformer(transformer);
    muleContext.dispose();
    // Artifacts excluded from lifecycle in MuleContextLifecyclePhase gets lifecycle when an object is registered.
    assertRegistrationOnlyLifecycle(transformer);
  }

  private void assertRegistrationOnlyLifecycle(TransformerLifecycleTracker transformer) {
    assertEquals("[setProperty, initialise]", transformer.getTracker().toString());
  }

  public static class TransformerLifecycleTracker extends AbstractTransformer implements Disposable {

    private final List<String> tracker = new ArrayList<>();

    private String property;

    @Override
    protected Object doTransform(Object src, Charset encoding) throws TransformerException {
      tracker.add("doTransform");
      return null;
    }

    public String getProperty() {
      return property;
    }

    public void setProperty(String property) {
      tracker.add("setProperty");
    }

    public List<String> getTracker() {
      return tracker;
    }

    @Override
    public void initialise() throws InitialisationException {
      tracker.add("initialise");
    }

    @Override
    public void dispose() {
      tracker.add("dispose");
    }
  }
}
