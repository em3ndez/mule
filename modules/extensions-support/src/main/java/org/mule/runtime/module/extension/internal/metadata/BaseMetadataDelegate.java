/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.metadata;

import static org.mule.metadata.api.utils.MetadataTypeUtils.isVoid;
import static org.mule.runtime.module.extension.internal.util.MuleExtensionUtils.getMetadataResolverFactory;

import org.mule.metadata.api.model.MetadataType;
import org.mule.runtime.api.meta.model.EnrichableModel;
import org.mule.runtime.api.metadata.resolving.NamedTypeResolver;
import org.mule.runtime.extension.api.metadata.MetadataResolverFactory;
import org.mule.runtime.extension.api.metadata.NullMetadataResolver;

import java.util.Optional;

/**
 * Base implementation for the Metadata service delegate implementations that are used by the {@link DefaultMetadataMediator}
 *
 * @since 4.0
 */
abstract class BaseMetadataDelegate {

  final static String NULL_TYPE_ERROR = "NullType is not a valid type for this element";

  protected final EnrichableModel model;
  final MetadataResolverFactory resolverFactory;

  BaseMetadataDelegate(EnrichableModel model) {
    this.model = model;
    this.resolverFactory = getMetadataResolverFactory(model);
  }

  boolean isMetadataResolvedCorrectly(MetadataType dynamicType, boolean allowsNullType) {
    return dynamicType != null && (!isVoid(dynamicType) || allowsNullType);
  }

  Optional<NamedTypeResolver> getOptionalResolver(NamedTypeResolver resolver) {
    return resolver instanceof NullMetadataResolver ? Optional.empty() : Optional.of(resolver);
  }
}
