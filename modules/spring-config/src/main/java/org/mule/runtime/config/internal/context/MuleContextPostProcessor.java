/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.config.internal.context;

import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.context.MuleContextAware;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Responsible for passing in the MuleContext instance for all objects in the registry that want it. For an object to get an
 * instance of the MuleContext it must implement MuleContextAware.
 *
 * @see MuleContextAware
 * @see org.mule.runtime.core.api.MuleContext
 *
 * @deprecated this interface is deprecated since {@link MuleContext} is deprecated. See {@link MuleContext} deprecation
 *             documentation for a replacement.
 */
@Deprecated
public class MuleContextPostProcessor implements BeanPostProcessor {

  private final MuleContext muleContext;

  public MuleContextPostProcessor(MuleContext muleContext) {
    this.muleContext = muleContext;
  }

  @Override
  public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
    if (bean instanceof MuleContextAware) {
      if (muleContext == null) {
        return bean;
      }

      ((MuleContextAware) bean).setMuleContext(muleContext);
    }
    return bean;
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
    return bean;
  }
}
