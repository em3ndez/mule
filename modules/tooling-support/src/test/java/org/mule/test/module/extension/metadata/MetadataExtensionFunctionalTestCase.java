/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.test.module.extension.metadata;

import static org.mule.metadata.api.model.MetadataFormat.JAVA;
import static org.mule.metadata.api.utils.MetadataTypeUtils.getTypeId;
import static org.mule.runtime.api.message.Message.of;
import static org.mule.runtime.api.metadata.MetadataKeyBuilder.newKey;
import static org.mule.runtime.api.metadata.MetadataService.METADATA_SERVICE_KEY;
import static org.mule.runtime.api.util.Preconditions.checkArgument;
import static org.mule.tck.junit4.matcher.metadata.MetadataKeyResultFailureMatcher.isFailure;
import static org.mule.tck.junit4.matcher.metadata.MetadataKeyResultSuccessMatcher.isSuccess;
import static org.mule.test.allure.AllureConstants.SdkToolingSupport.SDK_TOOLING_SUPPORT;
import static org.mule.test.allure.AllureConstants.SdkToolingSupport.MetadataTypeResolutionStory.METADATA_SERVICE;
import static org.mule.test.metadata.extension.MetadataConnection.CAR;
import static org.mule.test.metadata.extension.MetadataConnection.PERSON;
import static org.mule.test.metadata.extension.resolver.TestMetadataResolverUtils.getCarMetadata;
import static org.mule.test.metadata.extension.resolver.TestMetadataResolverUtils.getHouseMetadata;
import static org.mule.test.metadata.extension.resolver.TestMetadataResolverUtils.getPersonMetadata;
import static org.mule.test.metadata.extension.resolver.TestMultiLevelKeyResolver.AMERICA;
import static org.mule.test.metadata.extension.resolver.TestMultiLevelKeyResolver.SAN_FRANCISCO;
import static org.mule.test.metadata.extension.resolver.TestMultiLevelKeyResolver.USA;
import static org.mule.test.module.extension.metadata.MetadataExtensionFunctionalTestCase.ResolutionType.DSL_RESOLUTION;
import static org.mule.test.module.extension.metadata.MetadataExtensionFunctionalTestCase.ResolutionType.EXPLICIT_RESOLUTION;

import static java.util.function.UnaryOperator.identity;
import static java.util.stream.Collectors.toMap;

import static org.apache.commons.lang3.StringUtils.isBlank;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.core.Is.is;

import org.mule.metadata.api.ClassTypeLoader;
import org.mule.metadata.api.builder.BaseTypeBuilder;
import org.mule.metadata.api.model.MetadataType;
import org.mule.metadata.api.model.ObjectType;
import org.mule.runtime.api.component.location.Location;
import org.mule.runtime.api.meta.Typed;
import org.mule.runtime.api.meta.model.ComponentModel;
import org.mule.runtime.api.meta.model.ConnectableComponentModel;
import org.mule.runtime.api.meta.model.OutputModel;
import org.mule.runtime.api.metadata.MetadataKey;
import org.mule.runtime.api.metadata.MetadataKeysContainer;
import org.mule.runtime.api.metadata.MetadataService;
import org.mule.runtime.api.metadata.descriptor.ComponentMetadataDescriptor;
import org.mule.runtime.api.metadata.descriptor.ParameterMetadataDescriptor;
import org.mule.runtime.api.metadata.resolving.FailureCode;
import org.mule.runtime.api.metadata.resolving.MetadataComponent;
import org.mule.runtime.api.metadata.resolving.MetadataFailure;
import org.mule.runtime.api.metadata.resolving.MetadataResult;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.api.extension.ExtensionManager;
import org.mule.runtime.extension.api.declaration.type.ExtensionsTypeLoaderFactory;
import org.mule.runtime.extension.api.metadata.NullMetadataKey;
import org.mule.runtime.module.extension.api.metadata.MultilevelMetadataKeyBuilder;
import org.mule.test.module.extension.AbstractExtensionFunctionalTestCase;
import org.mule.test.runner.RunnerDelegateTo;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import org.junit.Before;
import org.junit.runners.Parameterized;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import jakarta.inject.Inject;
import jakarta.inject.Named;

//TODO MULE-12809: Make MetadataTestCase use LazyMetadataService
@RunnerDelegateTo(Parameterized.class)
@Feature(SDK_TOOLING_SUPPORT)
@Story(METADATA_SERVICE)
public abstract class MetadataExtensionFunctionalTestCase<T extends ComponentModel> extends AbstractExtensionFunctionalTestCase {

  protected static final String METADATA_TEST = "metadata/metadata-tests.xml";
  protected static final String RUNTIME_METADATA_CONFIG = "metadata/metadata-runtime-tests.xml";
  protected static final String DSQL_QUERY = "dsql:SELECT id FROM Circle WHERE (diameter < 18)";

  protected static final String METADATA_TEST_STATIC_NO_REF_CONFIGURATION =
      "metadata/metadata-tests-static-no-ref-configuration.xml";
  protected static final String METADATA_TEST_DYNAMIC_NO_REF_CONFIGURATION =
      "metadata/metadata-tests-dynamic-no-ref-configuration.xml";
  protected static final String METADATA_TEST_DYNAMIC_IMPLICIT_CONFIGURATION =
      "metadata/metadata-tests-dynamic-implicit-configuration.xml";

  protected static final String CONTENT_METADATA_WITH_KEY_ID = "contentMetadataWithKeyId";
  protected static final String OUTPUT_METADATA_WITH_KEY_ID = "outputMetadataWithKeyId";
  protected static final String OUTPUT_METADATA_WITH_KEY_ID_USING_CONFIG = "outputMetadataWithKeyIdUsingConfig";
  protected static final String CONTENT_AND_OUTPUT_METADATA_WITH_KEY_ID = "contentAndOutputMetadataWithKeyId";
  protected static final String OUTPUT_ONLY_WITHOUT_CONTENT_PARAM = "outputOnlyWithoutContentParam";
  protected static final String CONTENT_ONLY_IGNORES_OUTPUT = "contentOnlyIgnoresOutput";
  protected static final String CONTENT_METADATA_WITHOUT_KEY_ID = "contentMetadataWithoutKeyId";
  protected static final String OUTPUT_METADATA_WITHOUT_KEY_PARAM = "outputMetadataWithoutKeyId";
  protected static final String CONTENT_AND_OUTPUT_METADATA_WITHOUT_KEY_ID = "contentAndOutputMetadataWithoutKeyId";
  protected static final String CONTENT_METADATA_WITHOUT_KEYS_WITH_KEY_ID = "contentMetadataWithoutKeysWithKeyId";
  protected static final String OUTPUT_METADATA_WITHOUT_KEYS_WITH_KEY_ID = "outputMetadataWithoutKeysWithKeyId";
  protected static final String CONTENT_AND_OUTPUT_CACHE_RESOLVER = "contentAndOutputWithCacheResolver";
  protected static final String SCOPE_WITH_OUTPUT_RESOLVER = "scopeWithOutputResolver";
  protected static final String SCOPE_WITH_PASS_THROUGH_OUTPUT_RESOLVER = "scopeWithPassThroughOutputResolver";
  protected static final String SCOPE_WITH_INPUT_RESOLVER = "scopeWithInputResolver";
  protected static final String ROUTER_WITH_METADATA_RESOLVER = "routerWithMetadataResolver";
  protected static final String ROUTER_WITH_ONE_OF_ROUTES_METADATA_RESOLVER = "routerWithOneOfRoutesMetadataResolver";
  protected static final String ROUTER_WITH_ALL_OF_ROUTES_METADATA_RESOLVER = "routerWithAllOfRoutesMetadataResolver";
  protected static final String CONTENT_AND_OUTPUT_CACHE_RESOLVER_WITH_ALTERNATIVE_CONFIG =
      "contentAndOutputWithCacheResolverWithSpecificConfig";
  protected static final String QUERY_FLOW = "queryOperation";
  protected static final String QUERY_LIST_FLOW = "queryListOperation";
  protected static final String NATIVE_QUERY_FLOW = "nativeQueryOperation";
  protected static final String NATIVE_QUERY_LIST_FLOW = "nativeQueryListOperation";

  protected static final String CONTENT_ONLY_CACHE_RESOLVER = "contentOnlyCacheResolver";
  protected static final String OUTPUT_AND_METADATA_KEY_CACHE_RESOLVER = "outputAndMetadataKeyCacheResolver";
  protected static final String EMPTY_PARTIAL_MULTILEVEL_KEYS = "emptyPartialMultilevelKeys";
  protected static final String SOURCE_METADATA = "sourceMetadata";
  protected static final String SOURCE_METADATA_WITH_MULTILEVEL = "sourceMetadataWithMultilevel";
  protected static final String SOURCE_METADATA_WITH_PARTIAL_MULTILEVEL = "sourceMetadataWithPartialMultilevel";
  protected static final String SOURCE_METADATA_WITH_PARTIAL_MULTILEVEL_SHOW_IN_DSL =
      "sourceMetadataWithPartialMultiLevelShowInDsl";
  protected static final String SOURCE_METADATA_WITH_CALLBACK_PARAMETERS = "sourceMetadataWithCallbackParameters";
  protected static final String SHOULD_INHERIT_OPERATION_PARENT_RESOLVERS = "shouldInheritOperationParentResolvers";
  protected static final String SIMPLE_MULTILEVEL_KEY_RESOLVER = "simpleMultiLevelKeyResolver";
  protected static final String INCOMPLETE_MULTILEVEL_KEY_RESOLVER = "incompleteMultiLevelKeyResolver";
  protected static final String TYPE_WITH_DECLARED_SUBTYPES_METADATA = "typeWithDeclaredSubtypesMetadata";
  protected static final String RESOLVER_WITH_DYNAMIC_CONFIG = "resolverWithDynamicConfig";
  protected static final String RESOLVER_WITH_IMPLICIT_DYNAMIC_CONFIG = "resolverWithImplicitDynamicConfig";
  protected static final String RESOLVER_WITH_IMPLICIT_STATIC_CONFIG = "resolverWithImplicitStaticConfig";
  protected static final String OUTPUT_ATTRIBUTES_WITH_DYNAMIC_METADATA = "sdkOutputAttributesWithDynamicMetadata";
  protected static final String OUTPUT_ATTRIBUTES_WITH_DECLARED_SUBTYPES_METADATA =
      "outputAttributesWithDeclaredSubtypesMetadata";
  protected static final String RESOLVER_CONTENT_WITH_CONTEXT_CLASSLOADER = "resolverContentWithContextClassLoader";
  protected static final String RESOLVER_OUTPUT_WITH_CONTEXT_CLASSLOADER = "resolverOutputWithContextClassLoader";
  protected static final String ENUM_METADATA_KEY = "enumMetadataKey";
  protected static final String BOOLEAN_METADATA_KEY = "booleanMetadataKey";
  protected static final String METADATA_KEY_DEFAULT_VALUE = "metadataKeyDefaultValue";
  protected static final String METADATA_KEY_OPTIONAL = "metadataKeyOptional";
  protected static final String MULTILEVEL_METADATA_KEY_DEFAULT_VALUE = "multilevelMetadataKeyDefaultValue";
  protected static final String OUTPUT_AND_MULTIPLE_INPUT_WITH_KEY_ID = "outputAndMultipleInputWithKeyId";

  protected static final String CONTINENT = "continent";
  protected static final String COUNTRY = "country";
  protected static final String CITY = "city";

  protected static final String SUCCESS_OBJECT_PARAMETER_NAME = "successObject";
  protected static final String ERROR_OBJECT_PARAMETER_NAME = "errorObject";
  protected static final String RESPONSE_PARAMETER_NAME = "response";

  protected static final MetadataKey PERSON_METADATA_KEY = newKey(PERSON).build();
  protected static final MetadataKey CAR_KEY = newKey(CAR).build();
  protected static final MetadataKey LOCATION_MULTILEVEL_KEY =
      MultilevelMetadataKeyBuilder.newKey(AMERICA, CONTINENT).withChild(MultilevelMetadataKeyBuilder.newKey(USA, COUNTRY)
          .withChild(MultilevelMetadataKeyBuilder.newKey(SAN_FRANCISCO, CITY))).build();

  protected static final NullMetadataKey NULL_METADATA_KEY = new NullMetadataKey();

  protected static final MetadataType ANY_TYPE = BaseTypeBuilder.create(JAVA).anyType().build();
  protected static final MetadataType VOID_TYPE = BaseTypeBuilder.create(JAVA).voidType().build();
  protected static final MetadataType STRING_TYPE = BaseTypeBuilder.create(JAVA).stringType().build();

  protected Map<String, ObjectType> types;

  @Inject
  private ExtensionManager extensionManager;

  @Inject
  @Named(METADATA_SERVICE_KEY)
  protected MetadataService metadataService;

  @Override
  public boolean enableLazyInit() {
    return true;
  }

  @Override
  public boolean disableXmlValidations() {
    return true;
  }

  @Override
  public boolean addToolingObjectsToRegistry() {
    return true;
  }

  protected MetadataType personType;
  protected MetadataType houseType;
  protected MetadataType carType;

  protected Location location;
  protected CoreEvent event;
  protected ClassTypeLoader typeLoader = ExtensionsTypeLoaderFactory.getDefault().createTypeLoader();
  protected BaseTypeBuilder typeBuilder = BaseTypeBuilder.create(JAVA);
  protected MetadataComponentDescriptorProvider<T> provider;

  protected ResolutionType resolutionType;

  MetadataExtensionFunctionalTestCase(ResolutionType resolutionType) {
    this.resolutionType = resolutionType;
  }

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> data() {
    return Arrays.asList(new Object[][] {
        {EXPLICIT_RESOLUTION},
        {DSL_RESOLUTION}
    });
  }

  @Before
  public void setup() throws Exception {
    event = CoreEvent.builder(testEvent()).message(of("")).build();
    personType = getPersonMetadata();
    houseType = getHouseMetadata();
    carType = getCarMetadata();
  }

  @Before
  public void loadTypes() {
    types = extensionManager.getExtension("Metadata").get().getTypes()
        .stream()
        .filter(type -> getTypeId(type).isPresent())
        .collect(toMap(type -> getTypeId(type).get(), identity()));
  }

  @Override
  protected boolean isDisposeContextPerClass() {
    return false;
  }

  public enum ResolutionType {
    EXPLICIT_RESOLUTION, DSL_RESOLUTION
  }

  MetadataResult<ComponentMetadataDescriptor<T>> getComponentDynamicMetadata(MetadataKey key) {
    checkArgument(location != null, "Unable to resolve Metadata. The location has not been configured.");
    return provider.resolveDynamicMetadata(metadataService, location, key);
  }

  ComponentMetadataDescriptor getSuccessComponentDynamicMetadata() {
    return getSuccessComponentDynamicMetadata(PERSON_METADATA_KEY);
  }

  ComponentMetadataDescriptor<T> getSuccessComponentDynamicMetadataWithKey(MetadataKey key) {
    return getSuccessComponentDynamicMetadata(key, this::assertResolvedKey);
  }

  ComponentMetadataDescriptor<T> getSuccessComponentDynamicMetadata(MetadataKey key) {
    return getSuccessComponentDynamicMetadata(key, (a, b) -> {
    });
  }

  private ComponentMetadataDescriptor<T> getSuccessComponentDynamicMetadata(MetadataKey key,
                                                                            BiConsumer<MetadataResult<ComponentMetadataDescriptor<T>>, MetadataKey> assertKeys) {
    MetadataResult<ComponentMetadataDescriptor<T>> componentMetadata = getComponentDynamicMetadata(key);
    assertThat(componentMetadata, isSuccess());
    assertKeys.accept(componentMetadata, key);
    return componentMetadata.get();
  }

  void assertMetadataFailure(MetadataFailure failure,
                             String msgContains,
                             FailureCode failureCode,
                             String traceContains,
                             MetadataComponent failingComponent) {
    assertMetadataFailure(failure, msgContains, failureCode, traceContains, failingComponent, "");
  }

  void assertMetadataFailure(MetadataFailure failure,
                             String msgContains,
                             FailureCode failureCode,
                             String traceContains,
                             MetadataComponent failingComponent,
                             String failingElement) {
    assertThat(failure.getFailureCode(), is(failureCode));
    if (!isBlank(msgContains)) {
      assertThat(failure.getMessage(), containsString(msgContains));
    }
    if (!isBlank(traceContains)) {
      assertThat(failure.getReason(), containsString(traceContains));
    }
    assertThat(failure.getFailingComponent(), is(failingComponent));
    if (!isBlank(failingElement)) {
      assertThat(failure.getFailingElement().isPresent(), is(true));
      assertThat(failure.getFailingElement().get(), is(failingElement));
    }
  }

  void assertExpectedOutput(ConnectableComponentModel model, MetadataType payloadType, MetadataType attributesType) {
    assertExpectedOutput(model.getOutput(), model.getOutputAttributes(), payloadType, attributesType);
  }

  void assertExpectedOutput(OutputModel output, OutputModel attributes, MetadataType payloadType, MetadataType attributesType) {
    assertExpectedType(output.getType(), payloadType);
    assertExpectedType(attributes.getType(), attributesType);
  }

  protected void assertExpectedType(MetadataType type, MetadataType expectedType) {
    assertThat(type, is(expectedType));
  }

  protected void assertExpectedParameterMetadataDescriptor(ParameterMetadataDescriptor parameterMetadataDescriptor,
                                                           MetadataType type,
                                                           boolean dynamic) {
    assertThat(parameterMetadataDescriptor.isDynamic(), is(dynamic));
    assertExpectedType(parameterMetadataDescriptor.getType(), type);
  }

  protected void assertExpectedParameterMetadataDescriptor(ParameterMetadataDescriptor parameterMetadataDescriptor,
                                                           MetadataType type) {
    assertThat(parameterMetadataDescriptor.isDynamic(), is(true));
    assertExpectedType(parameterMetadataDescriptor.getType(), type);
  }

  protected void assertExpectedType(Typed type, MetadataType expectedType) {
    assertThat(type.getType(), is(expectedType));
  }

  protected void assertExpectedType(Typed typedModel, MetadataType expectedType, boolean isDynamic) {
    assertThat(typedModel.getType(), is(expectedType));
    assertThat(typedModel.hasDynamicType(), is(isDynamic));
  }

  protected <T extends ComponentModel> void assertResolvedKey(MetadataResult<ComponentMetadataDescriptor<T>> result,
                                                              MetadataKey metadataKey) {
    assertThat(result.get().getMetadataAttributes().getKey().isPresent(), is(true));
    MetadataKey resultKey = result.get().getMetadataAttributes().getKey().get();
    assertSameKey(metadataKey, resultKey);

    MetadataKey child = metadataKey.getChilds().stream().findFirst().orElseGet(() -> null);
    MetadataKey otherChild = resultKey.getChilds().stream().findFirst().orElseGet(() -> null);
    while (child != null && otherChild != null) {
      assertSameKey(child, otherChild);
      child = child.getChilds().stream().findFirst().orElseGet(() -> null);
      otherChild = otherChild.getChilds().stream().findFirst().orElseGet(() -> null);
    }
    assertThat(child == null && otherChild == null, is(true));
  }

  private void assertSameKey(MetadataKey metadataKey, MetadataKey resultKey) {
    assertThat(resultKey.getId(), is(metadataKey.getId()));
    assertThat(resultKey.getChilds(), hasSize(metadataKey.getChilds().size()));
  }

  public Set<MetadataKey> getKeysFromContainer(MetadataKeysContainer metadataKeysContainer) {
    return metadataKeysContainer.getKeys(metadataKeysContainer.getCategories().iterator().next()).get();
  }

  public void assertSuccessResult(MetadataResult<?> result) {
    assertThat(result, isSuccess());
  }

  void assertFailureResult(MetadataResult<?> result, int failureNumber) {
    assertThat(result, isFailure(hasSize(failureNumber)));
  }

  interface MetadataComponentDescriptorProvider<T extends ComponentModel> {

    MetadataResult<ComponentMetadataDescriptor<T>> resolveDynamicMetadata(MetadataService metadataService,
                                                                          Location location, MetadataKey key);
  }
}
