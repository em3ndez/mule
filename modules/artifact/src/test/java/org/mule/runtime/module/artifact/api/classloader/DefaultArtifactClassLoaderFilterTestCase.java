/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.artifact.api.classloader;

import static java.util.Arrays.asList;
import static java.util.Collections.singleton;

import static org.hamcrest.MatcherAssert.assertThat;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.core.Is.is;

import org.mule.tck.junit4.AbstractMuleTestCase;
import org.mule.tck.size.SmallTest;

import java.lang.annotation.Annotation;
import java.util.HashSet;

import org.junit.Test;

@SmallTest
public class DefaultArtifactClassLoaderFilterTestCase extends AbstractMuleTestCase {

  private final ArtifactClassLoaderFilter filter =
      new DefaultArtifactClassLoaderFilter(singleton("java.lang"), singleton("META-INF/schema.xsd"));

  @Test
  public void filtersClassWhenPackageNotExported() throws Exception {
    assertThat(filter.exportsClass(java.io.Closeable.class.getName()), equalTo(false));
  }

  @Test
  public void filtersClassWhenPackageNotExportedAndParentPackageIsExported() throws Exception {
    assertThat(filter.exportsClass(Annotation.class.getName()), equalTo(false));
  }

  @Test
  public void acceptsClassWhenPackageExported() throws Exception {
    assertThat(filter.exportsClass(Object.class.getName()), equalTo(true));
  }

  @Test
  public void acceptsExportedResource() throws Exception {
    assertThat(filter.exportsResource("/META-INF/schema.xsd"), equalTo(true));
  }

  @Test
  public void acceptsExportedPackage() throws Exception {
    assertThat(filter.exportsPackage("java.lang"), is(true));
  }

  @Test
  public void filtersNotExportedResource() throws Exception {
    assertThat(filter.exportsResource("/META-INF/readme.txt"), equalTo(false));
  }

  @Test(expected = IllegalArgumentException.class)
  public void validatesNullClassName() throws Exception {
    filter.exportsClass(null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void validatesEmptyClassName() throws Exception {
    filter.exportsClass("");
  }

  @Test(expected = IllegalArgumentException.class)
  public void validatesNullResourceName() throws Exception {
    filter.exportsResource(null);
  }

  @Test
  public void validatesEmptyResourceName() throws Exception {
    assertThat(filter.exportsResource(""), equalTo(false));
  }

  @Test
  public void validatesEmptyPackageName() throws Exception {
    ArtifactClassLoaderFilter filter =
        new DefaultArtifactClassLoaderFilter(new HashSet<>(asList("java.lang", "")), singleton("META-INF/schema.xsd"));
    assertThat(filter.exportsPackage(""), is(true));
  }

  @Test
  public void filterDefaultPackage() throws Exception {
    assertThat(filter.exportsPackage(""), is(false));
  }

}
