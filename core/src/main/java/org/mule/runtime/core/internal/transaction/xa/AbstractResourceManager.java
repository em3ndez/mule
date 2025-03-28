/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.transaction.xa;

import static org.mule.runtime.core.api.config.i18n.CoreMessages.resourceManagerDirty;
import static org.mule.runtime.core.api.config.i18n.CoreMessages.resourceManagerNotReady;
import static org.mule.runtime.core.api.config.i18n.CoreMessages.resourceManagerNotStarted;
import static org.mule.runtime.core.api.config.i18n.CoreMessages.transactionMarkedForRollback;

import static java.lang.Thread.currentThread;

import org.mule.runtime.core.api.transaction.xa.ResourceManagerException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.transaction.Status;

/**
 * This code is based on code coming from the <a href="http://jakarta.apache.org/commons/transaction/">commons-transaction</a>
 * project.
 */
public abstract class AbstractResourceManager {

  /**
   * Shutdown mode: Wait for all transactions to complete
   */
  private static final int SHUTDOWN_MODE_NORMAL = 0;

  /**
   * Shutdown mode: Try to roll back all active transactions
   */
  private static final int SHUTDOWN_MODE_ROLLBACK = 1;

  /**
   * Shutdown mode: Try to stop active transaction <em>NOW</em>, do no rollbacks
   */
  private static final int SHUTDOWN_MODE_KILL = 2;

  private static final int OPERATION_MODE_STOPPED = 0;
  private static final int OPERATION_MODE_STOPPING = 1;
  private static final int OPERATION_MODE_STARTED = 2;
  private static final int OPERATION_MODE_STARTING = 3;
  protected static final int OPERATION_MODE_RECOVERING = 4;

  private static final int DEFAULT_TIMEOUT_MSECS = 5000;
  private static final int DEFAULT_COMMIT_TIMEOUT_FACTOR = 2;

  private Collection<AbstractTransactionContext> globalTransactions =
      Collections.synchronizedCollection(new ArrayList<AbstractTransactionContext>());
  private int operationMode = OPERATION_MODE_STOPPED;
  private long defaultTimeout = DEFAULT_TIMEOUT_MSECS;

  protected Logger logger = LoggerFactory.getLogger(getClass());

  private boolean dirty = false;

  public synchronized void start() {
    logger.info("Starting ResourceManager");
    operationMode = OPERATION_MODE_STARTING;
    doStart();
    recover();
    operationMode = OPERATION_MODE_STARTED;
    if (dirty) {
      logger.warn("Started ResourceManager, but in dirty mode only (Recovery of pending transactions failed)");
    } else {
      logger.info("Started ResourceManager");
    }
  }

  protected void doStart() {
    // template method
  }

  protected void recover() {
    // nothing to do (yet?)
  }

  public synchronized void stop() {
    stop(SHUTDOWN_MODE_NORMAL);
  }

  public synchronized boolean stop(int mode) {
    return stop(mode, getDefaultTransactionTimeout() * DEFAULT_COMMIT_TIMEOUT_FACTOR);
  }

  public synchronized boolean stop(int mode, long timeOut) {
    logger.info("Stopping ResourceManager");
    operationMode = OPERATION_MODE_STOPPING;
    boolean success = shutdown(mode, timeOut);
    if (success) {
      operationMode = OPERATION_MODE_STOPPED;
      logger.info("Stopped ResourceManager");
    } else {
      logger.warn("Failed to stop ResourceManager");
    }

    return success;
  }

  protected boolean shutdown(int mode, long timeoutMSecs) {
    return switch (mode) {
      case SHUTDOWN_MODE_NORMAL -> waitForAllTxToStop(timeoutMSecs);
      case SHUTDOWN_MODE_ROLLBACK -> throw new UnsupportedOperationException();
      case SHUTDOWN_MODE_KILL -> true;
      default -> false;
    };
  }

  /**
   * Gets the default transaction timeout in <em>milliseconds</em>.
   */
  public long getDefaultTransactionTimeout() {
    return defaultTimeout;
  }

  /**
   * Sets the default transaction timeout.
   *
   * @param timeout timeout in <em>milliseconds</em>
   */
  public void setDefaultTransactionTimeout(long timeout) {
    defaultTimeout = timeout;
  }

  public void beginTransaction(AbstractTransactionContext context) throws ResourceManagerException {
    // can only start a new transaction when not already stopping
    assureStarted();

    synchronized (context) {
      if (logger.isDebugEnabled()) {
        logger.debug("Beginning transaction {}", context);
      }
      doBegin(context);
      context.status = Status.STATUS_ACTIVE;
      if (logger.isDebugEnabled()) {
        logger.debug("Began transaction {}", context);
      }
    }
    globalTransactions.add(context);
  }

  public void rollbackTransaction(AbstractTransactionContext context) throws ResourceManagerException {
    assureReady();
    synchronized (context) {
      if (logger.isDebugEnabled()) {
        logger.debug("Rolling back transaction {}", context);
      }
      try {
        context.status = Status.STATUS_ROLLING_BACK;
        doRollback(context);
        context.status = Status.STATUS_ROLLEDBACK;
      } catch (Exception e) {
        setDirty(context, e);
        throw e;
      } finally {
        globalTransactions.remove(context);
        context.finalCleanUp();
        // tell shutdown thread this tx is finished
        context.notifyFinish();
      }
      if (logger.isDebugEnabled()) {
        logger.debug("Rolled back transaction {}", context);
      }
    }
  }

  public void setTransactionRollbackOnly(AbstractTransactionContext context) {
    context.status = Status.STATUS_MARKED_ROLLBACK;
  }

  public void commitTransaction(AbstractTransactionContext context) throws ResourceManagerException {
    assureReady();
    if (context.status == Status.STATUS_MARKED_ROLLBACK) {
      throw new ResourceManagerException(transactionMarkedForRollback());
    }
    synchronized (context) {
      if (logger.isDebugEnabled()) {
        logger.debug("Committing transaction {}", context);
      }
      try {
        context.status = Status.STATUS_COMMITTING;
        doCommit(context);
        context.status = Status.STATUS_COMMITTED;
      } catch (ResourceManagerException e) {
        logger.warn("Could not commit tx " + context + ", rolling back instead", e);
        doRollback(context);
      } catch (Exception e) {
        setDirty(context, e);
        throw e;
      } finally {
        globalTransactions.remove(context);
        context.finalCleanUp();
        // tell shutdown thread this tx is finished
        context.notifyFinish();
      }
      if (logger.isDebugEnabled()) {
        logger.debug("Committed transaction {}", context);
      }
    }
  }

  protected abstract void doBegin(AbstractTransactionContext context);

  protected abstract void doCommit(AbstractTransactionContext context) throws ResourceManagerException;

  protected abstract void doRollback(AbstractTransactionContext context) throws ResourceManagerException;

  private boolean waitForAllTxToStop(long timeoutMSecs) {
    long startTime = System.currentTimeMillis();

    // be sure not to lock globalTransactions for too long, as we need to
    // give
    // txs the chance to complete (otherwise deadlocks are very likely to
    // occur)
    // instead iterate over a copy as we can be sure no new txs will be
    // registered
    // after operation level has been set to stopping

    Collection<AbstractTransactionContext> transactionsToStop;
    synchronized (globalTransactions) {
      transactionsToStop = new ArrayList<>(globalTransactions);
    }
    for (AbstractTransactionContext context : transactionsToStop) {
      long remainingTimeout = startTime - System.currentTimeMillis() + timeoutMSecs;

      if (remainingTimeout <= 0) {
        return false;
      }

      synchronized (context) {
        if (!context.finished) {
          logger.info("Waiting for tx {} to finish for {} milli seconds", context, remainingTimeout);
        }
        while (!context.finished && remainingTimeout > 0) {
          try {
            context.wait(remainingTimeout);
          } catch (InterruptedException e) {
            currentThread().interrupt();
            return false;
          }
          remainingTimeout = startTime - System.currentTimeMillis() + timeoutMSecs;
        }
        if (context.finished) {
          logger.info("Tx {} finished", context);
        } else {
          logger.warn("Tx {} failed to finish in given time", context);
        }
      }
    }

    return globalTransactions.isEmpty();
  }

  /**
   * Flag this resource manager as dirty. No more operations will be allowed until a recovery has been successfully performed.
   *
   * @param context
   * @param t
   */
  private void setDirty(AbstractTransactionContext context, Throwable t) {
    logger.error("Fatal error during critical commit/rollback of transaction {}, setting resource manager to dirty.", context, t);
    dirty = true;
  }

  /**
   * Check that the FileManager is started.
   *
   * @throws FileManagerSystemException if the FileManager is not started.
   */
  private void assureStarted() throws ResourceManagerSystemException {
    if (operationMode != OPERATION_MODE_STARTED) {
      throw new ResourceManagerSystemException(resourceManagerNotStarted());
    }
    // do not allow any further writing or commit or rollback when db is
    // corrupt
    if (dirty) {
      throw new ResourceManagerSystemException(resourceManagerDirty());
    }
  }

  /**
   * Check that the FileManager is ready.
   *
   * @throws FileManagerSystemException if the FileManager is neither started not stopping.
   */
  protected void assureReady() throws ResourceManagerSystemException {
    if (operationMode != OPERATION_MODE_STARTED && operationMode != OPERATION_MODE_STOPPING) {
      throw new ResourceManagerSystemException(resourceManagerNotReady());
    }
    // do not allow any further writing or commit or rollback when db is
    // corrupt
    if (dirty) {
      throw new ResourceManagerSystemException(resourceManagerDirty());
    }
  }

}
