/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.artifact.activation.internal.extension.discovery.test;

import static org.mule.runtime.extension.api.ExtensionConstants.ALL_SUPPORTED_JAVA_VERSIONS;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.mule.runtime.api.meta.model.ExtensionModel;
import org.mule.runtime.extension.api.provider.RuntimeExtensionModelProvider;

public class TestRuntimeExtensionModelProvider implements RuntimeExtensionModelProvider {

  @Override
  public ExtensionModel createExtensionModel() {
    ExtensionModel extModel = mock(ExtensionModel.class);
    when(extModel.getName()).thenReturn("testRuntime");
    when(extModel.getSupportedJavaVersions()).thenReturn(ALL_SUPPORTED_JAVA_VERSIONS);
    return extModel;
  }

}
