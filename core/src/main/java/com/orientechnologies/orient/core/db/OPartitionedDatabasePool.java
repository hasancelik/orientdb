package com.orientechnologies.orient.core.db;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx;

/**
 * @author Andrey Lomakin <a href="mailto:lomakin.andrey@gmail.com">Andrey Lomakin</a>
 * @since 06/11/14
 */
public class OPartitionedDatabasePool {
  private final String         url;
  private final String         userName;
  private final String         password;

  private final int            maxSize;

  private final AtomicInteger  currentSize         = new AtomicInteger();
  private final AtomicInteger  acquiredConnections = new AtomicInteger();

  private static final int     HASH_INCREMENT      = 0x61c88647;
  private static final int     MIN_POOL_SIZE       = 1;

  private static AtomicInteger nextHashCode        = new AtomicInteger();

  private static int nextHashCode() {
    return nextHashCode.getAndAdd(HASH_INCREMENT);
  }

  private final ThreadLocal<PoolData>                                                 poolData      = new ThreadLocal<PoolData>() {
                                                                                                      @Override
                                                                                                      protected PoolData initialValue() {
                                                                                                        return new PoolData();
                                                                                                      }
                                                                                                    };

  private final AtomicBoolean                                                         poolBusy      = new AtomicBoolean();
  private final int                                                                   maxPartitions = Runtime.getRuntime()
                                                                                                        .availableProcessors() << 3;

  private volatile AtomicReference<ConcurrentLinkedQueue<DatabaseDocumentTxPolled>>[] partitions;

  public OPartitionedDatabasePool(String url, String userName, String password) {
    this(url, userName, password, 64);
  }

  public OPartitionedDatabasePool(String url, String userName, String password, int maxSize) {
    this.url = url;
    this.userName = userName;
    this.password = password;
    this.maxSize = maxSize;

    final AtomicReference<ConcurrentLinkedQueue<DatabaseDocumentTxPolled>>[] pts = new AtomicReference[2];
    for (int i = 0; i < pts.length; i++) {
      final ConcurrentLinkedQueue<DatabaseDocumentTxPolled> queue = new ConcurrentLinkedQueue<DatabaseDocumentTxPolled>();
      pts[i] = new AtomicReference<ConcurrentLinkedQueue<DatabaseDocumentTxPolled>>(queue);

      initQueue(url, queue);
    }

    partitions = pts;

  }

  private void initQueue(String url, ConcurrentLinkedQueue<DatabaseDocumentTxPolled> queue) {
    for (int n = 0; n < MIN_POOL_SIZE; n++) {
      final DatabaseDocumentTxPolled db = new DatabaseDocumentTxPolled(url);
      queue.add(db);
    }

    currentSize.addAndGet(MIN_POOL_SIZE);
  }

	public int getMaxSize() {
		return maxSize;
	}

	public int getAvailableConnections() {
    final int result = currentSize.get() - acquiredConnections.get();
    if (result < 0)
      return 0;

    return result;
  }

  public int getCreatedInstances() {
    return currentSize.get();
  }

  public ODatabaseDocumentTx acquire() {


    final PoolData data = poolData.get();
    if (data.acquireCount > 0) {
      data.acquireCount++;
      return data.acquiredDatabase;
    }

    acquiredConnections.incrementAndGet();
    try {
      while (true) {
				final AtomicReference<ConcurrentLinkedQueue<DatabaseDocumentTxPolled>>[] pts = partitions;

        final int index = (pts.length - 1) & data.hashCode;

        ConcurrentLinkedQueue<DatabaseDocumentTxPolled> queue = pts[index].get();
        if (queue == null) {
          if (poolBusy.compareAndSet(false, true)) {
            if (pts == partitions) {
              final AtomicReference<ConcurrentLinkedQueue<DatabaseDocumentTxPolled>> queueRef = pts[index];

              if (queueRef.get() == null) {
                queue = new ConcurrentLinkedQueue<DatabaseDocumentTxPolled>();
                initQueue(url, queue);
                queueRef.set(queue);
              }
            }

            poolBusy.set(false);
          }

          continue;
        } else {
          DatabaseDocumentTxPolled db = queue.poll();
          if (db == null) {
            if (pts.length < maxPartitions) {
              if (poolBusy.compareAndSet(false, true)) {
                if (pts == partitions) {
                  final AtomicReference<ConcurrentLinkedQueue<DatabaseDocumentTxPolled>>[] newPartitions = new AtomicReference[partitions.length << 1];
                  System.arraycopy(partitions, 0, newPartitions, 0, partitions.length);

                  for (int i = partitions.length - 1; i < newPartitions.length; i++)
                    newPartitions[i] = new AtomicReference<ConcurrentLinkedQueue<DatabaseDocumentTxPolled>>();

                  partitions = newPartitions;
                }

                poolBusy.set(false);
              }

              continue;
            } else {
              if (currentSize.get() >= maxSize)
                throw new IllegalStateException("You have reached maximum pool size");

              db = new DatabaseDocumentTxPolled(url);
              db.open(userName, password);
              db.queue = queue;

              data.acquireCount = 1;
              data.acquiredDatabase = db;

              currentSize.incrementAndGet();

              return db;
            }
          } else {
            db.open(userName, password);
            db.queue = queue;

            data.acquireCount = 1;
            data.acquiredDatabase = db;

            return db;
          }
        }
      }
    } catch (RuntimeException e) {
      acquiredConnections.decrementAndGet();
      throw e;
    }
  }

  private final class DatabaseDocumentTxPolled extends ODatabaseDocumentTx {
    private ConcurrentLinkedQueue<DatabaseDocumentTxPolled> queue;

    private DatabaseDocumentTxPolled(String iURL) {
      super(iURL);
    }

    @Override
    public void close() {
      final PoolData data = poolData.get();
			if (data.acquireCount == 0)
				return;

      data.acquireCount--;

      if (data.acquireCount > 0)
        return;

      ConcurrentLinkedQueue<DatabaseDocumentTxPolled> q = queue;
      queue = null;

      super.close();
      data.acquiredDatabase = null;

      q.offer(this);
      acquiredConnections.decrementAndGet();
    }
  }

  private static final class PoolData {
    private final int hashCode;

    private PoolData() {
      hashCode = nextHashCode();
    }

    private int                      acquireCount;
    private DatabaseDocumentTxPolled acquiredDatabase;
  }
}
