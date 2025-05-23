/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.deployment.model.internal.policy;

import static org.mule.runtime.api.util.Preconditions.checkArgument;

import static org.apache.commons.lang3.StringUtils.isEmpty;

import org.mule.runtime.deployment.model.api.builder.RegionPluginClassLoadersFactory;
import org.mule.runtime.deployment.model.internal.AbstractArtifactClassLoaderBuilder;
import org.mule.runtime.module.artifact.api.classloader.ArtifactClassLoader;
import org.mule.runtime.module.artifact.api.classloader.DeployableArtifactClassLoaderFactory;
import org.mule.runtime.module.artifact.api.classloader.MuleDeployableArtifactClassLoader;
import org.mule.runtime.module.artifact.api.classloader.RegionClassLoader;
import org.mule.runtime.module.artifact.api.descriptor.ArtifactDescriptor;

/**
 * Builds the class loader to use on a {@link org.mule.runtime.deployment.model.api.policy.PolicyTemplate}
 */
public class PolicyTemplateClassLoaderBuilder extends AbstractArtifactClassLoaderBuilder<PolicyTemplateClassLoaderBuilder> {

  private final DeployableArtifactClassLoaderFactory artifactClassLoaderFactory;
  private ArtifactClassLoader parentClassLoader;

  /**
   * Creates an {@link AbstractArtifactClassLoaderBuilder}.
   *
   * @param artifactClassLoaderFactory factory for the classloader specific to the artifact resource and classes. Must be not
   *                                   null.
   * @param pluginClassLoadersFactory  creates the class loaders for the plugins included in the policy's region. Non null
   */
  public PolicyTemplateClassLoaderBuilder(DeployableArtifactClassLoaderFactory artifactClassLoaderFactory,
                                          RegionPluginClassLoadersFactory pluginClassLoadersFactory) {
    super(pluginClassLoadersFactory);
    this.artifactClassLoaderFactory = artifactClassLoaderFactory;
  }


  @Override
  protected ArtifactClassLoader createArtifactClassLoader(String artifactId, RegionClassLoader regionClassLoader) {
    return artifactClassLoaderFactory.create(artifactId, regionClassLoader, artifactDescriptor);
  }

  @Override
  protected ArtifactClassLoader getParentClassLoader() {
    return parentClassLoader;
  }

  @Override
  public MuleDeployableArtifactClassLoader build() {
    return (MuleDeployableArtifactClassLoader) super.build();
  }

  public PolicyTemplateClassLoaderBuilder setParentClassLoader(ArtifactClassLoader parentClassLoader) {
    this.parentClassLoader = parentClassLoader;

    return this;
  }

  @Override
  protected String getArtifactId(ArtifactDescriptor artifactDescriptor) {
    return getPolicyId(artifactDescriptor.getBundleDescriptor().getGroupId() + ":" +
        artifactDescriptor.getBundleDescriptor().getArtifactId() + ":" +
        artifactDescriptor.getBundleDescriptor().getVersion());
  }

  public String getPolicyId(String policyName) {
    checkArgument(!isEmpty(policyName), "policyName cannot be empty");

    return parentClassLoader.getArtifactId() + "/policy/" + policyName;
  }
}
