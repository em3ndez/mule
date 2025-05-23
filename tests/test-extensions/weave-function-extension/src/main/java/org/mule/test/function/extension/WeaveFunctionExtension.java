/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.test.function.extension;

import static org.mule.sdk.api.meta.JavaVersion.JAVA_17;
import static org.mule.sdk.api.meta.JavaVersion.JAVA_21;

import org.mule.runtime.extension.api.annotation.ExpressionFunctions;
import org.mule.runtime.extension.api.annotation.Extension;
import org.mule.runtime.extension.api.annotation.Import;
import org.mule.runtime.extension.api.annotation.ImportedTypes;
import org.mule.runtime.extension.api.annotation.Operations;
import org.mule.runtime.extension.api.annotation.dsl.xml.Xml;
import org.mule.sdk.api.annotation.JavaVersionSupport;
import org.mule.test.heisenberg.extension.model.KnockeableDoor;



@Extension(name = "Test Functions")
@JavaVersionSupport({JAVA_21, JAVA_17})
@ExpressionFunctions(GlobalWeaveFunction.class)
@Operations(WeaveTestUtilsOperations.class)
@Xml(prefix = "fn")
@ImportedTypes(@Import(type = KnockeableDoor.class))
public class WeaveFunctionExtension {

}
