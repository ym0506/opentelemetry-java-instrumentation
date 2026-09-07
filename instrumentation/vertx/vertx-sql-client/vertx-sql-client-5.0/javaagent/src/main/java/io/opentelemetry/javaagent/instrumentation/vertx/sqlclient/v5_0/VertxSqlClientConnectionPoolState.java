/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.vertx.sqlclient.v5_0;

import static io.opentelemetry.javaagent.instrumentation.vertx.sqlclient.v5_0.VertxSqlClientQueryState.QUERY_STATE;

import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.util.VirtualField;
import io.opentelemetry.javaagent.instrumentation.vertx.sqlclient.v5_0.VertxSqlClientSingletons.ConnectionAttempt;
import io.vertx.core.Completable;
import io.vertx.core.internal.pool.ConnectionPool;
import io.vertx.core.internal.pool.PoolWaiter;
import javax.annotation.Nullable;

public final class VertxSqlClientConnectionPoolState {
  private static final VirtualField<ConnectionPool<?>, VertxSqlClientSupplierInfo> POOL_SUPPLIER =
      VirtualField.find(ConnectionPool.class, VertxSqlClientSupplierInfo.class);
  private static final VirtualField<PoolWaiter<?>, VertxSqlClientQueryState> WAITER_QUERY =
      VirtualField.find(PoolWaiter.class, VertxSqlClientQueryState.class);
  private static final ThreadLocal<Submission> submission = new ThreadLocal<>();
  private static final ThreadLocal<Acquisition> acquisition = new ThreadLocal<>();
  private static final ThreadLocal<ConnectionAttempt> connectionAttempt = new ThreadLocal<>();

  public static void attachSupplier(ConnectionPool<?> pool) {
    VertxSqlClientSupplierInfo info = VertxSqlClientSingletons.getBuildingSupplierCapture();
    if (info != null) {
      POOL_SUPPLIER.set(pool, info);
    }
  }

  @Nullable
  public static Submission enterSubmission(ConnectionPool<?> pool, Object command) {
    Submission previous = submission.get();
    Context context = VertxSqlClientSingletons.getCommandContext(command);
    VertxSqlClientQueryState query = context != null ? context.get(QUERY_STATE) : null;
    setSubmission(query != null ? new Submission(pool, query) : null);
    return previous;
  }

  public static void setSubmission(@Nullable Submission value) {
    if (value == null) {
      submission.remove();
    } else {
      submission.set(value);
    }
  }

  @Nullable
  public static Acquisition enterAcquisition(ConnectionPool<?> pool, Completable<?> handler) {
    Acquisition previous = acquisition.get();
    Submission current = submission.get();
    if (current != null && current.pool == pool && !current.claimed) {
      current.claimed = true;
      acquisition.set(new Acquisition(handler, current.query));
    } else {
      acquisition.remove();
    }
    return previous;
  }

  public static void setAcquisition(@Nullable Acquisition value) {
    if (value == null) {
      acquisition.remove();
    } else {
      acquisition.set(value);
    }
  }

  public static void attachWaiter(PoolWaiter<?> waiter, Completable<?> handler) {
    Acquisition current = acquisition.get();
    if (current != null && current.handler == handler) {
      acquisition.remove();
      WAITER_QUERY.set(waiter, current.query);
    }
  }

  @Nullable
  public static ConnectionAttempt enterConnection(ConnectionPool<?> pool, PoolWaiter<?> waiter) {
    ConnectionAttempt previous = connectionAttempt.get();
    VertxSqlClientSupplierInfo supplier = POOL_SUPPLIER.get(pool);
    if (supplier == null) {
      connectionAttempt.remove();
    } else {
      // The combiner may start a queued replacement before delivering another waiter's failure.
      VertxSqlClientQueryState query = WAITER_QUERY.get(waiter);
      WAITER_QUERY.set(waiter, null);
      connectionAttempt.set(new ConnectionAttempt(supplier.getInfo().getDbSystemName(), query));
    }
    return previous;
  }

  @Nullable
  public static ConnectionAttempt getConnectionAttempt() {
    return connectionAttempt.get();
  }

  public static void exitConnection(
      @Nullable ConnectionAttempt previous, @Nullable Throwable throwable) {
    ConnectionAttempt current = connectionAttempt.get();
    if (previous == null) {
      connectionAttempt.remove();
    } else {
      connectionAttempt.set(previous);
    }
    if (current != null) {
      current.end(throwable);
    }
  }

  public static final class Submission {
    private final ConnectionPool<?> pool;
    private final VertxSqlClientQueryState query;
    private boolean claimed;

    private Submission(ConnectionPool<?> pool, VertxSqlClientQueryState query) {
      this.pool = pool;
      this.query = query;
    }
  }

  public static final class Acquisition {
    private final Completable<?> handler;
    private final VertxSqlClientQueryState query;

    private Acquisition(Completable<?> handler, VertxSqlClientQueryState query) {
      this.handler = handler;
      this.query = query;
    }
  }

  private VertxSqlClientConnectionPoolState() {}
}
