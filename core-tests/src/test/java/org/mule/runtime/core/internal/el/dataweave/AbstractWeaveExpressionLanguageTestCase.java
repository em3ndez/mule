/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.el.dataweave;

import static java.nio.charset.Charset.defaultCharset;
import static java.util.Optional.of;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.mule.runtime.api.artifact.Registry;
import org.mule.runtime.api.el.DefaultExpressionLanguageFactoryService;
import org.mule.runtime.api.lifecycle.InitialisationException;
import org.mule.tck.junit4.AbstractMuleContextTestCase;
import org.mule.weave.v2.el.provider.WeaveDefaultExpressionLanguageFactoryService;

import org.junit.Before;

public abstract class AbstractWeaveExpressionLanguageTestCase extends AbstractMuleContextTestCase {

  protected DataWeaveExpressionLanguageAdaptor expressionLanguage;

  private DefaultExpressionLanguageFactoryService weaveExpressionExecutor;
  protected Registry registry = mock(Registry.class);

  @Before
  public void setUp() throws InitialisationException {
    weaveExpressionExecutor = new WeaveDefaultExpressionLanguageFactoryService(null);
    when(registry.lookupByType(DefaultExpressionLanguageFactoryService.class)).thenReturn(of(weaveExpressionExecutor));
    expressionLanguage =
        new DataWeaveExpressionLanguageAdaptor(muleContext, registry, muleContext.getConfiguration(),
                                               () -> defaultCharset(),
                                               weaveExpressionExecutor, getFeatureFlaggingService());
    expressionLanguage.initialise();
  }

}
