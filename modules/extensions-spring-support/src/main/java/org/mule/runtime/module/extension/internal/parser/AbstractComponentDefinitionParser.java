/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.parser;

import static org.mule.runtime.dsl.api.component.AttributeDefinition.Builder.fromChildCollectionConfiguration;
import static org.mule.runtime.dsl.api.component.AttributeDefinition.Builder.fromChildConfiguration;
import static org.mule.runtime.dsl.api.component.AttributeDefinition.Builder.fromFixedValue;
import static org.mule.runtime.dsl.api.component.AttributeDefinition.Builder.fromReferenceObject;
import static org.mule.runtime.dsl.api.component.AttributeDefinition.Builder.fromSimpleParameter;
import static org.mule.runtime.dsl.api.component.TypeDefinition.fromType;
import static org.mule.runtime.extension.api.ExtensionConstants.ERROR_MAPPINGS_PARAMETER_NAME;
import static org.mule.runtime.extension.api.ExtensionConstants.TARGET_PARAMETER_NAME;
import static org.mule.runtime.extension.api.ExtensionConstants.TARGET_VALUE_PARAMETER_NAME;

import org.mule.metadata.api.ClassTypeLoader;
import org.mule.runtime.api.meta.model.ComponentModel;
import org.mule.runtime.api.meta.model.ExtensionModel;
import org.mule.runtime.api.meta.model.nested.NestableElementModel;
import org.mule.runtime.api.meta.model.nested.NestedChainModel;
import org.mule.runtime.api.meta.model.parameter.ParameterGroupModel;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.config.ConfigurationException;
import org.mule.runtime.core.api.processor.Processor;
import org.mule.runtime.core.api.retry.policy.RetryPolicyTemplate;
import org.mule.runtime.core.api.streaming.CursorProviderFactory;
import org.mule.runtime.core.internal.exception.EnrichedErrorMapping;
import org.mule.runtime.dsl.api.component.ComponentBuildingDefinition.Builder;
import org.mule.runtime.extension.api.dsl.syntax.DslElementSyntax;
import org.mule.runtime.extension.api.dsl.syntax.resolver.DslSyntaxResolver;
import org.mule.runtime.module.extension.internal.config.dsl.ComponentMessageProcessorObjectFactory;
import org.mule.runtime.module.extension.internal.config.dsl.ExtensionDefinitionParser;
import org.mule.runtime.module.extension.internal.config.dsl.ExtensionParsingContext;
import org.mule.runtime.module.extension.internal.config.dsl.operation.OperationMessageProcessorObjectFactory;
import org.mule.runtime.module.extension.internal.runtime.operation.ComponentMessageProcessor;
import org.mule.runtime.module.extension.internal.runtime.operation.OperationMessageProcessor;

import java.util.List;
import java.util.Optional;

/**
 * A {@link ExtensionDefinitionParser} for parsing {@link OperationMessageProcessor} instances through a
 * {@link OperationMessageProcessorObjectFactory}
 *
 * @since 4.0
 */
public abstract class AbstractComponentDefinitionParser<T extends ComponentModel> extends ExtensionDefinitionParser {

  private final ExtensionModel extensionModel;
  private final T componentModel;
  private final DslElementSyntax operationDsl;

  public AbstractComponentDefinitionParser(Builder definition, ExtensionModel extensionModel,
                                           T componentModel,
                                           DslSyntaxResolver dslSyntaxResolver,
                                           ExtensionParsingContext parsingContext,
                                           Optional<ClassTypeLoader> typeLoader) {
    super(definition, dslSyntaxResolver, parsingContext, typeLoader);
    this.extensionModel = extensionModel;
    this.componentModel = componentModel;
    this.operationDsl = dslSyntaxResolver.resolve(componentModel);
  }

  @Override
  protected Builder doParse(Builder definitionBuilder)
      throws ConfigurationException {
    Builder finalBuilder = definitionBuilder.withIdentifier(operationDsl.getElementName())
        .withTypeDefinition(fromType(getMessageProcessorType()))
        .withObjectFactoryType(getMessageProcessorFactoryType())
        .withConstructorParameterDefinition(fromFixedValue(extensionModel).build())
        .withConstructorParameterDefinition(fromFixedValue(componentModel).build())
        .withConstructorParameterDefinition(fromReferenceObject(MuleContext.class).build())
        .withSetterParameterDefinition(CURSOR_PROVIDER_FACTORY_FIELD_NAME,
                                       fromChildConfiguration(CursorProviderFactory.class).build())
        .withSetterParameterDefinition("retryPolicyTemplate", fromChildConfiguration(RetryPolicyTemplate.class).build());

    if (hasOutputGroup()) {
      finalBuilder = finalBuilder
          .withSetterParameterDefinition(TARGET_PARAMETER_NAME,
                                         fromSimpleParameter(TARGET_PARAMETER_NAME).build())
          .withSetterParameterDefinition(TARGET_VALUE_PARAMETER_NAME,
                                         fromSimpleParameter(TARGET_VALUE_PARAMETER_NAME).build());
    }

    if (hasErrorMappingsGroup()) {
      finalBuilder = finalBuilder
          .withSetterParameterDefinition(ERROR_MAPPINGS_PARAMETER_NAME,
                                         fromChildCollectionConfiguration(EnrichedErrorMapping.class).build());
    }

    Optional<? extends NestableElementModel> nestedChain = componentModel.getNestedComponents().stream()
        .filter(NestedChainModel.class::isInstance)
        .findFirst();

    if (nestedChain.isPresent()) {
      // TODO MULE-13483: improve parsers to support things like [source, chainOfProcessors, errorHandler]
      // or [chainOfProcessors, errorHandler]
      finalBuilder = finalBuilder.withSetterParameterDefinition("nestedProcessors",
                                                                fromChildCollectionConfiguration(Processor.class).build());

      parseParameters(componentModel.getAllParameterModels());
    } else {
      List<ParameterGroupModel> inlineGroups = getInlineGroups(componentModel);
      parseParameters(getFlatParameters(inlineGroups, componentModel.getAllParameterModels()));
      for (ParameterGroupModel group : inlineGroups) {
        parseInlineParameterGroup(group);
      }

      // TODO MULE-13483
      parseNestedComponents(componentModel.getNestedComponents());
    }

    return finalBuilder;
  }

  protected boolean hasErrorMappingsGroup() {
    return false;
  }

  protected boolean hasOutputGroup() {
    return false;
  }

  protected abstract Class<? extends ComponentMessageProcessor> getMessageProcessorType();

  protected abstract Class<? extends ComponentMessageProcessorObjectFactory> getMessageProcessorFactoryType();

  public final T getComponentModel() {
    return componentModel;
  }
}
