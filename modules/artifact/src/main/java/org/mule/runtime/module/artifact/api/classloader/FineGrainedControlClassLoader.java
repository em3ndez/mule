/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.artifact.api.classloader;

import static org.mule.runtime.api.util.MuleSystemProperties.MULE_LOG_VERBOSE_CLASSLOADING;
import static org.mule.runtime.module.artifact.api.classloader.BlockingLoggerResolutionClassRegistry.getBlockingLoggerResolutionClassRegistry;

import static java.lang.Boolean.valueOf;
import static java.lang.String.format;
import static java.lang.System.getProperty;
import static java.net.URLConnection.setDefaultUseCaches;
import static java.util.Objects.requireNonNull;

import static org.slf4j.LoggerFactory.getLogger;

import org.mule.api.annotation.NoInstantiate;
import org.mule.runtime.core.api.util.ClassUtils;
import org.mule.runtime.core.api.util.CompoundEnumeration;
import org.mule.runtime.module.artifact.api.classloader.exception.CompositeClassNotFoundException;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import org.slf4j.Logger;

/**
 * Defines a {@link ClassLoader} which enables the control of the class loading lookup mode.
 * <p/>
 * By using a {@link ClassLoaderLookupPolicy} this classLoader can use parent-first, parent-only or child-first classloading
 * lookup mode per package.
 */
@NoInstantiate
public class FineGrainedControlClassLoader extends URLClassLoader
    implements DisposableClassLoader, ClassLoaderLookupPolicyProvider {

  static {
    registerAsParallelCapable();

    // Disables the default caching behavior of {@link JarURLConnection} for the "jar" protocol.
    // By default, Java caches open JAR file handles when resolving resources via {@link JarURLConnection}. This can cause file
    // locking issues, especially on Windows platforms, preventing the deletion or modification of JAR files during the
    // un-deployment of applications or extensions.
    setDefaultUseCaches("jar", false);

    getBlockingLoggerResolutionClassRegistry().registerClassNeedingBlockingLoggerResolution(FineGrainedControlClassLoader.class);
  }

  private static final Logger LOGGER = getLogger(FineGrainedControlClassLoader.class);

  private final ClassLoaderLookupPolicy lookupPolicy;
  private final boolean verboseLogging;

  public FineGrainedControlClassLoader(URL[] urls, ClassLoader parent, ClassLoaderLookupPolicy lookupPolicy) {
    super(urls, parent);
    this.lookupPolicy = requireNonNull(lookupPolicy, "Lookup policy cannot be null");
    verboseLogging = valueOf(getProperty(MULE_LOG_VERBOSE_CLASSLOADING));
  }

  private boolean isVerboseLogging() {
    return verboseLogging || LOGGER.isDebugEnabled();
  }

  @Override
  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    Class<?> result = findLoadedClass(name);

    if (result != null) {
      return result;
    }

    final LookupStrategy lookupStrategy = lookupPolicy.getClassLookupStrategy(name);
    if (lookupStrategy == null) {
      throw new NullPointerException(format("Unable to find a lookup strategy for '%s' from %s", name, this));
    }

    if (isVerboseLogging()) {
      logLoadingClass(name, lookupStrategy, "Loading class '%s' with '%s' on '%s'", this);
    }

    // Gather information about the exceptions in each of the searched class loaders to provide
    // troubleshooting information in case of throwing a ClassNotFoundException.

    List<ClassNotFoundException> exceptions = new ArrayList<>();
    for (ClassLoader classLoader : lookupStrategy.getClassLoaders(this)) {
      try {
        if (classLoader == this) {
          result = findLocalClass(name);
          break;
        } else {
          result = findParentClass(name, classLoader);
          break;
        }
      } catch (ClassNotFoundException e) {
        exceptions.add(e);
      }
    }

    if (result == null) {
      final CompositeClassNotFoundException compositeClassNotFoundException =
          new CompositeClassNotFoundException(name, lookupStrategy, exceptions);
      if (isVerboseLogging()) {
        LOGGER.warn(compositeClassNotFoundException.getMessage());
      }
      throw compositeClassNotFoundException;
    }

    if (isVerboseLogging()) {
      logLoadedClass(name, result);
    }

    if (resolve) {
      resolveClass(result);
    }

    return result;
  }

  private void logLoadingClass(String name, LookupStrategy lookupStrategy, String format,
                               FineGrainedControlClassLoader fineGrainedControlClassLoader) {
    final String message = format(format, name, lookupStrategy, fineGrainedControlClassLoader);
    doVerboseLogging(message);
  }

  private void logLoadedClass(String name, Class<?> result) {
    final boolean loadedFromChild = result.getClassLoader() == this;
    final String message = format("Loaded class '%s' from %s: %s", name, (loadedFromChild ? "child" : "parent"),
                                  (loadedFromChild ? this : getParent()));
    doVerboseLogging(message);
  }

  private void doVerboseLogging(String message) {
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug(message);
    } else {
      LOGGER.info(message);
    }
  }

  protected Class<?> findParentClass(String name, ClassLoader classLoader) throws ClassNotFoundException {
    if (classLoader != null) {
      return classLoader.loadClass(name);
    } else {
      return findSystemClass(name);
    }
  }

  @Override
  public URL getResource(String name) {
    URL url = findResource(name);
    if (url == null && getParent() != null) {
      url = getParent().getResource(name);
    }
    return url;
  }

  @Override
  public Enumeration<URL> getResources(String name) throws IOException {
    Enumeration<URL>[] tmp = (Enumeration<URL>[]) new Enumeration<?>[2];
    tmp[0] = findResources(name);
    if (getParent() != null) {
      tmp[1] = getParent().getResources(name);
    }

    return new CompoundEnumeration<>(tmp);
  }

  public Class<?> findLocalClass(String name) throws ClassNotFoundException {
    synchronized (getClassLoadingLock(name)) {
      Class<?> result = findLoadedClass(name);

      if (result != null) {
        return result;
      }

      return super.findClass(name);
    }
  }

  @Override
  public ClassLoaderLookupPolicy getClassLoaderLookupPolicy() {
    return lookupPolicy;
  }

  /**
   * Disposes the {@link ClassLoader} by closing all the resources opened by this {@link ClassLoader}. See
   * {@link URLClassLoader#close()}.
   */
  @Override
  public void dispose() {
    try {
      // Java 7 added support for closing a URLClassLoader, it will close any resources opened by this classloader
      close();
    } catch (IOException e) {
      // ignore
    }

    try {
      // fix groovy compiler leaks http://www.mulesoft.org/jira/browse/MULE-5125
      final Class clazz = ClassUtils.loadClass("org.codehaus.groovy.transform.ASTTransformationVisitor", getClass());
      final Field compUnit = clazz.getDeclaredField("compUnit");
      compUnit.setAccessible(true);
      // static field
      compUnit.set(null, null);
    } catch (Throwable t) {
      // ignore
    }
  }
}
