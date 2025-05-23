/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.test.functional;

import static java.util.Arrays.asList;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;
import static org.mule.functional.junit4.matchers.MessageMatchers.hasPayload;

import org.mule.runtime.api.message.Message;
import org.mule.runtime.extension.internal.ast.MacroExpansionModuleModel;
import org.mule.test.runner.RunnerDelegateTo;

import java.util.Arrays;
import java.util.Collection;

import org.apache.commons.lang3.StringUtils;
import org.junit.Test;
import org.junit.runners.Parameterized;

/**
 * Test case to guarantee that the {@link MacroExpansionModuleModel} does its work properly. The Mule application relies on a set
 * of modules that will be feed in several orders, proving that the topological order while macro expanding do work accordingly.
 * <p/>
 * The current application graph dependencies with plugins to macro expand is as follow:
 *
 * <pre>
 *   +---------------------------------------------+
 *   |                                             |
 *   |                                           +-v-+
 *   | +-----------------------+ +---------------> Z |
 *   | |                       | |               +---+
 *   | |                      +v-++     +---+
 *   | |               +------> B +-----> A |
 *   | |               |      +---+     +---+
 *   | |             +-+-+
 *   | | +-----------> C +----------------+
 *   | | |           +---+                |
 *   +-+-+-+                              |
 *   |     |                   +---+    +-v-+
 *   | APP +-------------------> X +----> W <--+
 *   |     |                   +-^-+    +--++  |
 *   +---+-+                     |         |   |
 *       |           +---+       |         +---+
 *       +-----------> Y +-------+
 *                   +---+
 * </pre>
 */
@RunnerDelegateTo(Parameterized.class)
public class ModulesUsingGraphTestCase extends AbstractCeXmlExtensionMuleArtifactFunctionalTestCase {

  private static final String MODULES_GRAPH_FOLDER = "modules/graph/module-";
  private static final String SUFFIX_XML_FILE = ".xml";

  @Parameterized.Parameter
  public String permutation;

  @Parameterized.Parameter(1)
  public String[] paths;

  @Parameterized.Parameters(name = "{index}: Running permutation modules {0} ")
  public static Collection<Object[]> data() {
    // The extensions are loaded in correct order according to their dependencies, so it is pointless to test with impossible
    // permutations.
    return asList(getParameters("w", "x", "y", "z", "a", "b", "c"),
                  getParameters("w", "z", "a", "b", "x", "c", "y"));
  }

  private static Object[] getParameters(String... modules) {
    return new Object[] {StringUtils.join(modules, "-"),
        Arrays.asList(modules).stream()
            .map(moduleName -> MODULES_GRAPH_FOLDER + moduleName + SUFFIX_XML_FILE)
            .toArray(String[]::new)
    };
  }

  @Override
  protected String[] getModulePaths() {
    return paths;
  }

  @Override
  protected String getConfigFile() {
    return "flows/flows-using-graph-modules.xml";
  }

  @Test
  public void testUsingModuleB_Op1() throws Exception {
    assertCalls("testUsingModuleB_Op1", "b-op1 a-op1 z-op1");
  }

  @Test
  public void testUsingModuleC_Op1() throws Exception {
    assertCalls("testUsingModuleC_Op1", "c-op1 b-op1 a-op1 z-op1");
  }

  @Test
  public void testUsingModuleC_Op2() throws Exception {
    assertCalls("testUsingModuleC_Op2", "c-op2 a-op1 z-op1");
  }

  @Test
  public void testUsingModuleC_Op3() throws Exception {
    assertCalls("testUsingModuleC_Op3", "c-op3 w-op1 w-internal-op");
  }

  @Test
  public void testUsingModuleX_Op1() throws Exception {
    assertCalls("testUsingModuleX_Op1", "x-op1 w-op1 w-internal-op");
  }

  @Test
  public void testUsingModuleY_Op1() throws Exception {
    assertCalls("testUsingModuleY_Op1", "y-op1 x-op1 w-op1 w-internal-op");
  }

  @Test
  public void testUsingModuleZ_Op1() throws Exception {
    assertCalls("testUsingModuleZ_Op1", "z-op1");
  }

  private void assertCalls(String flow, String expected) throws Exception {
    final Message consumedMessage = flowRunner(flow).run().getMessage();
    assertThat(consumedMessage, hasPayload(equalTo(expected)));
  }
}
