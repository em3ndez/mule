/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.test.nonimplicit.config.extension.extension.api;

import static org.mule.sdk.api.meta.JavaVersion.JAVA_17;
import static org.mule.sdk.api.meta.JavaVersion.JAVA_21;

import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.lifecycle.Initialisable;
import org.mule.runtime.api.lifecycle.InitialisationException;
import org.mule.runtime.api.lifecycle.Startable;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.context.MuleContextAware;
import org.mule.runtime.extension.api.annotation.Extension;
import org.mule.runtime.extension.api.annotation.NoImplicit;
import org.mule.runtime.extension.api.annotation.Operations;
import org.mule.runtime.extension.api.annotation.connectivity.ConnectionProviders;
import org.mule.runtime.extension.api.annotation.dsl.xml.Xml;
import org.mule.runtime.extension.api.annotation.param.Optional;
import org.mule.runtime.extension.api.annotation.param.Parameter;
import org.mule.runtime.extension.api.annotation.param.ParameterGroup;
import org.mule.sdk.api.annotation.JavaVersionSupport;
import org.mule.test.nonimplicit.config.extension.extension.internal.NonImplicitConnectionProvider;
import org.mule.test.nonimplicit.config.extension.extension.internal.NonImplicitOperations;

@Extension(name = "Non Implicit")
@JavaVersionSupport({JAVA_21, JAVA_17})
@Operations({NonImplicitOperations.class})
@Xml(namespace = "http://www.mulesoft.org/schema/mule/implicit", prefix = "non-implicit")
@ConnectionProviders(NonImplicitConnectionProvider.class)
@NoImplicit
public class NonImplicitConfigExtension implements Initialisable, Startable, MuleContextAware {

  private MuleContext muleContext;
  private int initialise = 0;
  private int start = 0;

  @Parameter
  @Optional
  private String optionalNoDefault;

  @Parameter
  @Optional(defaultValue = "#[vars.number]")
  private Integer optionalWithDefault;

  @org.mule.sdk.api.annotation.param.ParameterGroup(name = "nullSafeGroup")
  private NullSafeParameterGroup nullSafeGroup;

  @ParameterGroup(name = "nullSafeGroupShowInDsl", showInDsl = true)
  private NullSafeParameterGroupShowInDsl nullSafeGroupShowInDsl;

  @Override
  public void initialise() throws InitialisationException {
    initialise++;
  }

  @Override
  public void setMuleContext(MuleContext context) {
    muleContext = context;
  }

  @Override
  public void start() throws MuleException {
    start++;
  }

  public MuleContext getMuleContext() {
    return muleContext;
  }

  public int getInitialise() {
    return initialise;
  }

  public int getStart() {
    return start;
  }

  public String getOptionalNoDefault() {
    return optionalNoDefault;
  }

  public Integer getOptionalWithDefault() {
    return optionalWithDefault;
  }

  public NullSafeParameterGroup getNullSafeGroup() {
    return nullSafeGroup;
  }

  public void setNullSafeGroup(NullSafeParameterGroup nullSafeGroup) {
    this.nullSafeGroup = nullSafeGroup;
  }

  public NullSafeParameterGroupShowInDsl getNullSafeGroupShowInDsl() {
    return nullSafeGroupShowInDsl;
  }

  public void setNullSafeGroupShowInDsl(NullSafeParameterGroupShowInDsl nullSafeGroupShowInDsl) {
    this.nullSafeGroupShowInDsl = nullSafeGroupShowInDsl;
  }
}
