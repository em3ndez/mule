/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.artifact.api.descriptor;

import static java.util.Collections.singleton;
import static java.util.Optional.empty;

import org.mule.api.annotation.NoExtend;
import org.mule.runtime.app.declaration.api.ArtifactDeclaration;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * Describes a Mule Application artifact.
 *
 * @since 4.5
 */
@NoExtend
public class ApplicationDescriptor extends DeployableArtifactDescriptor {

  public static final String DEFAULT_CONFIGURATION_RESOURCE = "mule-config.xml";
  public static final String REPOSITORY_FOLDER = "repository";
  public static final String MULE_APPLICATION_CLASSIFIER = "mule-application";
  public static final String MULE_DOMAIN_CLASSIFIER = "mule-domain";

  private String encoding;
  private Map<String, String> appProperties = new HashMap<>();
  private ArtifactDeclaration artifactDeclaration;
  private volatile Optional<BundleDescriptor> domainDescriptor;
  private String domainName;

  /**
   * Creates a new application descriptor
   *
   * @param name application name. Non empty.
   */
  public ApplicationDescriptor(String name) {
    super(name, empty());
  }

  public ApplicationDescriptor(String name, Optional<Properties> properties) {
    super(name, properties);
  }

  public String getEncoding() {
    return encoding;
  }

  public void setEncoding(String encoding) {
    this.encoding = encoding;
  }

  public Map<String, String> getAppProperties() {
    return appProperties;
  }

  public void setAppProperties(Map<String, String> appProperties) {
    this.appProperties = appProperties;
  }

  public String getDomainName() {
    return domainName;
  }

  /**
   * @return the optional descriptor of the domain on which the application is deployed into
   */
  public Optional<BundleDescriptor> getDomainDescriptor() {
    if (domainDescriptor == null) {
      synchronized (this) {
        if (domainDescriptor == null) {
          Optional<BundleDependency> domain =
              getClassLoaderConfiguration().getDependencies().stream()
                  .filter(d -> d.getDescriptor().getClassifier().isPresent()
                      && d.getDescriptor().getClassifier().get().equals(MULE_DOMAIN_CLASSIFIER))
                  .findFirst();
          domainDescriptor = domain.map(BundleDependency::getDescriptor);
        }
      }
    }

    return domainDescriptor;
  }

  public void setDomainName(String domainName) {
    this.domainName = domainName;
  }

  /**
   * @return programmatic definition of the application configuration.
   */
  public ArtifactDeclaration getArtifactDeclaration() {
    return artifactDeclaration;
  }

  /**
   * @param artifactDeclaration programmatic definition of the application configuration.
   */
  public void setArtifactDeclaration(ArtifactDeclaration artifactDeclaration) {
    this.artifactDeclaration = artifactDeclaration;
  }

  @Override
  protected Set<String> getDefaultConfigResources() {
    return singleton(DEFAULT_CONFIGURATION_RESOURCE);
  }
}
