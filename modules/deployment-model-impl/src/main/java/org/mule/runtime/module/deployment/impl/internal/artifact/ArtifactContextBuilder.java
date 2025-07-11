/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.deployment.impl.internal.artifact;

import static org.mule.runtime.api.util.Preconditions.checkArgument;
import static org.mule.runtime.api.util.Preconditions.checkState;
import static org.mule.runtime.core.api.config.MuleDeploymentProperties.MULE_ADD_TOOLING_OBJECTS_TO_REGISTRY;
import static org.mule.runtime.core.api.config.MuleDeploymentProperties.MULE_LAZY_INIT_DEPLOYMENT_PROPERTY;
import static org.mule.runtime.core.api.config.MuleDeploymentProperties.MULE_LAZY_INIT_ENABLE_XML_VALIDATIONS_DEPLOYMENT_PROPERTY;
import static org.mule.runtime.core.api.config.MuleProperties.APP_HOME_DIRECTORY_PROPERTY;
import static org.mule.runtime.core.api.config.MuleProperties.APP_NAME_PROPERTY;
import static org.mule.runtime.core.api.config.MuleProperties.DOMAIN_HOME_DIRECTORY_PROPERTY;
import static org.mule.runtime.core.api.config.MuleProperties.DOMAIN_NAME_PROPERTY;
import static org.mule.runtime.core.api.config.MuleProperties.OBJECT_CLASSLOADER_REPOSITORY;
import static org.mule.runtime.core.api.config.MuleProperties.OBJECT_POLICY_PROVIDER;
import static org.mule.runtime.core.api.config.bootstrap.ArtifactType.APP;
import static org.mule.runtime.core.api.config.bootstrap.ArtifactType.DOMAIN;
import static org.mule.runtime.core.api.config.bootstrap.ArtifactType.POLICY;
import static org.mule.runtime.core.api.util.ClassUtils.withContextClassLoader;
import static org.mule.runtime.core.api.util.UUID.getUUID;
import static org.mule.runtime.module.deployment.impl.internal.artifact.ArtifactFactoryUtils.getMuleContext;
import static org.mule.runtime.module.deployment.impl.internal.artifact.ArtifactFactoryUtils.isConfigLess;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.empty;

import org.mule.runtime.api.artifact.ArtifactCoordinates;
import org.mule.runtime.api.component.location.Location;
import org.mule.runtime.api.config.custom.ServiceConfigurator;
import org.mule.runtime.api.connectivity.ConnectivityTestingService;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.lifecycle.InitialisationException;
import org.mule.runtime.api.memory.management.MemoryManagementService;
import org.mule.runtime.api.metadata.ExpressionLanguageMetadataService;
import org.mule.runtime.api.service.ServiceRepository;
import org.mule.runtime.app.declaration.api.ArtifactDeclaration;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.config.ConfigurationBuilder;
import org.mule.runtime.core.api.config.ConfigurationException;
import org.mule.runtime.core.api.config.DefaultMuleConfiguration;
import org.mule.runtime.core.api.config.bootstrap.ArtifactType;
import org.mule.runtime.core.api.config.builders.SimpleConfigurationBuilder;
import org.mule.runtime.core.api.context.DefaultMuleContextFactory;
import org.mule.runtime.core.api.context.MuleContextBuilder;
import org.mule.runtime.core.api.context.notification.MuleContextListener;
import org.mule.runtime.core.api.policy.PolicyProvider;
import org.mule.runtime.deployment.model.api.DeployableArtifact;
import org.mule.runtime.deployment.model.api.artifact.ArtifactConfigurationProcessor;
import org.mule.runtime.deployment.model.api.artifact.ArtifactContext;
import org.mule.runtime.deployment.model.api.artifact.ArtifactContextConfiguration;
import org.mule.runtime.deployment.model.api.domain.Domain;
import org.mule.runtime.deployment.model.api.plugin.ArtifactPlugin;
import org.mule.runtime.module.artifact.activation.api.extension.discovery.ExtensionModelLoaderRepository;
import org.mule.runtime.module.artifact.activation.api.service.config.ContainerServiceConfigurator;
import org.mule.runtime.module.artifact.api.classloader.ClassLoaderRepository;
import org.mule.runtime.module.artifact.api.serializer.ArtifactObjectSerializer;
import org.mule.runtime.module.deployment.impl.internal.application.ApplicationMuleContextBuilder;
import org.mule.runtime.module.deployment.impl.internal.application.DefaultMuleApplication;
import org.mule.runtime.module.deployment.impl.internal.application.PolicyMuleContextBuilder;
import org.mule.runtime.module.deployment.impl.internal.domain.DomainMuleContextBuilder;
import org.mule.runtime.module.deployment.impl.internal.policy.ArtifactExtensionManagerFactory;
import org.mule.runtime.module.extension.api.manager.DefaultExtensionManagerFactory;
import org.mule.runtime.module.extension.api.manager.ExtensionManagerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Builder for creating an {@link ArtifactContext}. This is the preferred mechanism to create a {@code ArtifactContext} and a
 * {@link MuleContext} that can be retrieved from the {@link ArtifactContext} by calling {@link ArtifactContext#getMuleContext()}
 *
 * @since 4.0
 */
public class ArtifactContextBuilder {

  protected static final String EXECUTION_CLASSLOADER_WAS_NOT_SET = "Execution classloader was not set";
  protected static final String MULE_CONTEXT_ARTIFACT_PROPERTIES_CANNOT_BE_NULL =
      "MuleContext artifact properties cannot be null";
  protected static final String INSTALLATION_DIRECTORY_MUST_BE_A_DIRECTORY = "installation directory must be a directory";
  protected static final String ONLY_APPLICATIONS_OR_POLICIES_ARE_ALLOWED_TO_HAVE_A_PARENT_ARTIFACT =
      "Only applications or policies are allowed to have a parent artifact";
  protected static final String SERVICE_REPOSITORY_CANNOT_BE_NULL = "serviceRepository cannot be null";
  protected static final String EXTENSION_MODEL_LOADER_REPOSITORY_CANNOT_BE_NULL =
      "extensionModelLoaderRepository cannot be null";
  protected static final String CLASS_LOADER_REPOSITORY_CANNOT_BE_NULL = "classLoaderRepository cannot be null";
  protected static final String CLASS_LOADER_REPOSITORY_WAS_NOT_SET = "classLoaderRepository was not set";
  protected static final String SERVICE_CONFIGURATOR_CANNOT_BE_NULL = "serviceConfigurator cannot be null";

  protected static final String ACTION_ON_MULE_ARTIFACT_DEPLOYMENT_NULL = "actionOnMuleArtifactDeployment cannot be null";

  private List<ArtifactPlugin> artifactPlugins = new ArrayList<>();
  private ArtifactType artifactType = APP;
  private ArtifactConfigurationProcessor artifactConfigurationProcessor;
  private String[] configurationFiles = new String[0];
  private ArtifactDeclaration artifactDeclaration;
  private Map<String, String> artifactProperties = new HashMap<>();
  private String artifactName = getUUID();
  private MuleContextBuilder muleContextBuilder;
  private ClassLoader executionClassLoader;
  private File artifactInstallationDirectory;
  private MuleContextListener muleContextListener;
  private String defaultEncoding;
  private ServiceRepository serviceRepository = Collections::emptyList;
  private ExtensionModelLoaderRepository extensionModelLoaderRepository = loaderDescriber -> empty();
  private boolean enableLazyInit;
  private boolean disableXmlValidations;
  private boolean addToolingObjectsToRegistry;
  private List<ConfigurationBuilder> additionalBuilders = emptyList();
  private ClassLoaderRepository classLoaderRepository;
  private PolicyProvider policyProvider;
  private final List<ServiceConfigurator> serviceConfigurators = new ArrayList<>();
  private ExtensionManagerFactory extensionManagerFactory;
  private DeployableArtifact<?> parentArtifact;
  private Optional<Properties> properties = empty();
  private String dataFolderName;
  private MemoryManagementService memoryManagementService;
  private ExpressionLanguageMetadataService expressionLanguageMetadataService;
  private ArtifactCoordinates artifactCoordinates;
  private Consumer<ClassLoader> actionOnMuleArtifactDeployment = cl -> {
  };

  private ArtifactContextBuilder() {}

  /**
   * @return a new builder to create a {@link ArtifactContext} instance.
   */
  public static ArtifactContextBuilder newBuilder() {
    return new ArtifactContextBuilder();
  }

  /**
   * @return a new builder to create a {@link ArtifactContext} instance.
   */
  public static ArtifactContextBuilder newBuilder(ConfigurationBuilder... additionalBuilders) {
    final ArtifactContextBuilder builder = new ArtifactContextBuilder();
    builder.setAdditionalBuilders(asList(additionalBuilders));
    return builder;
  }

  private void setAdditionalBuilders(List<ConfigurationBuilder> additionalBuilders) {
    this.additionalBuilders = additionalBuilders;
  }

  /**
   * The {@code ArtifactType} defines the set of services that will be available in the {@code MuleContext}. For instance
   * {@code ArtifactType.DOMAIN} does not have any service required to execute flows.
   * <p>
   * By default {@code ArtifactType.APP} will be used, making all services available.
   *
   * @param artifactType artifact type for which a {@code MuleContext} must be created.
   * @return the builder
   */
  public ArtifactContextBuilder setArtifactType(ArtifactType artifactType) {
    this.artifactType = artifactType;
    return this;
  }

  public ArtifactContextBuilder setProperties(Optional<Properties> properties) {
    this.properties = properties;
    return this;
  }

  /**
   * @param folderName the folder name to use to store data in the file system related to the application.
   * @return the builder
   */
  public ArtifactContextBuilder setDataFolderName(String folderName) {
    this.dataFolderName = folderName;
    return this;
  }

  /**
   * Configures the {@link ArtifactConfigurationProcessor} to use.
   *
   * @param artifactConfigurationProcessor the processor to use for building the application model.
   * @return the builder
   *
   * @since 4.5
   */
  public ArtifactContextBuilder setArtifactConfigurationProcessor(ArtifactConfigurationProcessor artifactConfigurationProcessor) {
    this.artifactConfigurationProcessor = artifactConfigurationProcessor;
    return this;
  }

  /**
   * @param configurationFiles set of the artifact configuration files. These must be absolute paths.
   * @return the builder
   */
  public ArtifactContextBuilder setConfigurationFiles(String... configurationFiles) {
    this.configurationFiles = configurationFiles;
    return this;
  }

  /**
   * @param artifactDeclaration Artifact declaration.
   * @return
   */
  public ArtifactContextBuilder setArtifactDeclaration(ArtifactDeclaration artifactDeclaration) {
    this.artifactDeclaration = artifactDeclaration;
    return this;
  }

  /**
   * Allows to define a parent artifact which resources will be available to the context to be created. This is the mechanism
   * using for {@link Domain}s to define shared resources.
   *
   * @param parentArtifact artifact parent of the one being created.
   * @return the builder
   */
  public ArtifactContextBuilder setParentArtifact(DeployableArtifact<?> parentArtifact) {
    this.parentArtifact = parentArtifact;
    return this;
  }

  /**
   * The artifact properties define key value pairs that can be referenced from within the configuration files.
   *
   * @param artifactProperties properties use for the artifact configuration
   * @return the builder
   */
  public ArtifactContextBuilder setArtifactProperties(Map<String, String> artifactProperties) {
    checkArgument(artifactProperties != null, MULE_CONTEXT_ARTIFACT_PROPERTIES_CANNOT_BE_NULL);
    this.artifactProperties = artifactProperties;
    return this;
  }

  /**
   * Sets a meaningful name to identify the artifact. If not provided a UUID will be used.
   *
   * @param artifactName name to use to identify the artifact.
   * @return the builder
   */
  public ArtifactContextBuilder setArtifactName(String artifactName) {
    this.artifactName = artifactName;
    return this;
  }

  /**
   * Allows to set a listener that will be notified when the {@code MuleContext} is created, initialized or configured.
   *
   * @param muleContextListener listener of {@code MuleContext} notifications.
   * @return the builder
   */
  public ArtifactContextBuilder setMuleContextListener(MuleContextListener muleContextListener) {
    this.muleContextListener = muleContextListener;
    return this;
  }

  /**
   * Sets the file location where the artifact is installed. Must be a directory.
   *
   * @param location directory where the artifact is installed
   * @return the builder
   */
  public ArtifactContextBuilder setArtifactInstallationDirectory(File location) {
    checkArgument(location.isDirectory(), INSTALLATION_DIRECTORY_MUST_BE_A_DIRECTORY);
    this.artifactInstallationDirectory = location;
    return this;
  }

  /**
   * Sets the classloader that must be used to execute all {@code MuleContext} tasks such as running flows, doing connection
   * retries, etc.
   *
   * @param classloader classloader to use for executing logic within the {@code MuleContext}
   * @return the builder
   */
  public ArtifactContextBuilder setExecutionClassloader(ClassLoader classloader) {
    this.executionClassLoader = classloader;
    return this;
  }

  /**
   * Sets the default encoding for the {@code MuleContext} if the use did not define one explicitly within the configuration.
   *
   * @param defaultEncoding default encoding to use within the {@code MuleContext}
   * @return the builder
   */
  public ArtifactContextBuilder setDefaultEncoding(String defaultEncoding) {
    this.defaultEncoding = defaultEncoding;
    return this;
  }

  /**
   * Provides a list of {@link ArtifactPlugin} that describe all the extensions that need to be accessible by the
   * {@code MuleContext} to be created. It may also be that the configuration files make use of this extensions.
   *
   * @param artifactPlugins collection of artifact extensions that define resources as part of the {@code MuleContext} to be
   *                        created.
   * @return the builder
   */
  public ArtifactContextBuilder setArtifactPlugins(List<ArtifactPlugin> artifactPlugins) {
    this.artifactPlugins = artifactPlugins;
    return this;
  }

  /**
   * Provides a {@link ServiceRepository} containing all the services that will be accessible from the {@link MuleContext} to be
   * created.
   *
   * @param serviceRepository repository of available services. Non null.
   * @return the builder
   */
  public ArtifactContextBuilder setServiceRepository(ServiceRepository serviceRepository) {
    checkArgument(serviceRepository != null, SERVICE_REPOSITORY_CANNOT_BE_NULL);
    this.serviceRepository = serviceRepository;
    return this;
  }

  /**
   * Sets a {@link ExtensionModelLoaderRepository} that allows to retrieve the available extension loaders.
   *
   * @param extensionModelLoaderRepository {@link ExtensionModelLoaderRepository} with the available extension loaders. Non null.
   * @return the builder
   */
  public ArtifactContextBuilder setExtensionModelLoaderRepository(ExtensionModelLoaderRepository extensionModelLoaderRepository) {
    checkArgument(extensionModelLoaderRepository != null, EXTENSION_MODEL_LOADER_REPOSITORY_CANNOT_BE_NULL);
    this.extensionModelLoaderRepository = extensionModelLoaderRepository;
    return this;
  }

  public ArtifactContextBuilder setPolicyProvider(PolicyProvider policyProvider) {
    this.policyProvider = policyProvider;
    return this;
  }

  /**
   * Allows to lazily create the artifact resources.
   *
   * @param enableLazyInit when true the artifact resources from the mule configuration won't be created at startup. The artifact
   *                       components from the configuration will be created on demand when requested. For instance, when using
   *                       {@link DefaultMuleApplication#getConnectivityTestingService()} and then invoking
   *                       {@link ConnectivityTestingService#testConnection(Location)} will cause the creation of the component
   *                       requested to do test connectivity, if it was not already created. when false, the application will be
   *                       created completely at startup.
   * @return the builder
   */
  public ArtifactContextBuilder setEnableLazyInit(boolean enableLazyInit) {
    this.enableLazyInit = enableLazyInit;
    return this;
  }

  /**
   * Allows to create an {@link ArtifactContext} that will not check for XSD validations.
   *
   * @param disableXmlValidations {@code true} if the artifact context must be created without XSD validations, false otherwise.
   * @return the builder
   */
  public ArtifactContextBuilder setDisableXmlValidations(boolean disableXmlValidations) {
    this.disableXmlValidations = disableXmlValidations;
    return this;
  }

  /**
   * Allows to create an {@link ArtifactContext} that will contain the objects that enabling the tooling capabilities.
   *
   * @param addToolingObjectsToRegistry {@code true} if tooling capabilities will be available on the artifact context,
   *                                    {@code false} otherwise.
   * @return the builder
   */
  public ArtifactContextBuilder setAddToolingObjectsToRegistry(boolean addToolingObjectsToRegistry) {
    this.addToolingObjectsToRegistry = addToolingObjectsToRegistry;
    return this;
  }

  /**
   * Provides a {@link ClassLoaderRepository} containing all registered class loaders on the container.
   *
   * @param classLoaderRepository repository of available class loaders. Non null.
   * @return the builder
   */
  public ArtifactContextBuilder setClassLoaderRepository(ClassLoaderRepository classLoaderRepository) {
    checkState(classLoaderRepository != null, CLASS_LOADER_REPOSITORY_CANNOT_BE_NULL);
    this.classLoaderRepository = classLoaderRepository;
    return this;
  }

  /**
   * Adds a service configurator to configure the created context.
   *
   * @param serviceConfigurator used to configure the create context. Non null.
   * @return the builder
   */
  public ArtifactContextBuilder withServiceConfigurator(ServiceConfigurator serviceConfigurator) {
    checkState(serviceConfigurator != null, SERVICE_CONFIGURATOR_CANNOT_BE_NULL);
    this.serviceConfigurators.add(serviceConfigurator);
    return this;
  }

  public ArtifactContextBuilder setArtifactCoordinates(ArtifactCoordinates artifactCoordinates) {
    this.artifactCoordinates = artifactCoordinates;
    return this;
  }

  private Map<String, String> merge(Map<String, String> properties, Properties deploymentProperties) {
    if (deploymentProperties == null) {
      return properties;
    }

    Map<String, String> mergedProperties = new HashMap<>(properties);
    for (Map.Entry<Object, Object> entry : deploymentProperties.entrySet()) {
      mergedProperties.put(entry.getKey().toString(), entry.getValue().toString());
    }

    mergedProperties.put(MULE_ADD_TOOLING_OBJECTS_TO_REGISTRY, "" + addToolingObjectsToRegistry);
    mergedProperties.put(MULE_LAZY_INIT_DEPLOYMENT_PROPERTY, "" + enableLazyInit);
    mergedProperties.put(MULE_LAZY_INIT_ENABLE_XML_VALIDATIONS_DEPLOYMENT_PROPERTY,
                         "" + !disableXmlValidations);

    return mergedProperties;
  }

  /**
   * @return the {@code MuleContext} created with the provided configuration
   * @throws ConfigurationException  when there's a problem creating the {@code MuleContext}
   * @throws InitialisationException when a certain configuration component failed during initialisation phase
   */
  public ArtifactContext build() throws InitialisationException, ConfigurationException {
    checkState(executionClassLoader != null, EXECUTION_CLASSLOADER_WAS_NOT_SET);
    checkState(classLoaderRepository != null, CLASS_LOADER_REPOSITORY_WAS_NOT_SET);
    checkState(POLICY.equals(artifactType) || APP.equals(artifactType) || parentArtifact == null,
               ONLY_APPLICATIONS_OR_POLICIES_ARE_ALLOWED_TO_HAVE_A_PARENT_ARTIFACT);
    try {
      return withContextClassLoader(executionClassLoader, () -> {
        actionOnMuleArtifactDeployment.accept(executionClassLoader);
        List<ConfigurationBuilder> builders = new LinkedList<>(additionalBuilders);
        builders.add(new ArtifactBootstrapServiceDiscovererConfigurationBuilder(artifactPlugins));
        boolean hasEmptyParentDomain = isConfigLess(parentArtifact);
        if (extensionManagerFactory == null) {
          MuleContext parentMuleContext = getMuleContext(parentArtifact).orElse(null);
          if (parentMuleContext == null || hasEmptyParentDomain) {
            extensionManagerFactory =
                new ArtifactExtensionManagerFactory(artifactPlugins, extensionModelLoaderRepository,
                                                    new DefaultExtensionManagerFactory());
          } else {
            extensionManagerFactory = new CompositeArtifactExtensionManagerFactory(parentArtifact, extensionModelLoaderRepository,
                                                                                   artifactPlugins,
                                                                                   new DefaultExtensionManagerFactory());
          }

        }

        builders.add(new ArtifactExtensionManagerConfigurationBuilder(artifactPlugins,
                                                                      extensionManagerFactory));
        builders.add(createConfigurationBuilderFromApplicationProperties());

        AtomicReference<ArtifactContext> artifactContext = new AtomicReference<>();
        builders.add(new ConfigurationBuilder() {

          @Override
          public void configure(MuleContext muleContext) throws ConfigurationException {
            if (serviceRepository != null) {
              serviceConfigurators.add(new ContainerServiceConfigurator(serviceRepository.getServices()));
            }
            if (classLoaderRepository != null) {
              serviceConfigurators.add(customizationService -> customizationService
                  .registerCustomServiceImpl(OBJECT_CLASSLOADER_REPOSITORY,
                                             classLoaderRepository,
                                             true));
            }
            if (policyProvider != null) {
              serviceConfigurators.add(customizationService -> customizationService
                  .registerCustomServiceImpl(OBJECT_POLICY_PROVIDER,
                                             policyProvider,
                                             true));
            }
            ArtifactContextConfiguration.ArtifactContextConfigurationBuilder artifactContextConfigurationBuilder =
                ArtifactContextConfiguration.builder()
                    .setMuleContext(muleContext)
                    .setConfigResources(configurationFiles)
                    .setArtifactDeclaration(artifactDeclaration)
                    .setArtifactProperties(merge(artifactProperties, muleContext.getDeploymentProperties()))
                    .setArtifactType(artifactType)
                    .setEnableLazyInitialization(enableLazyInit)
                    .setDisableXmlValidations(disableXmlValidations)
                    .setAddToolingObjectsToRegistry(addToolingObjectsToRegistry)
                    .setServiceConfigurators(serviceConfigurators)
                    .setMemoryManagementService(memoryManagementService)
                    .setExpressionLanguageMetadataService(expressionLanguageMetadataService);

            if (parentArtifact != null && parentArtifact.getArtifactContext() != null) {
              artifactContextConfigurationBuilder.setParentArtifactContext(parentArtifact.getArtifactContext());
            }

            artifactContext
                .set(artifactConfigurationProcessor.createArtifactContext(artifactContextConfigurationBuilder.build()));
            ((DefaultMuleConfiguration) muleContext.getConfiguration()).setDataFolderName(dataFolderName);
          }

          @Override
          public void addServiceConfigurator(ServiceConfigurator serviceConfigurator) {
            // Nothing to do
          }
        });
        DefaultMuleContextFactory muleContextFactory = new DefaultMuleContextFactory();
        if (muleContextListener != null) {
          muleContextFactory.addListener(muleContextListener);
        }
        if (APP.equals(artifactType)) {
          muleContextBuilder = new ApplicationMuleContextBuilder(artifactName, artifactProperties, defaultEncoding);
        } else if (POLICY.equals(artifactType)) {
          muleContextBuilder = new PolicyMuleContextBuilder(artifactName, artifactProperties, defaultEncoding);
        } else {
          muleContextBuilder = new DomainMuleContextBuilder(artifactName);
        }
        muleContextBuilder.setExecutionClassLoader(this.executionClassLoader);
        ArtifactObjectSerializer objectSerializer =
            new ArtifactObjectSerializer(classLoaderRepository, this.executionClassLoader);
        muleContextBuilder.setObjectSerializer(objectSerializer);
        muleContextBuilder.setDeploymentProperties(properties);
        muleContextBuilder.setArtifactCoordinates(artifactCoordinates);

        if (parentArtifact != null) {
          builders.add(new ConnectionManagerConfigurationBuilder(parentArtifact));
        } else {
          builders.add(new ConnectionManagerConfigurationBuilder());
        }

        try {
          muleContextFactory.createMuleContext(builders, muleContextBuilder);
          return artifactContext.get();
        } catch (InitialisationException e) {
          throw new ConfigurationException(e);
        }
      });
    } catch (MuleRuntimeException e) {
      // We need this exception to be thrown as they are since the are possible causes of connectivity errors
      if (e.getCause() instanceof InitialisationException) {
        throw (InitialisationException) e.getCause();
      }
      if (e.getCause() instanceof ConfigurationException) {
        throw (ConfigurationException) e.getCause();
      }
      throw e;
    }
  }

  protected ConfigurationBuilder createConfigurationBuilderFromApplicationProperties() {
    if (artifactInstallationDirectory != null) {
      String absolutePath = artifactInstallationDirectory.getAbsolutePath();
      if (artifactType.equals(DOMAIN)) {
        artifactProperties.put(DOMAIN_HOME_DIRECTORY_PROPERTY, absolutePath);
      } else {
        artifactProperties.put(APP_HOME_DIRECTORY_PROPERTY, absolutePath);
      }
    }
    if (artifactType.equals(DOMAIN)) {
      artifactProperties.put(DOMAIN_NAME_PROPERTY, artifactName);
    } else {
      artifactProperties.put(APP_NAME_PROPERTY, artifactName);
    }
    return new SimpleConfigurationBuilder(artifactProperties);
  }

  public ArtifactContextBuilder setExtensionManagerFactory(ExtensionManagerFactory extensionManagerFactory) {
    this.extensionManagerFactory = extensionManagerFactory;

    return this;
  }

  public ArtifactContextBuilder setMemoryManagementService(MemoryManagementService memoryManagementService) {
    this.memoryManagementService = memoryManagementService;
    return this;
  }

  public ArtifactContextBuilder setExpressionLanguageMetadataService(ExpressionLanguageMetadataService expressionLanguageMetadataService) {
    this.expressionLanguageMetadataService = expressionLanguageMetadataService;
    return this;
  }

  /**
   * An action to be performed on the artifact deployment.
   *
   * @param actionOnMuleArtifactDeployment the {@link ClassLoader} used in the deployment process.
   * @return this builder.
   * @throws NullPointerException if {@param actionOnMuleArtifactDeployment} is null.
   */
  public ArtifactContextBuilder setActionOnMuleArtifactDeployment(Consumer<ClassLoader> actionOnMuleArtifactDeployment) {
    requireNonNull(actionOnMuleArtifactDeployment, ACTION_ON_MULE_ARTIFACT_DEPLOYMENT_NULL);
    this.actionOnMuleArtifactDeployment = actionOnMuleArtifactDeployment;
    return this;
  }
}
