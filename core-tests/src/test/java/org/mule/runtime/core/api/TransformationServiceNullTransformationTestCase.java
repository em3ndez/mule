/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.api;

import static org.mule.runtime.api.message.Message.of;

import static java.nio.charset.Charset.defaultCharset;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.message.Message;
import org.mule.runtime.api.metadata.DataType;
import org.mule.runtime.core.api.transformer.Transformer;
import org.mule.runtime.core.internal.transformer.ExtendedTransformationService;
import org.mule.tck.junit4.AbstractMuleTestCase;
import org.mule.tck.size.SmallTest;

import org.junit.Test;

@SmallTest
public class TransformationServiceNullTransformationTestCase extends AbstractMuleTestCase {

  @Test
  public void transformerIsNeverCalledWithANullValue() throws MuleException {
    ExtendedTransformationService transformationService = new ExtendedTransformationService();
    transformationService.setArtifactEncoding(() -> defaultCharset());

    Transformer transformer1 = mock(Transformer.class);
    when(transformer1.transform(any(Object.class))).thenReturn(null);
    when(transformer1.isSourceDataTypeSupported(any(DataType.class))).thenReturn(true);
    when(transformer1.getReturnDataType()).thenReturn(DataType.OBJECT);

    Transformer transformer2 = mock(Transformer.class);
    when(transformer2.transform(any(Object.class))).thenReturn("foo");
    when(transformer2.isSourceDataTypeSupported(any(DataType.class))).thenReturn(true);
    when(transformer2.getReturnDataType()).thenReturn(DataType.OBJECT);

    Message message = transformationService.applyTransformers(of(""), null, transformer1, transformer2);

    assertEquals("foo", message.getPayload().getValue());
    verify(transformer1, never()).transform(null);
    verify(transformer1, never()).isAcceptNull();
    verify(transformer2, never()).transform(null);
    verify(transformer2, never()).isAcceptNull();
  }
}
