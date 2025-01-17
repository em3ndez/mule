/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.config.internal.dsl.spring;

import static org.mule.runtime.api.functional.Either.left;
import static org.mule.runtime.api.functional.Either.right;
import static org.mule.runtime.api.meta.model.parameter.ParameterRole.BEHAVIOUR;
import static org.mule.runtime.api.meta.model.parameter.ParameterRole.CONTENT;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.mule.runtime.api.meta.model.parameter.ParameterModel;
import org.mule.runtime.ast.api.ComponentAst;
import org.mule.runtime.ast.api.ComponentParameterAst;
import org.mule.runtime.config.internal.dsl.model.SpringComponentModel;
import org.mule.runtime.dsl.api.component.ComponentBuildingDefinition;
import org.mule.tck.junit4.AbstractMuleTestCase;

import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import io.qameta.allure.Issue;

public class SimpleTypeBeanParamDefinitionCreatorTestCase extends AbstractMuleTestCase {

  private ParameterModel paramModel;

  private SimpleTypeBeanParamDefinitionCreator simpleTypeParamDefinitionCreator;
  private CreateParamBeanDefinitionRequest createParamDefinitionRequest;
  private Map<ComponentAst, SpringComponentModel> springComponentModels;

  @Before
  public void setUp() {
    paramModel = mock(ParameterModel.class);
    when(paramModel.getRole()).thenReturn(CONTENT);

    simpleTypeParamDefinitionCreator = new SimpleTypeBeanParamDefinitionCreator(true, true);
    createParamDefinitionRequest = mock(CreateParamBeanDefinitionRequest.class);
    SpringComponentModel springComponentModel = new SpringComponentModel();
    when(createParamDefinitionRequest.getSpringComponentModel()).thenReturn(springComponentModel);
    when(createParamDefinitionRequest.getComponentBuildingDefinition()).thenReturn(mock(ComponentBuildingDefinition.class));
  }

  @Test
  @Issue("W-17475148")
  public void complexObjectFromExpression() {
    SpringComponentModel springComponentModel = createParamDefinitionRequest.getSpringComponentModel();
    springComponentModel.setType(Map.class);

    final ComponentParameterAst param = mock(ComponentParameterAst.class);
    when(param.getValue()).thenReturn(left("'some DW expression'"));

    when(createParamDefinitionRequest.getParam()).thenReturn(param);

    boolean result = simpleTypeParamDefinitionCreator.handleRequest(springComponentModels, createParamDefinitionRequest);
    assertThat("request not handled when it must", result, is(true));
  }

  @Test
  @Issue("W-17475148")
  public void complexObjectInlineNotHandled() {
    SpringComponentModel springComponentModel = createParamDefinitionRequest.getSpringComponentModel();
    springComponentModel.setType(Map.class);

    final ComponentParameterAst param = mock(ComponentParameterAst.class);
    when(param.getValue()).thenReturn(right(mock(ComponentAst.class)));

    final ParameterModel paramModel = mock(ParameterModel.class);
    when(paramModel.getRole()).thenReturn(BEHAVIOUR);
    when(param.getModel()).thenReturn(paramModel);

    when(createParamDefinitionRequest.getParam()).thenReturn(param);

    boolean result = simpleTypeParamDefinitionCreator.handleRequest(springComponentModels, createParamDefinitionRequest);
    assertThat("request handled when it must not", result, is(false));
  }

  @Test
  @Issue("W-17475148")
  public void staticTextInContentParamHandled() {
    SpringComponentModel springComponentModel = createParamDefinitionRequest.getSpringComponentModel();
    springComponentModel.setType(Map.class);

    final ComponentParameterAst param = mock(ComponentParameterAst.class);
    when(param.getValue()).thenReturn(right("some static text"));

    final ParameterModel paramModel = mock(ParameterModel.class);
    when(paramModel.getRole()).thenReturn(CONTENT);
    when(param.getModel()).thenReturn(paramModel);

    when(createParamDefinitionRequest.getParam()).thenReturn(param);

    boolean result = simpleTypeParamDefinitionCreator.handleRequest(springComponentModels, createParamDefinitionRequest);
    assertThat("request not handled when it must", result, is(true));
  }

}
