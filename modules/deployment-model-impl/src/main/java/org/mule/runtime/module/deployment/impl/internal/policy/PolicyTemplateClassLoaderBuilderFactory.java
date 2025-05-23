/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.deployment.impl.internal.policy;

import org.mule.runtime.container.internal.FilteringContainerClassLoader;
import org.mule.runtime.deployment.model.internal.policy.PolicyTemplateClassLoaderBuilder;

/**
 * Creates instances of {@link PolicyTemplateClassLoaderBuilder}
 */
public interface PolicyTemplateClassLoaderBuilderFactory {

  /**
   * Creates a new builder
   *
   * @return a new builder instance.
   */
  PolicyTemplateClassLoaderBuilder createArtifactClassLoaderBuilder();

  /**
   * Retrieves the filtering container class loader.
   *
   * @return The filtering container class loader.
   */
  FilteringContainerClassLoader getFilteringContainerClassLoader();
}
