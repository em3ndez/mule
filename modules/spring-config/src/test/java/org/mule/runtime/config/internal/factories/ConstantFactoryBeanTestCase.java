/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.config.internal.factories;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mule.runtime.core.api.Injector;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.context.MuleContextAware;
import org.mule.tck.junit4.AbstractMuleTestCase;
import org.mule.tck.size.SmallTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@SmallTest
public class ConstantFactoryBeanTestCase extends AbstractMuleTestCase {

  @Rule
  public MockitoRule rule = MockitoJUnit.rule();

  @Mock(extraInterfaces = {MuleContextAware.class})
  private Object value;
  private ConstantFactoryBean<Object> factoryBean;
  private MuleContext muleContext = mock(MuleContext.class);
  private Injector injector = mock(Injector.class);

  @Before
  public void before() throws Exception {
    factoryBean = new ConstantFactoryBean<>(value, true);
    when(muleContext.getInjector()).thenReturn(injector);
    factoryBean.setMuleContext(muleContext);
  }

  @Test
  public void returnsValue() throws Exception {
    assertThat(factoryBean.getObject(), is(sameInstance(value)));
  }

  @Test
  public void singleton() {
    assertThat(factoryBean.isSingleton(), is(true));
  }

  @Test
  public void assertClass() {
    assertThat(factoryBean.getObjectType() == value.getClass(), is(true));
  }

  @Test
  public void injection() throws Exception {
    factoryBean.getObject();
    verify(injector).inject(value);
  }

}
