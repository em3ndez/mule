/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.artifact.api.descriptor;

import static org.mule.runtime.api.util.Preconditions.checkArgument;
import static org.mule.runtime.module.artifact.api.descriptor.ArtifactDescriptor.MULE_ARTIFACT_FOLDER;
import static org.mule.runtime.module.artifact.api.descriptor.ArtifactDescriptor.MULE_ARTIFACT_JSON_DESCRIPTOR;

import static java.io.File.separator;
import static java.lang.String.format;
import static java.util.Collections.unmodifiableMap;

import static org.slf4j.LoggerFactory.getLogger;

import org.mule.api.annotation.NoExtend;
import org.mule.api.annotation.NoInstantiate;
import org.mule.runtime.api.artifact.ArtifactType;
import org.mule.runtime.api.deployment.meta.AbstractMuleArtifactModel;
import org.mule.runtime.api.deployment.meta.MuleArtifactLoaderDescriptor;
import org.mule.runtime.api.deployment.persistence.AbstractMuleArtifactModelJsonSerializer;
import org.mule.runtime.api.meta.MuleVersion;
import org.mule.runtime.core.api.util.IOUtils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import org.slf4j.Logger;

/**
 * Base class to create artifact descriptors
 *
 * @param <M> type of the artifact model that owns the descriptor
 * @param <T> type of descriptor being created
 *
 * @since 4.0
 */
@NoInstantiate
@NoExtend
public abstract class AbstractArtifactDescriptorFactory<M extends AbstractMuleArtifactModel, T extends ArtifactDescriptor>
    implements ArtifactDescriptorFactory<T> {

  private static final Logger LOGGER = getLogger(AbstractArtifactDescriptorFactory.class);

  public static final String ARTIFACT_DESCRIPTOR_DOES_NOT_EXISTS_ERROR = "Artifact descriptor does not exists: ";
  protected final DescriptorLoaderRepository descriptorLoaderRepository;
  private final ArtifactDescriptorValidator artifactDescriptorValidator;

  /**
   * Creates a new factory
   *
   * @param descriptorLoaderRepository         contains all the {@link ClassLoaderConfigurationLoader} registered on the
   *                                           container. Non null
   * @param artifactDescriptorValidatorBuilder {@link ArtifactDescriptorValidatorBuilder} to create the
   *                                           {@link ArtifactDescriptorValidator} in order to check the state of the descriptor
   *                                           once loaded.
   */
  public AbstractArtifactDescriptorFactory(DescriptorLoaderRepository descriptorLoaderRepository,
                                           ArtifactDescriptorValidatorBuilder artifactDescriptorValidatorBuilder) {
    checkArgument(descriptorLoaderRepository != null, "descriptorLoaderRepository cannot be null");
    this.descriptorLoaderRepository = descriptorLoaderRepository;

    this.artifactDescriptorValidator = artifactDescriptorValidatorBuilder
        .validateMinMuleVersion()
        .validateMuleProduct()
        .validateVersionFormat()
        .validateSupportedJavaVersions()
        .build();
  }

  @Override
  public T create(File artifactFolder, Optional<Properties> deploymentProperties) throws ArtifactDescriptorCreateException {
    final M artifactModel = createArtifactModel(artifactFolder);
    return createArtifact(artifactFolder, deploymentProperties, artifactModel);
  }

  public T createArtifact(File artifactFolder, Optional<Properties> deploymentProperties, M artifactModel) {
    T artifactDescriptor =
        loadFromJsonDescriptor(artifactFolder, artifactModel, deploymentProperties);
    return artifactDescriptor;
  }

  public M createArtifactModel(File artifactFolder) {
    final File artifactJsonFile = new File(artifactFolder, MULE_ARTIFACT_FOLDER + separator + getDescriptorFileName());
    if (!artifactJsonFile.exists()) {
      throw new ArtifactDescriptorCreateException(ARTIFACT_DESCRIPTOR_DOES_NOT_EXISTS_ERROR + artifactJsonFile);
    }

    return loadModelFromJson(getDescriptorContent(artifactJsonFile));
  }

  /**
   * @return the type of artifact begin created
   */
  protected abstract ArtifactType getArtifactType();

  /**
   * Loads a descriptor from an artifact model
   *
   * @param artifactLocation folder where the artifact is located, it can be a folder or file depending on the artifact type.
   * @param artifactModel    model representing the artifact.
   * @return a descriptor matching the provided model.
   */
  protected final T loadFromJsonDescriptor(File artifactLocation, M artifactModel, Optional<Properties> deploymentProperties) {

    artifactModel.validateModel(artifactLocation.getName());

    final T descriptor = createArtifactDescriptor(artifactLocation, artifactModel.getName(), deploymentProperties);
    if (artifactLocation.isDirectory()) {
      descriptor.setRootFolder(artifactLocation);
    }

    BundleDescriptor bundleDescriptor = getBundleDescriptor(artifactLocation, artifactModel, deploymentProperties);
    descriptor.setBundleDescriptor(bundleDescriptor);
    descriptor.setMinMuleVersion(new MuleVersion(artifactModel.getMinMuleVersion()));
    descriptor.setRequiredProduct(artifactModel.getRequiredProduct());

    ClassLoaderConfiguration classLoaderConfiguration =
        getClassLoaderConfiguration(artifactLocation, deploymentProperties, artifactModel.getClassLoaderModelLoaderDescriptor(),
                                    bundleDescriptor);
    descriptor.setClassLoaderConfiguration(classLoaderConfiguration);

    doDescriptorConfig(artifactModel, descriptor, artifactLocation);

    artifactDescriptorValidator.validate(descriptor);

    return descriptor;
  }

  /**
   * Generates an artifact model from a given JSON descriptor
   *
   * @param jsonString artifact descriptor in JSON format
   * @return the artifact model matching the provided JSON content.
   */
  protected M loadModelFromJson(String jsonString) {
    try {
      return deserializeArtifactModel(jsonString);
    } catch (IOException e) {
      throw new IllegalArgumentException("Cannot deserialize artifact descriptor from: " + jsonString);
    }
  }

  /**
   * @return the serializer for the artifact model
   */
  protected abstract AbstractMuleArtifactModelJsonSerializer<M> getMuleArtifactModelJsonSerializer();

  /**
   * Allows subclasses to customize descriptor based on the provided model
   *
   * @param artifactModel    artifact model created from the JSON descriptor
   * @param descriptor       descriptor created from the model and configured with common attributes
   * @param artifactLocation folder where the artifact is located, it can be a folder or file depending on the artifact type.
   */
  protected abstract void doDescriptorConfig(M artifactModel, T descriptor, File artifactLocation);

  /**
   * @param artifactLocation     folder where the artifact is located, it can be a folder or file depending on the artifact type.
   * @param name                 name for the created artifact
   * @param deploymentProperties properties provided for the deployment process.
   * @return a new descriptor of the type required by the factory.
   */
  protected abstract T createArtifactDescriptor(File artifactLocation, String name, Optional<Properties> deploymentProperties);

  private String getDescriptorFileName() {
    return MULE_ARTIFACT_JSON_DESCRIPTOR;
  }

  private String getDescriptorContent(File jsonFile) {
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("Loading artifact descriptor from '{}'..." + jsonFile.getAbsolutePath());
    }

    try (InputStream stream = new BufferedInputStream(new FileInputStream(jsonFile))) {
      return IOUtils.toString(stream);
    } catch (IOException e) {
      throw new IllegalArgumentException(format("Could not read extension describer on artifact '%s'",
                                                jsonFile.getAbsolutePath()),
                                         e);
    }
  }

  protected ClassLoaderConfiguration getClassLoaderConfiguration(File artifactFolder, Optional<Properties> deploymentProperties,
                                                                 MuleArtifactLoaderDescriptor classLoaderModelLoaderDescriptor,
                                                                 BundleDescriptor bundleDescriptor) {
    ClassLoaderConfigurationLoader classLoaderConfigurationLoader;
    try {
      classLoaderConfigurationLoader =
          descriptorLoaderRepository.get(classLoaderModelLoaderDescriptor.getId(), getArtifactType(),
                                         ClassLoaderConfigurationLoader.class);
    } catch (LoaderNotFoundException e) {
      throw new ArtifactDescriptorCreateException(invalidClassLoaderModelIdError(artifactFolder,
                                                                                 classLoaderModelLoaderDescriptor));
    }

    final ClassLoaderConfiguration classLoaderConfiguration;
    try {
      classLoaderConfiguration = classLoaderConfigurationLoader
          .load(artifactFolder, getClassLoaderConfigurationAttributes(deploymentProperties,
                                                                      classLoaderModelLoaderDescriptor,
                                                                      bundleDescriptor),
                getArtifactType());
    } catch (InvalidDescriptorLoaderException e) {
      throw new ArtifactDescriptorCreateException(e);
    }
    return classLoaderConfiguration;
  }

  protected Map<String, Object> getClassLoaderConfigurationAttributes(Optional<Properties> deploymentProperties,
                                                                      MuleArtifactLoaderDescriptor classLoaderModelLoaderDescriptor,
                                                                      BundleDescriptor bundleDescriptor) {
    // Adding BundleDescriptor to avoid resolving it again while loading the class loader configuration
    Map<String, Object> attributes = new HashMap<>();
    attributes.putAll(classLoaderModelLoaderDescriptor.getAttributes());
    attributes.put(BundleDescriptor.class.getName(), bundleDescriptor);

    return unmodifiableMap(attributes);
  }

  protected BundleDescriptor getBundleDescriptor(File appFolder, M artifactModel, Optional<Properties> deploymentProperties) {
    BundleDescriptorLoader bundleDescriptorLoader;
    try {
      bundleDescriptorLoader =
          descriptorLoaderRepository.get(artifactModel.getBundleDescriptorLoader().getId(), getArtifactType(),
                                         BundleDescriptorLoader.class);
    } catch (LoaderNotFoundException e) {
      throw new ArtifactDescriptorCreateException(invalidBundleDescriptorLoaderIdError(appFolder, artifactModel
          .getBundleDescriptorLoader()));
    }

    try {
      return bundleDescriptorLoader
          .load(appFolder, getBundleDescriptorAttributes(artifactModel.getBundleDescriptorLoader(), deploymentProperties),
                getArtifactType());
    } catch (InvalidDescriptorLoaderException e) {
      throw new ArtifactDescriptorCreateException(e);
    }
  }

  protected Map<String, Object> getBundleDescriptorAttributes(MuleArtifactLoaderDescriptor bundleDescriptorLoader,
                                                              Optional<Properties> deploymentPropertiesOptional) {
    return bundleDescriptorLoader.getAttributes();
  }

  private M deserializeArtifactModel(String jsonString) throws IOException {
    return getMuleArtifactModelJsonSerializer().deserialize(jsonString);
  }

  public static String invalidClassLoaderModelIdError(File artifactFolder,
                                                      MuleArtifactLoaderDescriptor classLoaderModelLoaderDescriptor) {
    return format("The identifier '%s' for a class loader model descriptor is not supported (error found while reading artifact '%s')",
                  classLoaderModelLoaderDescriptor.getId(),
                  artifactFolder.getAbsolutePath());

  }

  public static String invalidBundleDescriptorLoaderIdError(File artifactFolder,
                                                            MuleArtifactLoaderDescriptor bundleDescriptorLoader) {
    return format("The identifier '%s' for a bundle descriptor loader is not supported (error found while reading artifact '%s')",
                  bundleDescriptorLoader.getId(),
                  artifactFolder.getAbsolutePath());
  }
}
