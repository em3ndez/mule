/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.config.internal.context;

import static org.mule.runtime.core.api.config.MuleProperties.OBJECT_NOTIFICATION_MANAGER;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;

import org.mule.runtime.config.utils.AppParserConfigurationBuilder;
import org.mule.runtime.core.api.config.ConfigurationBuilder;
import org.mule.runtime.core.internal.context.MuleContextWithRegistry;
import org.mule.tck.junit4.AbstractMuleContextTestCase;

import org.junit.Test;

public class GenericServerNotificationManagerConfiguratorTestCase extends AbstractMuleContextTestCase {

  @Override
  protected ConfigurationBuilder getBuilder() throws Exception {
    return new AppParserConfigurationBuilder(new String[] {"./generic-server-notification-manager-test.xml"});
  }

  @Test
  public void testRegistryHasAGenericServerNotificationManagerIfNoDynamicConfigIsPresent() {
    assertThat(((MuleContextWithRegistry) muleContext).getRegistry().lookupObject(OBJECT_NOTIFICATION_MANAGER), notNullValue());
  }

}
