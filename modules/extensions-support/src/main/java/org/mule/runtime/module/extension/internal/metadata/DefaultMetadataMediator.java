/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.metadata;

import static org.mule.runtime.api.metadata.resolving.FailureCode.INVALID_METADATA_KEY;
import static org.mule.runtime.api.metadata.resolving.MetadataFailure.Builder.newFailure;
import static org.mule.runtime.api.metadata.resolving.MetadataResult.failure;
import static org.mule.runtime.api.metadata.resolving.MetadataResult.success;
import static org.mule.runtime.extension.api.util.ExtensionModelUtils.isRouter;
import static org.mule.runtime.extension.api.util.ExtensionModelUtils.isScope;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;

import org.mule.metadata.message.api.MessageMetadataType;
import org.mule.runtime.api.meta.model.ComponentModel;
import org.mule.runtime.api.meta.model.ComponentModelVisitor;
import org.mule.runtime.api.meta.model.HasOutputModel;
import org.mule.runtime.api.meta.model.OutputModel;
import org.mule.runtime.api.meta.model.construct.ConstructModel;
import org.mule.runtime.api.meta.model.nested.NestedChainModel;
import org.mule.runtime.api.meta.model.nested.NestedComponentModel;
import org.mule.runtime.api.meta.model.nested.NestedRouteModel;
import org.mule.runtime.api.meta.model.operation.OperationModel;
import org.mule.runtime.api.meta.model.parameter.ParameterGroupModel;
import org.mule.runtime.api.meta.model.parameter.ParameterModel;
import org.mule.runtime.api.meta.model.source.SourceCallbackModel;
import org.mule.runtime.api.meta.model.source.SourceModel;
import org.mule.runtime.api.metadata.MetadataAttributes;
import org.mule.runtime.api.metadata.MetadataContext;
import org.mule.runtime.api.metadata.MetadataKey;
import org.mule.runtime.api.metadata.MetadataKeyProvider;
import org.mule.runtime.api.metadata.MetadataKeysContainer;
import org.mule.runtime.api.metadata.MetadataResolvingException;
import org.mule.runtime.api.metadata.descriptor.ComponentMetadataDescriptor;
import org.mule.runtime.api.metadata.descriptor.InputMetadataDescriptor;
import org.mule.runtime.api.metadata.descriptor.OutputMetadataDescriptor;
import org.mule.runtime.api.metadata.descriptor.ParameterMetadataDescriptor;
import org.mule.runtime.api.metadata.descriptor.RouterInputMetadataDescriptor;
import org.mule.runtime.api.metadata.descriptor.ScopeInputMetadataDescriptor;
import org.mule.runtime.api.metadata.descriptor.TypeMetadataDescriptor;
import org.mule.runtime.api.metadata.resolving.InputTypeResolver;
import org.mule.runtime.api.metadata.resolving.MetadataFailure;
import org.mule.runtime.api.metadata.resolving.MetadataResult;
import org.mule.runtime.api.metadata.resolving.OutputTypeResolver;
import org.mule.runtime.api.metadata.resolving.TypeKeysResolver;
import org.mule.runtime.api.util.Reference;
import org.mule.runtime.extension.api.annotation.metadata.MetadataKeyId;
import org.mule.runtime.extension.api.metadata.MetadataResolverFactory;
import org.mule.runtime.extension.api.metadata.NullMetadataKey;
import org.mule.runtime.extension.api.model.ImmutableOutputModel;
import org.mule.runtime.extension.api.model.construct.ImmutableConstructModel;
import org.mule.runtime.extension.api.model.operation.ImmutableOperationModel;
import org.mule.runtime.extension.api.model.parameter.ImmutableParameterGroupModel;
import org.mule.runtime.extension.api.model.parameter.ImmutableParameterModel;
import org.mule.runtime.extension.api.model.source.ImmutableSourceCallbackModel;
import org.mule.runtime.extension.api.model.source.ImmutableSourceModel;
import org.mule.runtime.extension.api.property.MetadataKeyIdModelProperty;
import org.mule.runtime.extension.api.property.MetadataKeyPartModelProperty;
import org.mule.runtime.module.extension.api.metadata.PropagatedParameterTypeResolver;
import org.mule.runtime.module.extension.api.runtime.resolver.ParameterValueResolver;
import org.mule.runtime.module.extension.api.tooling.metadata.MetadataMediator;
import org.mule.runtime.module.extension.internal.util.ReflectionCache;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import com.google.common.collect.ImmutableList;

/**
 * Resolves a Component's Metadata by coordinating the several moving parts that are affected by the Metadata fetching process, so
 * that such pieces can remain decoupled.
 * <p/>
 * This mediator will coordinate the resolvers: {@link MetadataResolverFactory}, {@link TypeKeysResolver},
 * {@link InputTypeResolver} and {@link OutputTypeResolver}, and the descriptors that represent their results:
 * {@link ComponentMetadataDescriptor}, {@link OutputMetadataDescriptor} and {@link TypeMetadataDescriptor}
 *
 * @since 4.0
 */
public final class DefaultMetadataMediator<T extends ComponentModel> implements MetadataMediator {

  protected final T component;
  private final ReflectionCache reflectionCache;
  private final List<ParameterModel> metadataKeyParts;
  private final MetadataKeysDelegate keysDelegate;
  private final MetadataOutputDelegate outputDelegate;
  private final MetadataInputDelegate inputDelegate;
  private final MetadataKeyIdObjectResolver keyIdObjectResolver;
  private final Optional<MetadataInputDelegate> successCallbackInputDelegate;
  private final Optional<MetadataInputDelegate> errorCallbackInputDelegate;
  private String keyContainerName = null;

  public DefaultMetadataMediator(T componentModel, ReflectionCache reflectionCache) {
    this.component = componentModel;
    this.reflectionCache = reflectionCache;
    this.metadataKeyParts = getMetadataKeyParts(componentModel);
    this.keysDelegate = new MetadataKeysDelegate(componentModel, metadataKeyParts);
    this.keyIdObjectResolver = new MetadataKeyIdObjectResolver(component);
    this.outputDelegate = new MetadataOutputDelegate(componentModel);
    this.inputDelegate = new MetadataInputDelegate(componentModel);
    if (componentModel instanceof SourceModel) {
      successCallbackInputDelegate = ((SourceModel) componentModel).getSuccessCallback()
          .map(successCallbackModel -> new MetadataInputDelegate(successCallbackModel));
      errorCallbackInputDelegate = ((SourceModel) componentModel).getErrorCallback()
          .map(errorCallbackModel -> new MetadataInputDelegate(errorCallbackModel));
    } else {
      successCallbackInputDelegate = empty();
      errorCallbackInputDelegate = empty();
    }

    componentModel.getModelProperty(MetadataKeyIdModelProperty.class)
        .ifPresent(keyIdMP -> keyContainerName = keyIdMP.getParameterName());
  }

  /**
   * Resolves the list of types available for the Content or Output of the associated {@link MetadataKeyProvider} Component,
   * representing them as a list of {@link MetadataKey}.
   * <p>
   * If no {@link MetadataKeyId} is present in the component's input parameters, then a {@link NullMetadataKey} is returned.
   * Otherwise, the {@link TypeKeysResolver#getKeys} associated with the current Component will be invoked to obtain the keys
   *
   * @param context current {@link MetadataContext} that will be used by the {@link TypeKeysResolver}
   * @return Successful {@link MetadataResult} if the keys are obtained without errors Failure {@link MetadataResult} when no
   *         Dynamic keys are a available or the retrieval fails for any reason
   */
  public MetadataResult<MetadataKeysContainer> getMetadataKeys(MetadataContext context) {
    return keysDelegate.getMetadataKeys(context, reflectionCache);
  }

  public MetadataResult<MetadataKeysContainer> getMetadataKeys(MetadataContext context,
                                                               ParameterValueResolver metadataKeyResolver) {
    MetadataResult keyValueResult = getMetadataKeyObjectValue(metadataKeyResolver);
    if (!keyValueResult.isSuccess()) {
      return keyValueResult;
    }

    return keysDelegate.getMetadataKeys(context, keyValueResult.get(), reflectionCache);
  }

  public MetadataResult<MetadataKeysContainer> getMetadataKeys(MetadataContext context,
                                                               MetadataKey partialKey) {
    try {
      Object resolvedKey = keyIdObjectResolver.resolveWithPartialKey(partialKey);
      return keysDelegate.getMetadataKeys(context, resolvedKey, reflectionCache);
    } catch (MetadataResolvingException e) {
      return failure(newFailure(e).onKeys());
    }
  }

  /**
   * Resolves the {@link ComponentMetadataDescriptor} for the associated {@code context} using the specified {@code key}
   *
   * @param context current {@link MetadataContext} that will be used by the metadata resolvers.
   * @param key     {@link MetadataKey} of the type which's structure has to be resolved, used both for input and output types
   * @return Successful {@link MetadataResult} if the MetadataTypes are resolved without errors Failure {@link MetadataResult}
   *         when the Metadata retrieval of any element fails for any reason
   */
  public MetadataResult<ComponentMetadataDescriptor<T>> getMetadata(MetadataContext context, MetadataKey key) {
    try {
      Object resolvedKey = keyIdObjectResolver.resolve(key);
      return getMetadata(context, resolvedKey, MetadataAttributes.builder().withKey(key));
    } catch (MetadataResolvingException e) {
      return failure(newFailure(e).onComponent());
    }
  }

  /**
   * Resolves the {@link ComponentMetadataDescriptor} for the associated {@code context} using the component
   * {@link ParameterValueResolver} to resolve an object with the value of the {@link MetadataKey}.
   *
   * @param context current {@link MetadataContext} that will be used by the metadata resolvers.
   * @return Successful {@link MetadataResult} if the MetadataTypes are resolved without errors Failure {@link MetadataResult}
   *         when the Metadata retrieval of any element fails for any reason
   */
  public MetadataResult<ComponentMetadataDescriptor<T>> getMetadata(MetadataContext context,
                                                                    ParameterValueResolver metadataKeyResolver) {
    try {
      Object keyValue;
      MetadataResult keyValueResult = getMetadataKeyObjectValue(metadataKeyResolver);
      if (!keyValueResult.isSuccess()) {
        return keyValueResult;
      }

      keyValue = keyValueResult.get();
      if (keyValue == null && keyIdObjectResolver.isKeyRequired()) {
        return failure(newFailure().withFailureCode(INVALID_METADATA_KEY).withMessage("MetadataKey resolved to null")
            .onComponent());
      }

      MetadataAttributes.MetadataAttributesBuilder builder = MetadataAttributes.builder();
      if (!keyIdObjectResolver.isKeyLess()) {
        builder.withKey(keyIdObjectResolver.reconstructKeyFromType(keyValue, reflectionCache));
      }

      return getMetadata(context, keyValue, builder);
    } catch (MetadataResolvingException e) {
      return failure(newFailure(e).onComponent());
    }
  }

  @Override
  public MetadataResult<ScopeInputMetadataDescriptor> getScopeInputMetadata(MetadataContext context,
                                                                            MetadataKey key,
                                                                            Supplier<MessageMetadataType> scopeInputMessageType,
                                                                            PropagatedParameterTypeResolver propagatedParameterTypeResolver) {
    if (!isScope(component)) {
      return failure(MetadataFailure.Builder.newFailure()
          .withMessage("The given component is not a scope").onComponent());
    }
    MetadataResult<InputMetadataDescriptor> inputMetadata = getInputMetadata(context, key);
    if (!inputMetadata.isSuccess()) {
      return (MetadataResult) inputMetadata;
    }

    MetadataResult<MessageMetadataType> scopeChainInputType =
        inputDelegate.getScopeChainInputType(context, scopeInputMessageType, inputMetadata.get(),
                                             propagatedParameterTypeResolver);

    if (scopeChainInputType.isSuccess()) {
      return success(ScopeInputMetadataDescriptor.builder()
          .withParameters(inputMetadata.get().getAllParameters())
          .withChainInputMessageType(scopeChainInputType.get())
          .build());
    } else {
      return (MetadataResult) scopeChainInputType;
    }
  }

  @Override
  public MetadataResult<RouterInputMetadataDescriptor> getRouterInputMetadata(MetadataContext context,
                                                                              MetadataKey key,
                                                                              Supplier<MessageMetadataType> routerInputMessageType,
                                                                              PropagatedParameterTypeResolver propagatedParameterTypeResolver) {
    if (!isRouter(component)) {
      return failure(MetadataFailure.Builder.newFailure()
          .withMessage("The given component is not a router").onComponent());
    }
    MetadataResult<InputMetadataDescriptor> inputMetadata = getInputMetadata(context, key);
    if (!inputMetadata.isSuccess()) {
      return (MetadataResult) inputMetadata;
    }

    MetadataResult<Map<String, MessageMetadataType>> routesInputTypes =
        inputDelegate.getRoutesChainInputType(context, routerInputMessageType, inputMetadata.get(),
                                              propagatedParameterTypeResolver);

    if (routesInputTypes.isSuccess()) {
      return success(RouterInputMetadataDescriptor.builder()
          .withParameters(inputMetadata.get().getAllParameters())
          .withRoutesInputMessageTypes(routesInputTypes.get())
          .build());
    } else {
      return (MetadataResult) routesInputTypes;
    }
  }

  @Override
  public MetadataResult<InputMetadataDescriptor> getInputMetadata(MetadataContext context, MetadataKey key) {
    try {
      Object resolvedKey = keyIdObjectResolver.resolve(key);
      MetadataResult<InputMetadataDescriptor> input = inputDelegate.getInputMetadataDescriptors(context, resolvedKey);
      Optional<MetadataResult<InputMetadataDescriptor>> successCallbackInput = successCallbackInputDelegate
          .map(successCallbackInputDelegate -> successCallbackInputDelegate.getInputMetadataDescriptors(context, resolvedKey));
      Optional<MetadataResult<InputMetadataDescriptor>> errorCallbackInput = errorCallbackInputDelegate
          .map(errorCallbackInputDelegate -> errorCallbackInputDelegate.getInputMetadataDescriptors(context, resolvedKey));

      if (input.isSuccess() && (!successCallbackInput.isPresent() || successCallbackInput.get().isSuccess())
          && (!errorCallbackInput.isPresent() || errorCallbackInput.get().isSuccess())) {
        InputMetadataDescriptor.InputMetadataDescriptorBuilder inputMetadataDescriptorBuilder = InputMetadataDescriptor.builder();
        input.get().getAllParameters().forEach((name, parameterMetadataDescriptor) -> inputMetadataDescriptorBuilder
            .withParameter(name, parameterMetadataDescriptor));
        successCallbackInput.map(MetadataResult::get)
            .ifPresent(successCallbackInputDescriptor -> successCallbackInputDescriptor.getAllParameters()
                .forEach((name, parameterMetadataDescriptor) -> inputMetadataDescriptorBuilder
                    .withParameter(name, parameterMetadataDescriptor)));
        errorCallbackInput.map(MetadataResult::get)
            .ifPresent(errorCallbackInputDescriptor -> errorCallbackInputDescriptor.getAllParameters()
                .forEach((name, parameterMetadataDescriptor) -> inputMetadataDescriptorBuilder
                    .withParameter(name, parameterMetadataDescriptor)));
        return success(inputMetadataDescriptorBuilder.build());
      }

      List<MetadataFailure> failures = ImmutableList.<MetadataFailure>builder()
          .addAll(input.getFailures())
          .addAll(successCallbackInput.map(MetadataResult::getFailures).orElse(emptyList()))
          .addAll(errorCallbackInput.map(MetadataResult::getFailures).orElse(emptyList()))
          .build();

      return failure(failures);
    } catch (MetadataResolvingException e) {
      return failure(newFailure(e).onComponent());
    }
  }

  @Override
  public MetadataResult<OutputMetadataDescriptor> getOutputMetadata(MetadataContext context, MetadataKey key) {
    if (!(component instanceof HasOutputModel)) {
      return failure(MetadataFailure.Builder.newFailure()
          .withMessage("The given component has not output definition to be described").onComponent());
    }

    try {
      Object resolvedKey = keyIdObjectResolver.resolve(key);
      return outputDelegate.getOutputMetadataDescriptor(context, resolvedKey);
    } catch (MetadataResolvingException e) {
      return failure(newFailure(e).onComponent());
    }
  }

  /**
   * Resolves the {@link ComponentMetadataDescriptor} for the associated {@code context} using static and dynamic resolving of the
   * Component parameters, attributes and output.
   *
   * @param context  current {@link MetadataContext} that will be used by the {@link InputTypeResolver} and
   *                 {@link OutputTypeResolver}
   * @param keyValue {@link Object} resolved {@link MetadataKey} object
   * @return Successful {@link MetadataResult} if the MetadataTypes are resolved without errors Failure {@link MetadataResult}
   *         when the Metadata retrieval of any element fails for any reason
   */
  private MetadataResult<ComponentMetadataDescriptor<T>> getMetadata(MetadataContext context,
                                                                     Object keyValue,
                                                                     MetadataAttributes.MetadataAttributesBuilder attributesBuilder) {

    MetadataResult<OutputMetadataDescriptor> output = getOutputMetadataDescriptor(context, keyValue);
    MetadataResult<InputMetadataDescriptor> input = inputDelegate.getInputMetadataDescriptors(context, keyValue);
    Optional<MetadataResult<InputMetadataDescriptor>> successCallbackInput = successCallbackInputDelegate
        .map(successCallbackInputDelegate -> successCallbackInputDelegate.getInputMetadataDescriptors(context, keyValue));
    Optional<MetadataResult<InputMetadataDescriptor>> errorCallbackInput = errorCallbackInputDelegate
        .map(errorCallbackInputDelegate -> errorCallbackInputDelegate.getInputMetadataDescriptors(context, keyValue));
    if (output.isSuccess() && input.isSuccess() && (!successCallbackInput.isPresent() || successCallbackInput.get().isSuccess())
        && (!errorCallbackInput.isPresent() || errorCallbackInput.get().isSuccess())) {
      MetadataAttributes metadataAttributes = getMetadataAttributes(attributesBuilder, outputDelegate, input.get());
      T model = getTypedModel(input.get(), output.get(), successCallbackInput.map(MetadataResult::get),
                              errorCallbackInput.map(MetadataResult::get));
      return success(ComponentMetadataDescriptor.builder(model).withAttributes(metadataAttributes).build());
    }

    List<MetadataFailure> failures = ImmutableList.<MetadataFailure>builder()
        .addAll(output.getFailures())
        .addAll(input.getFailures())
        .addAll(successCallbackInput.map(MetadataResult::getFailures).orElse(emptyList()))
        .addAll(errorCallbackInput.map(MetadataResult::getFailures).orElse(emptyList()))
        .build();

    return failure(ComponentMetadataDescriptor.builder(component).build(), failures);
  }

  private MetadataResult<OutputMetadataDescriptor> getOutputMetadataDescriptor(MetadataContext context, Object keyValue) {
    return outputDelegate.getOutputMetadataDescriptor(context, keyValue);
  }

  /**
   * Returns a {@link ComponentModel} with its types resolved.
   *
   * @param inputMetadataDescriptor  {@link InputMetadataDescriptor} describes the input parameters of the component
   * @param outputMetadataDescriptor {@link OutputMetadataDescriptor} describes the component output
   * @return model with its types resolved by the metadata resolution process
   */
  private T getTypedModel(InputMetadataDescriptor inputMetadataDescriptor,
                          OutputMetadataDescriptor outputMetadataDescriptor,
                          Optional<InputMetadataDescriptor> successSourceCallbackInputMetadataDescriptor,
                          Optional<InputMetadataDescriptor> errorSourceCallbackInputMetadataDescriptor) {

    Reference<T> typedModel = new Reference<>();
    component.accept(new ComponentModelVisitor() {

      @Override
      public void visit(ConstructModel constructModel) {
        typedModel.set((T) new ImmutableConstructModel(constructModel.getName(),
                                                       constructModel.getDescription(),
                                                       resolveParameterGroupModelType(
                                                                                      constructModel.getParameterGroupModels(),
                                                                                      inputMetadataDescriptor.getAllParameters()),
                                                       constructModel.getNestedComponents(),
                                                       constructModel.allowsTopLevelDeclaration(),
                                                       constructModel.getDisplayModel().orElse(null),
                                                       constructModel.getErrorModels(),
                                                       constructModel.getStereotype(),
                                                       constructModel.getVisibility(),
                                                       constructModel.getModelProperties(),
                                                       constructModel.getDeprecationModel().orElse(null)));
      }

      @Override
      public void visit(OperationModel operationModel) {
        OutputModel typedOutputModel =
            resolveOutputModelType(operationModel.getOutput(), outputMetadataDescriptor.getPayloadMetadata());
        OutputModel typedAttributesModel =
            resolveOutputModelType(operationModel.getOutputAttributes(), outputMetadataDescriptor.getAttributesMetadata());
        typedModel.set((T) new ImmutableOperationModel(operationModel.getName(),
                                                       operationModel.getDescription(),
                                                       resolveParameterGroupModelType(
                                                                                      operationModel.getParameterGroupModels(),

                                                                                      inputMetadataDescriptor.getAllParameters()),
                                                       operationModel.getNestedComponents(),
                                                       typedOutputModel, typedAttributesModel, operationModel.isBlocking(),
                                                       operationModel.getExecutionType(), operationModel.requiresConnection(),
                                                       operationModel.isTransactional(),
                                                       operationModel.supportsStreaming(),
                                                       operationModel.getDisplayModel().orElse(null),
                                                       operationModel.getErrorModels(),
                                                       operationModel.getStereotype(),
                                                       operationModel.getVisibility(),
                                                       operationModel.getModelProperties(),
                                                       operationModel.getNotificationModels(),
                                                       operationModel.getDeprecationModel().orElse(null)));
      }

      @Override
      public void visit(SourceModel sourceModel) {
        OutputModel typedOutputModel =
            resolveOutputModelType(sourceModel.getOutput(), outputMetadataDescriptor.getPayloadMetadata());
        OutputModel typedAttributesModel =
            resolveOutputModelType(sourceModel.getOutputAttributes(), outputMetadataDescriptor.getAttributesMetadata());
        typedModel.set((T) new ImmutableSourceModel(sourceModel.getName(),
                                                    sourceModel.getDescription(),
                                                    sourceModel.hasResponse(),
                                                    true,
                                                    resolveParameterGroupModelType(sourceModel.getParameterGroupModels(),
                                                                                   inputMetadataDescriptor.getAllParameters()),
                                                    sourceModel.getNestedComponents(),
                                                    typedOutputModel, typedAttributesModel,
                                                    resolveSourceCallbackType(sourceModel.getSuccessCallback(),
                                                                              successSourceCallbackInputMetadataDescriptor
                                                                                  .map(InputMetadataDescriptor::getAllParameters)
                                                                                  .orElse(emptyMap())),
                                                    resolveSourceCallbackType(sourceModel.getErrorCallback(),
                                                                              errorSourceCallbackInputMetadataDescriptor
                                                                                  .map(InputMetadataDescriptor::getAllParameters)
                                                                                  .orElse(emptyMap())),
                                                    resolveSourceCallbackType(sourceModel.getTerminateCallback(),
                                                                              inputMetadataDescriptor.getAllParameters()),
                                                    sourceModel.requiresConnection(),
                                                    sourceModel.isTransactional(),
                                                    sourceModel.supportsStreaming(),
                                                    sourceModel.getDisplayModel().orElse(null),
                                                    sourceModel.getStereotype(),
                                                    sourceModel.getErrorModels(),
                                                    sourceModel.getVisibility(),
                                                    sourceModel.getModelProperties(),
                                                    sourceModel.getNotificationModels(),
                                                    sourceModel.getDeprecationModel().orElse(null)));
      }

      @Override
      public void visit(NestedComponentModel model) {
        // no-op
      }

      @Override
      public void visit(NestedChainModel model) {
        // no-op
      }

      @Override
      public void visit(NestedRouteModel model) {
        // no-op
      }
    });

    return typedModel.get();
  }

  private MetadataAttributes getMetadataAttributes(MetadataAttributes.MetadataAttributesBuilder attributesBuilder,
                                                   MetadataOutputDelegate outputDelegate,
                                                   InputMetadataDescriptor input) {

    outputDelegate.getCategoryName().ifPresent(attributesBuilder::withCategoryName);
    outputDelegate.getOutputResolver().ifPresent(r -> attributesBuilder.withOutputResolver(r.getResolverName()));
    outputDelegate.getOutputAttributesResolver()
        .ifPresent(r -> attributesBuilder.withOutputAttributesResolver(r.getResolverName()));
    input.getAllParameters()
        .forEach((key, value) -> attributesBuilder
            .withParameterResolver(key, inputDelegate.getParameterResolver(key)
                .getResolverName()));
    return attributesBuilder.build();
  }

  private MetadataResult<Object> getMetadataKeyObjectValue(ParameterValueResolver keyValueResolver) {
    try {
      Object keyValue = getContainerName().isPresent() ? keyValueResolver.getParameterValue(getContainerName().get()) : null;
      return success(keyValue);
    } catch (Exception e) {
      return failure(newFailure(e).onComponent());
    }
  }

  private List<ParameterModel> getMetadataKeyParts(ComponentModel componentModel) {
    return componentModel.getAllParameterModels().stream()
        .filter(p -> p.getModelProperty(MetadataKeyPartModelProperty.class).isPresent())
        .collect(toList());
  }

  private Optional<String> getContainerName() {
    return ofNullable(keyContainerName);
  }

  private List<ParameterGroupModel> resolveParameterGroupModelType(List<ParameterGroupModel> untypedParameterGroups,
                                                                   Map<String, ParameterMetadataDescriptor> inputTypeDescriptors) {
    List<ParameterGroupModel> parameterGroups = new LinkedList<>();
    untypedParameterGroups.forEach(parameterGroup -> {
      List<ParameterModel> parameters = new LinkedList<>();
      parameterGroup.getParameterModels().forEach(parameterModel -> {
        ParameterMetadataDescriptor parameterMetadataDescriptor = inputTypeDescriptors.get(parameterModel.getName());
        ParameterModel typedParameterModel =
            new ImmutableParameterModel(parameterModel.getName(),
                                        parameterModel.getDescription(),
                                        parameterMetadataDescriptor.getType(),
                                        parameterMetadataDescriptor.isDynamic(),
                                        parameterModel.isRequired(),
                                        parameterModel.isOverrideFromConfig(),
                                        parameterModel.isComponentId(),
                                        parameterModel.getExpressionSupport(),
                                        parameterModel.getDefaultValue(),
                                        parameterModel.getRole(),
                                        parameterModel.getDslConfiguration(),
                                        parameterModel.getDisplayModel().orElse(null),
                                        parameterModel.getLayoutModel().orElse(null),
                                        parameterModel.getValueProviderModel().orElse(null),
                                        parameterModel.getAllowedStereotypes(),
                                        parameterModel.getModelProperties(),
                                        parameterModel.getDeprecationModel().orElse(null));
        parameters.add(typedParameterModel);
      });

      parameterGroups
          .add(new ImmutableParameterGroupModel(parameterGroup.getName(),
                                                parameterGroup.getDescription(),
                                                parameters,
                                                parameterGroup.getExclusiveParametersModels(),
                                                parameterGroup.isShowInDsl(),
                                                parameterGroup.getDisplayModel().orElse(null),
                                                parameterGroup.getLayoutModel().orElse(null),
                                                parameterGroup.getModelProperties()));
    });
    return parameterGroups;
  }

  private Optional<SourceCallbackModel> resolveSourceCallbackType(Optional<SourceCallbackModel> sourceCallbackModel,
                                                                  Map<String, ParameterMetadataDescriptor> inputTypeDescriptors) {
    return sourceCallbackModel.map(cb -> new ImmutableSourceCallbackModel(cb.getName(), cb.getDescription(),
                                                                          resolveParameterGroupModelType(cb
                                                                              .getParameterGroupModels(),
                                                                                                         inputTypeDescriptors),
                                                                          cb.getDisplayModel().orElse(null),
                                                                          cb.getModelProperties()));
  }

  private OutputModel resolveOutputModelType(OutputModel untypedModel, TypeMetadataDescriptor typeMetadataDescriptor) {
    return new ImmutableOutputModel(untypedModel.getDescription(), typeMetadataDescriptor.getType(),
                                    typeMetadataDescriptor.isDynamic(), untypedModel.getModelProperties());
  }
}
