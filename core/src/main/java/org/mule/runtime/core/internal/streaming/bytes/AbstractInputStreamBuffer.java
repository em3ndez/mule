/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.streaming.bytes;

import static org.mule.runtime.api.util.Preconditions.checkState;

import static java.lang.Math.min;
import static java.lang.Thread.currentThread;
import static java.lang.Thread.interrupted;

import static org.slf4j.LoggerFactory.getLogger;

import org.mule.runtime.core.api.streaming.bytes.ByteBufferManager;
import org.mule.runtime.core.internal.streaming.AbstractStreamingBuffer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import org.slf4j.Logger;

/**
 * Base class for implementations of {@link InputStreamBuffer}.
 * <p>
 * Contains the base algorithm and template methods so that implementations can be created easily
 *
 * @since 4.0
 */
public abstract class AbstractInputStreamBuffer extends AbstractStreamingBuffer implements InputStreamBuffer {

  private static final Logger LOGGER = getLogger(AbstractInputStreamBuffer.class);

  protected final InputStream stream;
  protected final ByteBufferManager bufferManager;

  protected boolean streamFullyConsumed = false;

  /**
   * Creates a new instance
   *
   * @param stream        The stream being buffered. This is the original data source
   * @param bufferManager the {@link ByteBufferManager} that will be used to allocate all buffers
   */
  public AbstractInputStreamBuffer(InputStream stream, ByteBufferManager bufferManager) {
    this.stream = stream;
    this.bufferManager = bufferManager;
  }

  /**
   * Consumes the stream in order to obtain data that has not been read yet.
   *
   * @return the amount of bytes read
   * @throws IOException
   */
  public abstract int consumeForwardData() throws IOException;

  /**
   * {@inheritDoc}
   */
  @Override
  public final void close() {
    if (closed.compareAndSet(false, true)) {
      writeLock.lock();
      try {
        doClose();
      } finally {
        if (stream != null) {
          try {
            stream.close();
          } catch (IOException e) {
            LOGGER.debug("Found exception trying to close InputStream", e);
          }
        }
        writeLock.unlock();
      }
    }
  }

  /**
   * Template method to support the {@link #close()} operation
   */
  public abstract void doClose();

  /**
   * {@inheritDoc}
   *
   * @throws IllegalStateException if the buffer is closed
   */
  @Override
  public final ByteBuffer get(long position, int length) throws IOException {
    checkState(!closed.get(), "Buffer is closed");
    return doGet(position, length);
  }

  protected abstract ByteBuffer doGet(long position, int length) throws IOException;

  protected int consumeStream(ByteBuffer buffer) throws IOException {
    final byte[] dest = buffer.array();

    int totalRead = 0;
    int remaining = buffer.remaining();
    int offset = buffer.position();

    while (remaining > 0) {
      try {
        final var available = stream.available();
        if (totalRead > 0 && available < 1) {
          break;
        }

        int read = stream.read(dest, offset, readLength(remaining, available));

        if (read == -1) {
          streamFullyConsumed = true;
          if (totalRead == 0) {
            return -1;
          } else {
            break;
          }
        } else if (read == 0) {
          break;
        }

        totalRead += read;
        remaining -= read;
        offset += read;

        if (available > 0) {
          // available informed, honor it
          return advanceBufferPosition(buffer, totalRead, offset);
        }
        // else: no data available or not informed. Fill the buffer, block if necessary.
      } catch (IOException e) {
        return handleIoException(e, totalRead);
      }
    }

    return advanceBufferPosition(buffer, totalRead, offset);
  }

  private int readLength(int remaining, final int available) {
    return available > 0 ? min(remaining, available) : remaining;
  }

  private int advanceBufferPosition(ByteBuffer buffer, int totalRead, int offset) {
    if (totalRead > 0) {
      buffer.position(offset);
    }
    return totalRead;
  }

  private int handleIoException(IOException ioe, int totalRead) throws IOException {
    if (!interrupted()) {
      throw ioe;
    }

    final var thread = currentThread();
    thread.interrupt();
    LOGGER.warn("Thread {} interrupted while reading from stream.", thread.getName());

    if (totalRead == 0 || closed.get()) {
      streamFullyConsumed = true;
      return -1;
    }

    throw ioe;
  }

  protected abstract ByteBuffer copy(long position, int length);
}
