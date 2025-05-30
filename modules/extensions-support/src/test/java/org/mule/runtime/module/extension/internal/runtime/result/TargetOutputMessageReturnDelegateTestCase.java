/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.runtime.result;

import static org.mule.runtime.api.metadata.MediaType.APPLICATION_JSON;
import static org.mule.tck.util.MuleContextUtils.eventBuilder;
import static org.mule.test.module.extension.internal.util.ExtensionsTestUtils.getDefaultCursorStreamProviderFactory;

import static java.nio.charset.Charset.defaultCharset;
import static java.util.Optional.empty;
import static java.util.Optional.of;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.mule.runtime.api.component.Component;
import org.mule.runtime.api.config.ArtifactEncoding;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.message.Message;
import org.mule.runtime.api.meta.model.ComponentModel;
import org.mule.runtime.api.metadata.MediaType;
import org.mule.runtime.core.api.context.notification.ServerNotificationManager;
import org.mule.runtime.core.api.el.ExpressionManager;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.api.security.SecurityManager;
import org.mule.runtime.core.api.streaming.StreamingManager;
import org.mule.runtime.extension.api.runtime.operation.Result;
import org.mule.runtime.module.extension.api.runtime.privileged.ExecutionContextAdapter;
import org.mule.runtime.module.extension.internal.loader.java.property.MediaTypeModelProperty;
import org.mule.tck.junit4.AbstractMuleContextTestCase;
import org.mule.tck.junit4.AbstractMuleTestCase;
import org.mule.tck.size.SmallTest;

import java.nio.charset.Charset;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@SmallTest
public class TargetOutputMessageReturnDelegateTestCase extends AbstractMuleContextTestCase {

  private static final String TARGET = "myFlowVar";

  @Rule
  public MockitoRule rule = MockitoJUnit.rule();

  private ExpressionManager expressionManager;

  @Mock(lenient = true)
  protected ExecutionContextAdapter operationContext;

  @Mock(answer = RETURNS_DEEP_STUBS)
  protected ComponentModel componentModel;

  @Mock
  protected Component component;

  protected CoreEvent event;

  @Mock
  private StreamingManager streamingManager;

  @Mock
  private ArtifactEncoding artifactEncoding;

  @Mock
  private ServerNotificationManager notificationManager;

  @Mock
  private SecurityManager securityManager;

  @Mock
  protected Object attributes;

  protected ReturnDelegate delegate;
  private final Object payload = "hello world!";

  @Before
  public void before() throws MuleException {
    expressionManager = muleContext.getExpressionManager();
    event = eventBuilder().message(Message.builder().value("").attributesValue(attributes).build()).build();
    when(operationContext.getEvent()).thenReturn(event);
    when(operationContext.getComponent()).thenReturn(component);
    when(operationContext.getCursorProviderFactory()).thenReturn(getDefaultCursorStreamProviderFactory());
    when(operationContext.getArtifactEncoding()).thenReturn(artifactEncoding);
    when(operationContext.getNotificationManager()).thenReturn(notificationManager);
    when(operationContext.getSecurityManager()).thenReturn(securityManager);
    when(componentModel.getModelProperty(MediaTypeModelProperty.class)).thenReturn(empty());
  }

  private TargetReturnDelegate createDelegate(String expression) {
    return new TargetReturnDelegate(TARGET, expression, componentModel, expressionManager,
                                    () -> defaultCharset(), streamingManager);
  }

  @Test
  public void operationTargetMessage() {
    delegate = createDelegate("#[message]");

    CoreEvent result = delegate.asReturnValue(payload, operationContext);
    assertMessage(result.getMessage());
    assertThat(result.getVariables().get(TARGET).getValue(), is(instanceOf(Message.class)));
    Message message = (Message) result.getVariables().get(TARGET).getValue();
    assertThat(message.getPayload().getValue(), is(payload));
  }

  @Test
  public void operationTargetMessageWithDefaultMimeType() {
    when(componentModel.getModelProperty(MediaTypeModelProperty.class)).thenReturn(of(
                                                                                      new MediaTypeModelProperty(APPLICATION_JSON
                                                                                          .toRfcString(), true)));
    delegate = createDelegate("#[message]");

    CoreEvent result = delegate.asReturnValue(payload, operationContext);
    assertMessage(result.getMessage());
    assertThat(result.getVariables().get(TARGET).getValue(), is(instanceOf(Message.class)));
    Message message = (Message) result.getVariables().get(TARGET).getValue();
    assertThat(message.getPayload().getValue(), is(payload));
    assertThat(message.getPayload().getDataType().getMediaType().toRfcString(), containsString(APPLICATION_JSON.toRfcString()));
  }

  @Test
  public void operationTargetPayload() {
    delegate = createDelegate("#[payload]");
    CoreEvent result = delegate.asReturnValue(payload, operationContext);
    assertMessage(result.getMessage());
    assertThat(result.getVariables().get(TARGET).getValue(), is(payload));
  }

  @Test
  public void operationTargetPayloadWithResult() {
    delegate = createDelegate("#[payload]");
    MediaType mediaType = APPLICATION_JSON.withCharset(Charset.defaultCharset());
    CoreEvent result =
        delegate.asReturnValue(Result.builder().output(payload).mediaType(mediaType).build(), operationContext);
    assertMessage(result.getMessage());
    assertThat(result.getVariables().get(TARGET).getValue(), is(payload));
    assertThat(result.getVariables().get(TARGET).getDataType().getMediaType(), is(mediaType));
  }

  private void assertMessage(Message message) {
    assertThat(message.getPayload().getValue(), is(""));
    assertThat(message.getAttributes().getValue(), is(attributes));
    assertThat(message.getPayload().getDataType().getType().equals(String.class), is(true));
  }
}
