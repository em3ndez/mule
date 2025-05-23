/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.test.vegan.extension;


import static org.mule.sdk.api.meta.JavaVersion.JAVA_17;
import static org.mule.sdk.api.meta.JavaVersion.JAVA_21;

import org.mule.runtime.extension.api.annotation.Configurations;
import org.mule.runtime.extension.api.annotation.Extension;
import org.mule.runtime.extension.api.annotation.Operations;
import org.mule.runtime.extension.api.annotation.SubTypeMapping;
import org.mule.runtime.extension.api.annotation.metadata.MetadataScope;
import org.mule.sdk.api.annotation.JavaVersionSupport;

@Extension(name = VeganExtension.VEGAN)
@JavaVersionSupport({JAVA_21, JAVA_17})
@Configurations({AppleConfig.class, BananaConfig.class, KiwiConfig.class, PeachConfig.class, PearConfig.class, GrapeConfig.class})
@Operations(VeganFidelityOperation.class)
@SubTypeMapping(baseType = FarmedFood.class, subTypes = {RottenFood.class, HealthyFood.class})
@MetadataScope(keysResolver = AppleTypesResolver.class)
public class VeganExtension {

  public static final String VEGAN = "vegan";
  public static final String APPLE = "apple-config";
  public static final String BANANA = "banana-config";
  public static final String KIWI = "kiwi-config";
  public static final String PEACH = "peach-config";

}

