/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.loader.java.property;

import org.mule.runtime.api.meta.model.ModelProperty;

/**
 * {@link ModelProperty} for indicating that the owning operation is a composed operation.
 *
 * @since 4.5
 */
public class ComposedOperationModelProperty implements ModelProperty {

  @Override
  public String getName() {
    return "composedOperation";
  }

  @Override
  public boolean isPublic() {
    return false;
  }
}
