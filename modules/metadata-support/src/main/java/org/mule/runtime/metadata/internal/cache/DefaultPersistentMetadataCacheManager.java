/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.metadata.internal.cache;

import static org.mule.runtime.api.util.MuleSystemProperties.SYSTEM_PROPERTY_PREFIX;
import static org.mule.runtime.core.api.config.MuleProperties.OBJECT_STORE_MANAGER;
import static org.mule.runtime.core.api.util.StringUtils.isBlank;

import static java.lang.String.format;
import static java.lang.System.getProperty;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;

import static org.apache.commons.lang3.math.NumberUtils.toLong;

import org.mule.runtime.api.lifecycle.Startable;
import org.mule.runtime.api.lock.LockFactory;
import org.mule.runtime.api.metadata.MetadataCache;
import org.mule.runtime.api.store.ObjectDoesNotExistException;
import org.mule.runtime.api.store.ObjectStore;
import org.mule.runtime.api.store.ObjectStoreException;
import org.mule.runtime.api.store.ObjectStoreManager;
import org.mule.runtime.api.store.ObjectStoreSettings;
import org.mule.runtime.api.util.LazyValue;

import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of a {@link MetadataCacheManager}. This implementation provides a way of managing persistent
 * {@link MetadataCache}s that are stored at container-level using the ObjectStore as handler for the persistence.
 *
 * Cache's are <b>never evicted</b>, and will be cleared only when an explicit disposal is invoked.
 *
 * @since 4.1.4, 4.2.0
 */
public class DefaultPersistentMetadataCacheManager implements MetadataCacheManager, Startable {

  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultPersistentMetadataCacheManager.class);
  public static final String PERSISTENT_METADATA_SERVICE_CACHE = "_mulePersistentMetadataService";
  public static final String MULE_METADATA_CACHE_ENTRY_TTL = SYSTEM_PROPERTY_PREFIX + "metadata.cache.entryTtl.minutes";
  public static final String MULE_METADATA_CACHE_EXPIRATION_INTERVAL =
      SYSTEM_PROPERTY_PREFIX + "metadata.cache.expirationInterval.millis";

  /**
   * Default implementation should use an {@link ObjectStoreManager} that is tied to the deployable artifact lifecyle.
   */
  @Inject
  @Named(OBJECT_STORE_MANAGER)
  private ObjectStoreManager objectStoreManager;

  /**
   * Default implementation should a {@link LockFactory} that comes from the deployable artifact context.
   */
  @Inject
  private LockFactory lockFactory;

  public void setLockFactory(LockFactory lockFactory) {
    this.lockFactory = lockFactory;
  }

  public void setObjectStoreManager(ObjectStoreManager objectStoreManager) {
    this.objectStoreManager = objectStoreManager;
  }

  private LazyValue<ObjectStore<MetadataCache>> metadataStore;

  @Override
  public void start() {
    metadataStore = new LazyValue<>(() -> {
      ObjectStoreSettings.Builder builder = ObjectStoreSettings.builder().persistent(true);
      getSystemProperty(MULE_METADATA_CACHE_ENTRY_TTL).map(stringValue -> toLong(stringValue))
          .ifPresent(entryTtl -> builder.entryTtl(MINUTES.convert(entryTtl, MILLISECONDS)));
      getSystemProperty(MULE_METADATA_CACHE_EXPIRATION_INTERVAL).map(stringValue -> toLong(stringValue))
          .ifPresent(expirationInterval -> builder.expirationInterval(expirationInterval));
      return objectStoreManager.getOrCreateObjectStore(PERSISTENT_METADATA_SERVICE_CACHE, builder.build());
    });
  }

  private Optional<String> getSystemProperty(String propertyName) {
    return ofNullable(getProperty(propertyName, null));
  }

  @Override
  public MetadataCache getOrCreateCache(String id) {
    return withKeyLock(id, key -> {
      try {
        if (metadataStore.get().contains(key)) {
          if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(format("Retrieving cache from OS with ID '%s'", id));
          }
          return metadataStore.get().retrieve(key);
        }

        if (LOGGER.isDebugEnabled()) {
          LOGGER.debug(format("Creating new cache in OS with ID '%s'", id));
        }
        DefaultMetadataCache metadataCache = new DefaultMetadataCache();
        metadataStore.get().store(key, metadataCache);
        return metadataCache;

      } catch (Exception e) {
        String msg = format("An error occurred while retrieving the MetadataCache with ID '%s': %s",
                            id, e.getMessage());
        LOGGER.error(msg);
        throw new RuntimeException(msg, e);
      }
    });
  }

  @Override
  public void updateCache(String id, MetadataCache cache) {
    withKeyLock(id, key -> {
      try {
        if (LOGGER.isDebugEnabled()) {
          LOGGER.debug(format("Updating cache in OS with ID '%s'", id));
        }
        if (metadataStore.get().contains(key)) {
          metadataStore.get().remove(key);
        }
        metadataStore.get().store(key, cache);
      } catch (Exception e) {
        String msg = format("An error occurred while updating the MetadataCache with ID '%s': %s",
                            id, e.getMessage());
        LOGGER.error(msg);
        throw new RuntimeException(msg, e);
      }
      return null;
    });
  }

  @Override
  public void dispose(String keyHash) {
    withKeyLock(keyHash, key -> {
      try {
        if (isBlank(keyHash)) {
          clearMetadataCaches();
        } else {
          if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(format("Removing cache in OS with ID '%s'", key));
          }
          metadataStore.get().remove(key);
        }
      } catch (ObjectDoesNotExistException e) {
        if (LOGGER.isDebugEnabled()) {
          LOGGER
              .debug(format("No exact match found for key '%s'. Disposing all the elements with a prefix matching the given value.",
                            key));
        }
        disposeAllMatches(keyHash);
      } catch (Exception e) {
        String msg = format("An error occurred while disposing the MetadataCache with ID '%s': %s",
                            keyHash, e.getMessage());
        LOGGER.error(msg);
        throw new RuntimeException(msg, e);
      }
      return null;
    });
  }

  private void disposeAllMatches(String keyHash) {
    try {
      metadataStore.get().allKeys().stream()
          .filter(id -> id.startsWith(keyHash))
          .forEach(id -> {
            try {
              this.dispose(id);
            } catch (Exception inner) {
              if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(format("Failed to dispose ID '%s' with partial prefix match: %s", id, inner.getMessage()));
              }
            }
          });
    } catch (ObjectStoreException e) {
      String msg = format("Failed to perform a cache disposal for partial prefix ID '%s': %s",
                          keyHash, e.getMessage());
      LOGGER.error(msg);
      throw new RuntimeException(msg, e);
    }
  }

  private void clearMetadataCaches() {
    try {
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("Clearing cache from OS");
      }
      metadataStore.get().clear();
    } catch (ObjectStoreException e) {
      String msg = format("An error occurred while clearing MetadataCaches: %s", e.getMessage());
      LOGGER.error(msg);
      throw new RuntimeException(msg, e);
    }
  }

  private <T> T withKeyLock(String key, Function<String, T> producer) {
    Lock lock = lockFactory.createLock(key);
    lock.lock();
    try {
      return producer.apply(key);
    } finally {
      lock.unlock();
    }
  }

}
