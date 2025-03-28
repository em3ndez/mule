/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.transformer.simple;

import static java.nio.charset.Charset.defaultCharset;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

import org.mule.runtime.api.streaming.bytes.CursorStreamProvider;
import org.mule.runtime.core.api.streaming.bytes.InMemoryCursorStreamConfig;
import org.mule.runtime.core.api.streaming.bytes.InMemoryCursorStreamProvider;
import org.mule.runtime.core.internal.streaming.bytes.SimpleByteBufferManager;
import org.mule.tck.junit4.AbstractMuleTestCase;
import org.mule.tck.size.SmallTest;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import org.junit.Before;
import org.junit.Test;

@SmallTest
public class InputStreamToByteArrayTestCase extends AbstractMuleTestCase {

  private static final String DONKEY = "donkey";

  private InputStreamToByteArray transformer;

  @Before
  public void setUp() {
    transformer = new InputStreamToByteArray();
    transformer.setArtifactEncoding(() -> defaultCharset());
  }

  @Test
  public void transformInputStream() throws Exception {
    InputStream inputStream = new ByteArrayInputStream(DONKEY.getBytes());
    assertThat(transformer.transform(inputStream), equalTo(DONKEY.getBytes()));
  }

  @Test
  public void transformCursorStreamProvider() throws Exception {
    InputStream inputStream = new ByteArrayInputStream(DONKEY.getBytes());
    CursorStreamProvider provider =
        new InMemoryCursorStreamProvider(inputStream, InMemoryCursorStreamConfig.getDefault(), new SimpleByteBufferManager());
    assertThat(transformer.transform(provider), equalTo(DONKEY.getBytes()));

  }
}

