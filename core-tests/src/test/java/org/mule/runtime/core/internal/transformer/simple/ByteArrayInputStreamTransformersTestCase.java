/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.transformer.simple;

import org.mule.runtime.core.api.transformer.Transformer;
import org.mule.tck.core.transformer.AbstractTransformerTestCase;

import java.io.ByteArrayInputStream;

public class ByteArrayInputStreamTransformersTestCase extends AbstractTransformerTestCase {

  @Override
  public Transformer getTransformer() throws Exception {
    return configureTransformer(new ObjectToInputStream());
  }

  @Override
  public Transformer getRoundTripTransformer() throws Exception {
    return configureTransformer(new ObjectToByteArray());
  }

  @Override
  public Object getTestData() {
    return TEST_MESSAGE.getBytes();
  }

  @Override
  public Object getResultData() {
    return new ByteArrayInputStream(TEST_MESSAGE.getBytes());
  }

}
