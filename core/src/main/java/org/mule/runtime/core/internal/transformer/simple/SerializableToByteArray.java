/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.transformer.simple;

import static org.mule.runtime.api.serialization.ObjectSerializer.DEFAULT_OBJECT_SERIALIZER_NAME;

import org.mule.runtime.api.message.Message;
import org.mule.runtime.api.metadata.DataType;
import org.mule.runtime.api.serialization.ObjectSerializer;
import org.mule.runtime.core.api.transformer.AbstractTransformer;
import org.mule.runtime.core.api.transformer.DiscoverableTransformer;
import org.mule.runtime.core.api.transformer.TransformerException;

import java.io.Serializable;
import java.nio.charset.Charset;

import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * <code>SerializableToByteArray</code> converts a serializable object or a String to a byte array. If <code>Message</code> is
 * configured as a source type on this transformer by calling {@link #setAcceptMuleMessage(boolean)} then the {@link Message} will
 * be serialised. This is useful for transports such as TCP where the message headers would normally be lost.
 */
public class SerializableToByteArray extends AbstractTransformer implements DiscoverableTransformer {

  private ObjectSerializer objectSerializer;

  /**
   * Give core transformers a slightly higher priority
   */
  private int priorityWeighting = DiscoverableTransformer.DEFAULT_PRIORITY_WEIGHTING + 1;

  public SerializableToByteArray() {
    this.registerSourceType(DataType.fromType(Serializable.class));
    this.setReturnDataType(DataType.BYTE_ARRAY);
  }

  @Override
  public Object doTransform(Object src, Charset outputEncoding) throws TransformerException {
    /*
     * If the Message source type has been registered then we can assume that the whole message is to be serialised, not just the
     * payload. This can be useful for protocols such as tcp where the protocol does not support headers and the whole message
     * needs to be serialized.
     */

    try {
      return objectSerializer.getExternalProtocol().serialize(src);
    } catch (Exception e) {
      throw new TransformerException(this, e);
    }
  }

  @Override
  public int getPriorityWeighting() {
    return priorityWeighting;
  }

  @Override
  public void setPriorityWeighting(int priorityWeighting) {
    this.priorityWeighting = priorityWeighting;
  }

  @Inject
  @Named(DEFAULT_OBJECT_SERIALIZER_NAME)
  public void setObjectSerializer(ObjectSerializer objectSerializer) {
    this.objectSerializer = objectSerializer;
  }

}
