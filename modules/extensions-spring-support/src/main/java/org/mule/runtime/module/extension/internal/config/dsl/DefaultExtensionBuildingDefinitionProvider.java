/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.config.dsl;

import static org.mule.runtime.api.util.Preconditions.checkState;
import static org.mule.runtime.core.api.util.ClassUtils.withContextClassLoader;
import static org.mule.runtime.extension.api.util.ExtensionMetadataTypeUtils.getSubstitutionGroup;
import static org.mule.runtime.module.extension.internal.util.MuleExtensionUtils.getClassLoader;

import static java.util.Collections.emptySet;

import org.mule.metadata.api.ClassTypeLoader;
import org.mule.metadata.api.model.ArrayType;
import org.mule.metadata.api.model.MetadataType;
import org.mule.metadata.api.model.ObjectType;
import org.mule.metadata.api.model.UnionType;
import org.mule.metadata.api.visitor.MetadataTypeVisitor;
import org.mule.runtime.api.dsl.DslResolvingContext;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.meta.model.ExtensionModel;
import org.mule.runtime.api.meta.model.XmlDslModel;
import org.mule.runtime.api.meta.model.config.ConfigurationModel;
import org.mule.runtime.api.meta.model.connection.ConnectionProviderModel;
import org.mule.runtime.api.meta.model.construct.ConstructModel;
import org.mule.runtime.api.meta.model.operation.OperationModel;
import org.mule.runtime.api.meta.model.parameter.ParameterGroupModel;
import org.mule.runtime.api.meta.model.parameter.ParameterModel;
import org.mule.runtime.api.meta.model.source.SourceModel;
import org.mule.runtime.api.meta.model.util.IdempotentExtensionWalker;
import org.mule.runtime.core.api.config.ConfigurationException;
import org.mule.runtime.core.api.extension.ExtensionManager;
import org.mule.runtime.core.internal.extension.CustomBuildingDefinitionProviderModelProperty;
import org.mule.runtime.dsl.api.component.ComponentBuildingDefinition;
import org.mule.runtime.dsl.api.component.ComponentBuildingDefinition.Builder;
import org.mule.runtime.dsl.api.component.ComponentBuildingDefinitionProvider;
import org.mule.runtime.extension.api.dsl.syntax.DslElementSyntax;
import org.mule.runtime.extension.api.dsl.syntax.resolver.DslSyntaxResolver;
import org.mule.runtime.extension.api.property.XmlExtensionModelProperty;
import org.mule.runtime.module.extension.internal.config.ExtensionBuildingDefinitionProvider;
import org.mule.runtime.module.extension.internal.config.dsl.config.ConfigurationDefinitionParser;
import org.mule.runtime.module.extension.internal.config.dsl.connection.ConnectionProviderDefinitionParser;
import org.mule.runtime.module.extension.internal.config.dsl.construct.ConstructDefinitionParser;
import org.mule.runtime.module.extension.internal.config.dsl.operation.OperationDefinitionParser;
import org.mule.runtime.module.extension.internal.config.dsl.parameter.ObjectTypeParameterParser;
import org.mule.runtime.module.extension.internal.config.dsl.source.SourceDefinitionParser;
import org.mule.runtime.module.extension.internal.loader.java.property.TypeLoaderModelProperty;
import org.mule.runtime.module.extension.internal.util.IntrospectionUtils;
import org.mule.runtime.module.extension.internal.util.ReflectionCache;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import com.google.common.collect.ImmutableList;

/**
 * A generic {@link ComponentBuildingDefinitionProvider} which provides definitions capable of handling all extensions registered
 * on the {@link ExtensionManager}.
 *
 * @since 4.0
 */
public class DefaultExtensionBuildingDefinitionProvider implements ExtensionBuildingDefinitionProvider {

  private final List<ComponentBuildingDefinition> definitions = new LinkedList<>();

  private Set<ExtensionModel> extensions = emptySet();
  private DslResolvingContext dslResolvingContext;
  private Function<ExtensionModel, Optional<DslSyntaxResolver>> dslSyntaxResolverLookup;

  /**
   * Gets a hold on a {@link ExtensionManager} instance and generates the definitions.
   *
   * @throws java.lang.IllegalStateException if no extension manager could be found
   */
  @Override
  public void init() {
    checkState(extensions != null, "extensions cannot be null");
    extensions.stream()
        .filter(extensionModel -> !extensionModel.getModelProperty(CustomBuildingDefinitionProviderModelProperty.class)
            .isPresent()
            && !extensionModel.getModelProperty(XmlExtensionModelProperty.class).isPresent())
        .forEach(this::registerExtensionParsers);
  }

  @Override
  public List<ComponentBuildingDefinition> getComponentBuildingDefinitions() {
    return definitions;
  }

  private void registerExtensionParsers(ExtensionModel extensionModel) {
    XmlDslModel xmlDslModel = extensionModel.getXmlDslModel();

    final ExtensionParsingContext parsingContext = createParsingContext(extensionModel);
    final Builder definitionBuilder = new Builder().withNamespace(xmlDslModel.getPrefix());
    final DslSyntaxResolver dslSyntaxResolver = dslSyntaxResolverLookup.apply(extensionModel)
        .orElseGet(() -> DslSyntaxResolver.getDefault(extensionModel, dslResolvingContext));
    final Optional<ClassTypeLoader> typeLoader =
        extensionModel.getModelProperty(TypeLoaderModelProperty.class).map(TypeLoaderModelProperty::getTypeLoader);

    final ClassLoader extensionClassLoader = getClassLoader(extensionModel);
    withContextClassLoader(extensionClassLoader, () -> {
      ReflectionCache reflectionCache = new ReflectionCache();
      new IdempotentExtensionWalker() {

        @Override
        public void onConfiguration(ConfigurationModel model) {
          parseWith(new ConfigurationDefinitionParser(definitionBuilder, extensionModel, model, dslSyntaxResolver,
                                                      parsingContext, typeLoader));
        }

        @Override
        protected void onConstruct(ConstructModel model) {
          parseWith(new ConstructDefinitionParser(definitionBuilder, extensionModel,
                                                  model, dslSyntaxResolver, parsingContext, typeLoader));
        }

        @Override
        public void onOperation(OperationModel model) {
          parseWith(new OperationDefinitionParser(definitionBuilder, extensionModel,
                                                  model, dslSyntaxResolver, parsingContext, typeLoader));
        }

        @Override
        public void onConnectionProvider(ConnectionProviderModel model) {
          parseWith(new ConnectionProviderDefinitionParser(definitionBuilder, model, extensionModel, dslSyntaxResolver,
                                                           parsingContext, typeLoader));
        }

        @Override
        public void onSource(SourceModel model) {
          parseWith(new SourceDefinitionParser(definitionBuilder, extensionModel, model, dslSyntaxResolver,
                                               parsingContext, typeLoader));
        }

        @Override
        protected void onParameter(ParameterGroupModel groupModel, ParameterModel model) {
          registerTopLevelParameter(model.getType(), definitionBuilder, extensionClassLoader, dslSyntaxResolver,
                                    parsingContext, reflectionCache, typeLoader);
        }

      }.walk(extensionModel);

      registerExportedTypesTopLevelParsers(extensionModel, definitionBuilder, extensionClassLoader, dslSyntaxResolver,
                                           parsingContext, reflectionCache, typeLoader);

      registerSubTypes(definitionBuilder, extensionClassLoader, dslSyntaxResolver, parsingContext, reflectionCache,
                       typeLoader);
    });
  }

  private void registerSubTypes(MetadataType type, Builder definitionBuilder,
                                ClassLoader extensionClassLoader, DslSyntaxResolver dslSyntaxResolver,
                                ExtensionParsingContext parsingContext, ReflectionCache reflectionCache,
                                Optional<ClassTypeLoader> typeLoader) {
    type.accept(new MetadataTypeVisitor() {

      @Override
      public void visitUnion(UnionType unionType) {
        unionType.getTypes().forEach(type -> type.accept(this));
      }

      @Override
      public void visitArrayType(ArrayType arrayType) {
        arrayType.getType().accept(this);
      }

      @Override
      public void visitObject(ObjectType objectType) {
        if (objectType.isOpen()) {
          objectType.getOpenRestriction().get().accept(this);
        } else {
          parsingContext.getSubTypes(objectType)
              .forEach(subtype -> registerTopLevelParameter(subtype, definitionBuilder, extensionClassLoader, dslSyntaxResolver,
                                                            parsingContext, reflectionCache, typeLoader));
        }
      }
    });
  }

  private void parseWith(ExtensionDefinitionParser parser) {
    try {
      definitions.addAll(parser.parse());
    } catch (ConfigurationException e) {
      throw new MuleRuntimeException(e);
    }
  }

  private void registerTopLevelParameter(final MetadataType parameterType, Builder definitionBuilder,
                                         ClassLoader extensionClassLoader, DslSyntaxResolver dslSyntaxResolver,
                                         ExtensionParsingContext parsingContext, ReflectionCache reflectionCache,
                                         Optional<ClassTypeLoader> typeLoader) {
    Optional<DslElementSyntax> dslElement = dslSyntaxResolver.resolve(parameterType);
    if (!dslElement.isPresent() ||
        parsingContext.isRegistered(dslElement.get().getElementName(), dslElement.get().getPrefix())
        || !IntrospectionUtils.isInstantiable(parameterType, reflectionCache)) {
      return;
    }

    parameterType.accept(new MetadataTypeVisitor() {

      @Override
      public void visitObject(ObjectType objectType) {
        DslElementSyntax pojoDsl = dslElement.get();
        if (pojoDsl.supportsTopLevelDeclaration() || (pojoDsl.supportsChildDeclaration() && pojoDsl.isWrapped())
            || getSubstitutionGroup(objectType).isPresent() ||
            parsingContext.getAllSubTypes().contains(objectType)) {

          parseWith(new ObjectTypeParameterParser(definitionBuilder, objectType, extensionClassLoader, dslSyntaxResolver,
                                                  parsingContext, typeLoader));
        }

        registerSubTypes(objectType, definitionBuilder, extensionClassLoader, dslSyntaxResolver, parsingContext, reflectionCache,
                         typeLoader);
      }

      @Override
      public void visitArrayType(ArrayType arrayType) {
        registerTopLevelParameter(arrayType.getType(), definitionBuilder, extensionClassLoader, dslSyntaxResolver,
                                  parsingContext, reflectionCache, typeLoader);
      }

      @Override
      public void visitUnion(UnionType unionType) {
        unionType.getTypes().forEach(type -> type.accept(this));
      }

    });
  }

  private void registerExportedTypesTopLevelParsers(ExtensionModel extensionModel,
                                                    Builder definitionBuilder,
                                                    ClassLoader extensionClassLoader, DslSyntaxResolver dslSyntaxResolver,
                                                    ExtensionParsingContext parsingContext, ReflectionCache reflectionCache,
                                                    Optional<ClassTypeLoader> typeLoader) {
    registerTopLevelParameters(extensionModel.getTypes().stream(),
                               definitionBuilder,
                               extensionClassLoader,
                               dslSyntaxResolver,
                               parsingContext,
                               reflectionCache,
                               typeLoader);
  }

  private void registerSubTypes(Builder definitionBuilder,
                                ClassLoader extensionClassLoader, DslSyntaxResolver dslSyntaxResolver,
                                ExtensionParsingContext parsingContext, ReflectionCache reflectionCache,
                                Optional<ClassTypeLoader> typeLoader) {


    ImmutableList<MetadataType> mappedTypes = new ImmutableList.Builder<MetadataType>()
        .addAll(parsingContext.getAllSubTypes())
        .addAll(parsingContext.getAllBaseTypes())
        .build();

    registerTopLevelParameters(mappedTypes.stream(), definitionBuilder, extensionClassLoader,
                               dslSyntaxResolver,
                               parsingContext, reflectionCache, typeLoader);
  }

  private void registerTopLevelParameters(Stream<? extends MetadataType> parameters, Builder definitionBuilder,
                                          ClassLoader extensionClassLoader, DslSyntaxResolver dslSyntaxResolver,
                                          ExtensionParsingContext parsingContext, ReflectionCache reflectionCache,
                                          Optional<ClassTypeLoader> typeLoader) {

    parameters.filter(p -> IntrospectionUtils.isInstantiable(p, reflectionCache))
        .forEach(subType -> registerTopLevelParameter(subType,
                                                      definitionBuilder,
                                                      extensionClassLoader,
                                                      dslSyntaxResolver,
                                                      parsingContext,
                                                      reflectionCache,
                                                      typeLoader));

  }

  private ExtensionParsingContext createParsingContext(ExtensionModel extensionModel) {
    return new ExtensionParsingContext(extensionModel);
  }

  @Override
  public void setExtensionModels(Set<ExtensionModel> extensionModels) {
    this.extensions = extensionModels;
  }

  @Override
  public void setDslResolvingContext(DslResolvingContext dslResolvingContext) {
    this.dslResolvingContext = dslResolvingContext;
  }

  @Override
  public void setDslSyntaxResolverLookup(Function<ExtensionModel, Optional<DslSyntaxResolver>> dslSyntaxResolverLookup) {
    this.dslSyntaxResolverLookup = dslSyntaxResolverLookup;
  }
}
