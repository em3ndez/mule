/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.tooling.internal.dsl.model;

import static org.apache.commons.lang3.StringUtils.EMPTY;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.mule.runtime.app.declaration.api.component.location.Location.builderFromStringRepresentation;

import org.mule.runtime.api.component.location.Location;
import org.mule.runtime.app.declaration.api.ArtifactDeclaration;
import org.mule.runtime.app.declaration.api.ConfigurationElementDeclaration;
import org.mule.runtime.app.declaration.api.ParameterElementDeclaration;
import org.mule.runtime.app.declaration.api.ParameterizedElementDeclaration;
import org.mule.runtime.app.declaration.api.fluent.ParameterSimpleValue;
import org.mule.runtime.ast.api.ArtifactAst;
import org.mule.runtime.ast.api.ComponentAst;
import org.mule.runtime.metadata.internal.cache.ComponentAstBasedValueProviderCacheIdGenerator;
import org.mule.runtime.metadata.api.locator.ComponentLocator;
import org.mule.runtime.core.internal.value.cache.ValueProviderCacheId;
import org.mule.runtime.core.internal.value.cache.ValueProviderCacheIdGenerator;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.junit.Test;

public class ComponentAstValueProviderCacheIdGeneratorTestCase extends AbstractMockedValueProviderExtensionTestCase {

  private Optional<ValueProviderCacheId> computeIdFor(ArtifactDeclaration appDeclaration,
                                                      String location,
                                                      String parameterName)
      throws Exception {
    ArtifactAst app = loadApplicationModel(appDeclaration);
    Locator locator = new Locator(app);
    ValueProviderCacheIdGenerator cacheIdGenerator = new ComponentAstBasedValueProviderCacheIdGenerator(locator);
    ComponentAst component = getComponentAst(app, location);
    return cacheIdGenerator.getIdForResolvedValues(component, parameterName);
  }

  private Optional<ParameterizedElementDeclaration> getParameterElementDeclaration(ArtifactDeclaration artifactDeclaration,
                                                                                   String location) {
    AtomicBoolean isConnection = new AtomicBoolean(false);
    if (location.endsWith("/connection")) {
      isConnection.set(true);
      location = location.split("/connection")[0];
    }
    return artifactDeclaration.<ParameterizedElementDeclaration>findElement(builderFromStringRepresentation(location).build())
        .map(d -> isConnection.get() ? ((ConfigurationElementDeclaration) d).getConnection().orElse(null) : d);
  }

  private void modifyParameter(ArtifactDeclaration artifactDeclaration, String ownerLocation, String parameterName,
                               Consumer<ParameterElementDeclaration> parameterConsumer) {
    getParameterElementDeclaration(artifactDeclaration, ownerLocation)
        .map(
             owner -> owner.getParameterGroups()
                 .stream()
                 .flatMap(pg -> pg.getParameters().stream())
                 .filter(p -> p.getName().equals(parameterName))
                 .findAny()
                 .map(fp -> {
                   parameterConsumer.accept(fp);
                   return EMPTY; // Needed to avoid exception
                 })
                 .orElseThrow(() -> new RuntimeException("Could not find parameter to modify")))
        .orElseThrow(() -> new RuntimeException("Location not found"));
  }


  @Test
  public void idForParameterWithNoProviderInConfig() throws Exception {
    ArtifactDeclaration app = getBaseApp();
    assertThat(computeIdFor(app, MY_CONFIG, ACTING_PARAMETER_NAME).isPresent(), is(false));
  }

  @Test
  public void idForParameterWithNoProviderInSource() throws Exception {
    ArtifactDeclaration app = getBaseApp();
    assertThat(computeIdFor(app, SOURCE_LOCATION, ACTING_PARAMETER_NAME).isPresent(), is(false));
  }

  @Test
  public void idForParameterWithNoProviderInOperation() throws Exception {
    ArtifactDeclaration app = getBaseApp();
    assertThat(computeIdFor(app, OPERATION_LOCATION, ACTING_PARAMETER_NAME).isPresent(), is(false));
  }

  @Test
  public void idForConfigNoChanges() throws Exception {
    ArtifactDeclaration app = getBaseApp();
    Optional<ValueProviderCacheId> configId = computeIdFor(app, MY_CONFIG, PROVIDED_PARAMETER_NAME);
    assertThat(configId.isPresent(), is(true));
    checkIdsAreEqual(configId, computeIdFor(app, MY_CONFIG, PROVIDED_PARAMETER_NAME));
  }



  @Test
  public void idForConfigChangingNotActingParameters() throws Exception {
    ArtifactDeclaration app = getBaseApp();
    Optional<ValueProviderCacheId> configId = computeIdFor(app, MY_CONFIG, PROVIDED_PARAMETER_NAME);
    assertThat(configId.isPresent(), is(true));
    modifyParameter(app, MY_CONFIG, PARAMETER_REQUIRED_FOR_METADATA_NAME, p -> p.setValue(ParameterSimpleValue.of("newValue")));
    checkIdsAreEqual(configId, computeIdFor(app, MY_CONFIG, PROVIDED_PARAMETER_NAME));
  }

  @Test
  public void idForConfigChangingActingParameter() throws Exception {
    ArtifactDeclaration app = getBaseApp();
    Optional<ValueProviderCacheId> configId = computeIdFor(app, MY_CONFIG, PROVIDED_PARAMETER_NAME);
    assertThat(configId.isPresent(), is(true));
    modifyParameter(app, MY_CONFIG, ACTING_PARAMETER_NAME, p -> p.setValue(ParameterSimpleValue.of("newValue")));
    checkIdsAreDifferent(configId, computeIdFor(app, MY_CONFIG, PROVIDED_PARAMETER_NAME));
  }

  @Test
  public void idForConfigChangingActingParameterInGroup() throws Exception {
    ArtifactDeclaration app = getBaseApp();
    Optional<ValueProviderCacheId> configId = computeIdFor(app, MY_CONFIG, PROVIDED_PARAMETER_NAME);
    assertThat(configId.isPresent(), is(true));
    modifyParameter(app, MY_CONFIG, PARAMETER_IN_GROUP_NAME, p -> p.setValue(ParameterSimpleValue.of("newValue")));
    checkIdsAreDifferent(configId, computeIdFor(app, MY_CONFIG, PROVIDED_PARAMETER_NAME));
  }

  @Test
  public void idForConfiglessAndConnectionlessOperationNoChanges() throws Exception {
    ArtifactDeclaration app = getBaseApp();
    Optional<ValueProviderCacheId> opId = computeIdFor(app, OPERATION_LOCATION, PROVIDED_PARAMETER_NAME);
    assertThat(opId.isPresent(), is(true));
    checkIdsAreEqual(opId, computeIdFor(app, OPERATION_LOCATION, PROVIDED_PARAMETER_NAME));
  }

  @Test
  public void idForConfiglessAndConnectionlessOperationChangingActingParameter() throws Exception {
    ArtifactDeclaration app = getBaseApp();
    Optional<ValueProviderCacheId> opId = computeIdFor(app, OPERATION_LOCATION, PROVIDED_PARAMETER_NAME);
    assertThat(opId.isPresent(), is(true));
    modifyParameter(app, OPERATION_LOCATION, ACTING_PARAMETER_NAME, p -> p.setValue(ParameterSimpleValue.of("newValue")));
    checkIdsAreDifferent(opId, computeIdFor(app, OPERATION_LOCATION, PROVIDED_PARAMETER_NAME));
  }

  @Test
  public void idForConfiglessAndConnectionlessOperationChangingActingParameterInGroup() throws Exception {
    ArtifactDeclaration app = getBaseApp();
    Optional<ValueProviderCacheId> opId = computeIdFor(app, OPERATION_LOCATION, PROVIDED_PARAMETER_NAME);
    assertThat(opId.isPresent(), is(true));
    modifyParameter(app, OPERATION_LOCATION, PARAMETER_IN_GROUP_NAME, p -> p.setValue(ParameterSimpleValue.of("newValue")));
    checkIdsAreDifferent(opId, computeIdFor(app, OPERATION_LOCATION, PROVIDED_PARAMETER_NAME));
  }

  @Test
  public void idForConfiglessAndConnectionlessOperationChangesInConfig() throws Exception {
    ArtifactDeclaration app = getBaseApp();
    Optional<ValueProviderCacheId> opId = computeIdFor(app, OPERATION_LOCATION, PROVIDED_PARAMETER_NAME);
    assertThat(opId.isPresent(), is(true));
    modifyParameter(app, MY_CONFIG, PARAMETER_REQUIRED_FOR_METADATA_NAME, p -> p.setValue(ParameterSimpleValue.of("newValue")));
    modifyParameter(app, MY_CONFIG, ACTING_PARAMETER_NAME, p -> p.setValue(ParameterSimpleValue.of("newValue")));
    checkIdsAreEqual(opId, computeIdFor(app, OPERATION_LOCATION, PROVIDED_PARAMETER_NAME));
  }

  @Test
  public void idForConfiglessAndConnectionlessOperationChangesInConnection() throws Exception {
    ArtifactDeclaration app = getBaseApp();
    Optional<ValueProviderCacheId> opId = computeIdFor(app, OPERATION_LOCATION, PROVIDED_PARAMETER_NAME);
    assertThat(opId.isPresent(), is(true));
    modifyParameter(app, MY_CONNECTION, PARAMETER_REQUIRED_FOR_METADATA_NAME,
                    p -> p.setValue(ParameterSimpleValue.of("newValue")));
    modifyParameter(app, MY_CONNECTION, ACTING_PARAMETER_NAME, p -> p.setValue(ParameterSimpleValue.of("newValue")));
    checkIdsAreEqual(opId, computeIdFor(app, OPERATION_LOCATION, PROVIDED_PARAMETER_NAME));
  }


  @Test
  public void idForConfiglessAndConnectionlessSourceNoChanges() throws Exception {
    ArtifactDeclaration app = getBaseApp();
    Optional<ValueProviderCacheId> sourceId = computeIdFor(app, SOURCE_LOCATION, PROVIDED_PARAMETER_NAME);
    assertThat(sourceId.isPresent(), is(true));
    checkIdsAreEqual(sourceId, computeIdFor(app, SOURCE_LOCATION, PROVIDED_PARAMETER_NAME));
  }

  @Test
  public void idForConfiglessAndConnectionlessSourceChangingActingParameter() throws Exception {
    ArtifactDeclaration app = getBaseApp();
    Optional<ValueProviderCacheId> sourceId = computeIdFor(app, SOURCE_LOCATION, PROVIDED_PARAMETER_NAME);
    assertThat(sourceId.isPresent(), is(true));
    modifyParameter(app, SOURCE_LOCATION, ACTING_PARAMETER_NAME, p -> p.setValue(ParameterSimpleValue.of("newValue")));
    checkIdsAreDifferent(sourceId, computeIdFor(app, SOURCE_LOCATION, PROVIDED_PARAMETER_NAME));
  }

  @Test
  public void idForConfiglessAndConnectionlessSourceChangingActingParameterInGroup() throws Exception {
    ArtifactDeclaration app = getBaseApp();
    Optional<ValueProviderCacheId> sourceId = computeIdFor(app, SOURCE_LOCATION, PROVIDED_PARAMETER_NAME);
    assertThat(sourceId.isPresent(), is(true));
    modifyParameter(app, SOURCE_LOCATION, PARAMETER_IN_GROUP_NAME, p -> p.setValue(ParameterSimpleValue.of("newValue")));
    checkIdsAreDifferent(sourceId, computeIdFor(app, SOURCE_LOCATION, PROVIDED_PARAMETER_NAME));
  }

  @Test
  public void idForConfiglessAndConnectionlessSourceChangesInConfig() throws Exception {
    ArtifactDeclaration app = getBaseApp();
    Optional<ValueProviderCacheId> sourceId = computeIdFor(app, SOURCE_LOCATION, PROVIDED_PARAMETER_NAME);
    assertThat(sourceId.isPresent(), is(true));
    modifyParameter(app, MY_CONFIG, PARAMETER_REQUIRED_FOR_METADATA_NAME, p -> p.setValue(ParameterSimpleValue.of("newValue")));
    modifyParameter(app, MY_CONFIG, ACTING_PARAMETER_NAME, p -> p.setValue(ParameterSimpleValue.of("newValue")));
    checkIdsAreEqual(sourceId, computeIdFor(app, SOURCE_LOCATION, PROVIDED_PARAMETER_NAME));
  }

  @Test
  public void idForConfiglessAndConnectionlessSourceChangesInConnection() throws Exception {
    ArtifactDeclaration app = getBaseApp();
    Optional<ValueProviderCacheId> sourceId = computeIdFor(app, SOURCE_LOCATION, PROVIDED_PARAMETER_NAME);
    assertThat(sourceId.isPresent(), is(true));
    modifyParameter(app, MY_CONNECTION, PARAMETER_REQUIRED_FOR_METADATA_NAME,
                    p -> p.setValue(ParameterSimpleValue.of("newValue")));
    modifyParameter(app, MY_CONNECTION, ACTING_PARAMETER_NAME, p -> p.setValue(ParameterSimpleValue.of("newValue")));
    checkIdsAreEqual(sourceId, computeIdFor(app, SOURCE_LOCATION, PROVIDED_PARAMETER_NAME));
  }

  @Test
  public void idForConfigAwareOperationChangesInConfigNotRequiredForMetadata() throws Exception {
    ArtifactDeclaration app = getBaseApp();
    when(valueProviderModel.requiresConfiguration()).thenReturn(true);
    Optional<ValueProviderCacheId> opId = computeIdFor(app, OPERATION_LOCATION, PROVIDED_PARAMETER_NAME);
    assertThat(opId.isPresent(), is(true));
    modifyParameter(app, MY_CONFIG, ACTING_PARAMETER_NAME, p -> p.setValue(ParameterSimpleValue.of("newValue")));
    checkIdsAreEqual(opId, computeIdFor(app, OPERATION_LOCATION, PROVIDED_PARAMETER_NAME));
  }

  @Test
  public void idForConfigAwareOperationChangesInConfigRequiredForMetadata() throws Exception {
    ArtifactDeclaration app = getBaseApp();
    when(valueProviderModel.requiresConfiguration()).thenReturn(true);
    Optional<ValueProviderCacheId> opId = computeIdFor(app, OPERATION_LOCATION, PROVIDED_PARAMETER_NAME);
    assertThat(opId.isPresent(), is(true));
    modifyParameter(app, MY_CONFIG, PARAMETER_REQUIRED_FOR_METADATA_NAME, p -> p.setValue(ParameterSimpleValue.of("newValue")));
    checkIdsAreDifferent(opId, computeIdFor(app, OPERATION_LOCATION, PROVIDED_PARAMETER_NAME));
  }

  @Test
  public void idForConnectionAwareOperationChangesInConnectionNotRequiredForMetadata() throws Exception {
    ArtifactDeclaration app = getBaseApp();
    when(valueProviderModel.requiresConnection()).thenReturn(true);
    Optional<ValueProviderCacheId> opId = computeIdFor(app, OPERATION_LOCATION, PROVIDED_PARAMETER_NAME);
    assertThat(opId.isPresent(), is(true));
    modifyParameter(app, MY_CONNECTION, ACTING_PARAMETER_NAME, p -> p.setValue(ParameterSimpleValue.of("newValue")));
    checkIdsAreEqual(opId, computeIdFor(app, OPERATION_LOCATION, PROVIDED_PARAMETER_NAME));
  }

  @Test
  public void idForConnectionAwareOperationChangesInConnectionRequiredForMetadata() throws Exception {
    ArtifactDeclaration app = getBaseApp();
    when(valueProviderModel.requiresConnection()).thenReturn(true);
    Optional<ValueProviderCacheId> opId = computeIdFor(app, OPERATION_LOCATION, PROVIDED_PARAMETER_NAME);
    assertThat(opId.isPresent(), is(true));
    modifyParameter(app, MY_CONNECTION, PARAMETER_REQUIRED_FOR_METADATA_NAME,
                    p -> p.setValue(ParameterSimpleValue.of("newValue")));
    checkIdsAreDifferent(opId, computeIdFor(app, OPERATION_LOCATION, PROVIDED_PARAMETER_NAME));
  }

  @Test
  public void idForConfigAwareSourceChangesInConfigNotRequiredForMetadata() throws Exception {
    ArtifactDeclaration app = getBaseApp();
    when(valueProviderModel.requiresConfiguration()).thenReturn(true);
    Optional<ValueProviderCacheId> sourceId = computeIdFor(app, SOURCE_LOCATION, PROVIDED_PARAMETER_NAME);
    assertThat(sourceId.isPresent(), is(true));
    modifyParameter(app, MY_CONFIG, ACTING_PARAMETER_NAME, p -> p.setValue(ParameterSimpleValue.of("newValue")));
    checkIdsAreEqual(sourceId, computeIdFor(app, SOURCE_LOCATION, PROVIDED_PARAMETER_NAME));
  }

  @Test
  public void idForConfigAwareSourceChangesInConfigRequiredForMetadata() throws Exception {
    ArtifactDeclaration app = getBaseApp();
    when(valueProviderModel.requiresConfiguration()).thenReturn(true);
    Optional<ValueProviderCacheId> sourceId = computeIdFor(app, SOURCE_LOCATION, PROVIDED_PARAMETER_NAME);
    assertThat(sourceId.isPresent(), is(true));
    modifyParameter(app, MY_CONFIG, PARAMETER_REQUIRED_FOR_METADATA_NAME, p -> p.setValue(ParameterSimpleValue.of("newValue")));
    checkIdsAreDifferent(sourceId, computeIdFor(app, SOURCE_LOCATION, PROVIDED_PARAMETER_NAME));
  }

  @Test
  public void idForConnectionAwareSourceChangesInConnectionNotRequiredForMetadata() throws Exception {
    ArtifactDeclaration app = getBaseApp();
    when(valueProviderModel.requiresConnection()).thenReturn(true);
    Optional<ValueProviderCacheId> sourceId = computeIdFor(app, SOURCE_LOCATION, PROVIDED_PARAMETER_NAME);
    assertThat(sourceId.isPresent(), is(true));
    modifyParameter(app, MY_CONNECTION, ACTING_PARAMETER_NAME, p -> p.setValue(ParameterSimpleValue.of("newValue")));
    checkIdsAreEqual(sourceId, computeIdFor(app, SOURCE_LOCATION, PROVIDED_PARAMETER_NAME));
  }

  @Test
  public void idForConnectionAwareSourceChangesInConnectionRequiredForMetadata() throws Exception {
    ArtifactDeclaration app = getBaseApp();
    when(valueProviderModel.requiresConnection()).thenReturn(true);
    Optional<ValueProviderCacheId> sourceId = computeIdFor(app, SOURCE_LOCATION, PROVIDED_PARAMETER_NAME);
    assertThat(sourceId.isPresent(), is(true));
    modifyParameter(app, MY_CONNECTION, PARAMETER_REQUIRED_FOR_METADATA_NAME,
                    p -> p.setValue(ParameterSimpleValue.of("newValue")));
    checkIdsAreDifferent(sourceId, computeIdFor(app, SOURCE_LOCATION, PROVIDED_PARAMETER_NAME));
  }

  @Test
  public void equalConfigsWithDifferentNameGetSameHash() throws Exception {
    ArtifactDeclaration app = getBaseApp();
    ConfigurationElementDeclaration config = (ConfigurationElementDeclaration) app.getGlobalElements().get(0);
    app.addGlobalElement(declareConfig(config.getConnection().get(), "newName",
                                       PARAMETER_REQUIRED_FOR_METADATA_DEFAULT_VALUE,
                                       ACTING_PARAMETER_DEFAULT_VALUE,
                                       PROVIDED_PARAMETER_DEFAULT_VALUE,
                                       PARAMETER_IN_GROUP_DEFAULT_VALUE));
    Optional<ValueProviderCacheId> config1Id = computeIdFor(app, MY_CONFIG, PROVIDED_PARAMETER_NAME);
    Optional<ValueProviderCacheId> config2Id = computeIdFor(app, "newName", PROVIDED_PARAMETER_NAME);
    checkIdsAreEqual(config1Id, config2Id);
  }

  @Test
  public void differentConfigsWithDifferentProviderIdGetDifferentHash() throws Exception {
    ArtifactDeclaration app = getBaseApp();
    ConfigurationElementDeclaration config = (ConfigurationElementDeclaration) app.getGlobalElements().get(0);
    app.addGlobalElement(declareOtherConfig(config.getConnection().get(), "newName",
                                            PARAMETER_REQUIRED_FOR_METADATA_DEFAULT_VALUE,
                                            ACTING_PARAMETER_DEFAULT_VALUE,
                                            PROVIDED_PARAMETER_DEFAULT_VALUE,
                                            PARAMETER_IN_GROUP_DEFAULT_VALUE));
    Optional<ValueProviderCacheId> config1Id = computeIdFor(app, MY_CONFIG, PROVIDED_PARAMETER_NAME);
    when(valueProviderModel.getProviderId()).thenReturn("newValueProviderId");
    Optional<ValueProviderCacheId> config2Id = computeIdFor(app, "newName", PROVIDED_PARAMETER_NAME);
    checkIdsAreDifferent(config1Id, config2Id);
  }

  @Test
  public void differentConfigsWithSameProviderIdGetSameHash() throws Exception {
    ArtifactDeclaration app = getBaseApp();
    ConfigurationElementDeclaration config = (ConfigurationElementDeclaration) app.getGlobalElements().get(0);
    app.addGlobalElement(declareOtherConfig(config.getConnection().get(), "newName",
                                            PARAMETER_REQUIRED_FOR_METADATA_DEFAULT_VALUE,
                                            ACTING_PARAMETER_DEFAULT_VALUE,
                                            PROVIDED_PARAMETER_DEFAULT_VALUE,
                                            PARAMETER_IN_GROUP_DEFAULT_VALUE));
    Optional<ValueProviderCacheId> config1Id = computeIdFor(app, MY_CONFIG, PROVIDED_PARAMETER_NAME);
    Optional<ValueProviderCacheId> config2Id = computeIdFor(app, "newName", PROVIDED_PARAMETER_NAME);
    checkIdsAreEqual(config1Id, config2Id);
  }

  @Test
  public void differentValueProviderIdGetsDifferentHash() throws Exception {
    ArtifactDeclaration app = getBaseApp();
    when(valueProviderModel.requiresConnection()).thenReturn(true);
    when(valueProviderModel.requiresConfiguration()).thenReturn(true);
    Optional<ValueProviderCacheId> opId1 = computeIdFor(app, OPERATION_LOCATION, PROVIDED_PARAMETER_NAME);
    when(valueProviderModel.getProviderId()).thenReturn("newValueProviderId");
    Optional<ValueProviderCacheId> opId2 = computeIdFor(app, OPERATION_LOCATION, PROVIDED_PARAMETER_NAME);
    checkIdsAreDifferent(opId1, opId2);
  }

  @Test
  public void differentOperationsWithDifferentProviderIdGetsDifferentHash() throws Exception {
    ArtifactDeclaration app = getBaseApp();
    Optional<ValueProviderCacheId> opId1 = computeIdFor(app, OPERATION_LOCATION, PROVIDED_PARAMETER_NAME);
    when(valueProviderModel.getProviderId()).thenReturn("newValueProviderId");
    Optional<ValueProviderCacheId> opId2 = computeIdFor(app, OTHER_OPERATION_LOCATION, PROVIDED_PARAMETER_NAME);
    checkIdsAreDifferent(opId1, opId2);
  }

  @Test
  public void differentOperationsWithSameValueProviderIdGetsSameHash() throws Exception {
    ArtifactDeclaration app = getBaseApp();
    Optional<ValueProviderCacheId> opId1 = computeIdFor(app, OPERATION_LOCATION, PROVIDED_PARAMETER_NAME);
    Optional<ValueProviderCacheId> opId2 = computeIdFor(app, OTHER_OPERATION_LOCATION, PROVIDED_PARAMETER_NAME);
    checkIdsAreEqual(opId1, opId2);
  }

  private static class Locator implements ComponentLocator<ComponentAst> {

    private final Map<Location, ComponentAst> components = new HashMap<>();

    Locator(ArtifactAst app) {
      app.topLevelComponentsStream().forEach(this::addComponent);
    }

    @Override
    public Optional<ComponentAst> get(Location location) {
      return Optional.ofNullable(components.get(location));
    }

    private Location getLocation(ComponentAst component) {
      return Location.builderFromStringRepresentation(component.getLocation().getLocation()).build();
    }

    private void addComponent(ComponentAst component) {
      components.put(getLocation(component), component);
      component.directChildrenStream().forEach(this::addComponent);
    }
  }

}
