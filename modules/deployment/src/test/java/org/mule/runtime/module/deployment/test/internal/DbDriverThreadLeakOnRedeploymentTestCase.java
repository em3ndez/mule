/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.deployment.test.internal;

import static org.mule.test.allure.AllureConstants.LeakPrevention.LEAK_PREVENTION;
import static org.mule.test.allure.AllureConstants.LeakPrevention.LeakPreventionMetaspace.METASPACE_LEAK_PREVENTION_ON_REDEPLOY;

import static java.util.Arrays.asList;

import static org.apache.commons.lang3.JavaVersion.JAVA_17;
import static org.apache.commons.lang3.SystemUtils.isJavaVersionAtLeast;
import static org.hamcrest.core.Is.is;
import static org.junit.Assume.assumeThat;

import java.util.List;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Contains tests for leak prevention on the deployment process.
 */
@RunWith(Parameterized.class)
@Feature(LEAK_PREVENTION)
@Story(METASPACE_LEAK_PREVENTION_ON_REDEPLOY)
public class DbDriverThreadLeakOnRedeploymentTestCase extends DbDriverThreadLeakTestCase {

  @Parameterized.Parameters(name = "Parallel: {0}, AppName: {1}, Use Plugin: {2}")
  public static List<Object[]> parameters() {
    return asList(new Object[][] {
        {true, "appWithExtensionPlugin-1.0.0-mule-application",
            "oracle-db-app"}
    });
  }

  public DbDriverThreadLeakOnRedeploymentTestCase(boolean parallellDeployment, String appName, String xmlFile) {
    super(parallellDeployment, appName, xmlFile);
  }

  @Override
  public void oracleDriverTimerThreadsReleasedOnUndeploy() throws Exception {
    // Here we're testing a test extension (OracleExtension) instead of the actual DB Connector. In JDK17+, the DB Connector is
    // responsible or releasing these resources, then this test doesn't apply there.
    assumeThat(isJavaVersionAtLeast(JAVA_17), is(false));
    super.oracleDriverTimerThreadsReleasedOnUndeploy();
  }
}
