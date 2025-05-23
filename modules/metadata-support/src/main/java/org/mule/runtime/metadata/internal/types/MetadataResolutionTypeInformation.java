/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.metadata.internal.types;

import org.mule.api.annotation.NoImplement;
import org.mule.runtime.metadata.api.cache.MetadataCacheId;

import java.util.Optional;

/**
 * Inteface defined to handle a component type to create a {@link MetadataCacheId} that describes it
 *
 * @since 4.2.0
 */
@NoImplement
public interface MetadataResolutionTypeInformation {

  /**
   *
   * @return whether the type is dynamic or not
   */
  boolean isDynamicType();

  /**
   *
   * @return an {@link Optional} with the {@link String} name associated to the resolution of the type if it is dynamic. or
   *         {@link Optional#empty()} if the resolution of the type is static.
   */
  Optional<String> getResolverName();

  /**
   *
   * @return an {@link Optional} with the {@link String} category associated to the resolution of the type if it is dynamic. or
   *         {@link Optional#empty()} if the resolution of the type is static.
   */
  Optional<String> getResolverCategory();

  /**
   *
   * @return the {@link MetadataCacheId} that describes the type. This only takes into account if the type is an input, output or
   *         attribute type.
   */
  MetadataCacheId getComponentTypeMetadataCacheId();

  /**
   * @return true if the type resolution should include the metadata keys configured when computing the {@link MetadataCacheId}
   */
  default boolean shouldIncludeConfiguredMetadataKeys() {
    return true;
  }

}
