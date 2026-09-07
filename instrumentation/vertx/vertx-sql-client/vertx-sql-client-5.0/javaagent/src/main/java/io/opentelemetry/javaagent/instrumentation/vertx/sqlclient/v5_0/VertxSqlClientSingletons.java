/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.vertx.sqlclient.v5_0;

import static io.opentelemetry.javaagent.instrumentation.vertx.sqlclient.v5_0.VertxSqlClientQueryState.QUERY_STATE;
import static java.util.logging.Level.FINE;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.opentelemetry.instrumentation.api.util.VirtualField;
import io.opentelemetry.javaagent.instrumentation.vertx.sqlclient.common.v4_0.VertxSqlClientInfo;
import io.opentelemetry.javaagent.instrumentation.vertx.sqlclient.common.v4_0.VertxSqlClientInfoProvider;
import io.opentelemetry.javaagent.instrumentation.vertx.sqlclient.common.v4_0.VertxSqlClientRequest;
import io.opentelemetry.javaagent.instrumentation.vertx.sqlclient.common.v4_0.VertxSqlInstrumenterFactory;
import io.opentelemetry.javaagent.tooling.muzzle.NoMuzzle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.sqlclient.SqlConnectOptions;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.impl.ClientBuilderBase;
import io.vertx.sqlclient.internal.SqlClientBase;
import java.util.List;
import java.util.logging.Logger;
import javax.annotation.Nullable;

public class VertxSqlClientSingletons {
  private static final String INSTRUMENTATION_NAME = "io.opentelemetry.vertx-sql-client-5.0";
  private static final Instrumenter<VertxSqlClientRequest, Void> instrumenter =
      VertxSqlInstrumenterFactory.createInstrumenter(INSTRUMENTATION_NAME);

  private static final VirtualField<SqlClientBase, VertxSqlClientInfoProvider>
      CLIENT_INFO_PROVIDER =
          VirtualField.find(SqlClientBase.class, VertxSqlClientInfoProvider.class);
  private static final VirtualField<ClientBuilderBase<?>, List<SqlConnectOptions>>
      BUILDER_DATABASES = VirtualField.find(ClientBuilderBase.class, List.class);

  private static final Logger logger = Logger.getLogger(VertxSqlClientSingletons.class.getName());
  private static final ThreadLocal<VertxSqlClientSupplierInfo> buildingSupplierCapture =
      new ThreadLocal<>();

  @Nullable
  private static final VirtualField<Object, Context> COMMAND_CONTEXT =
      getVersionedVirtualField(
          "io.vertx.sqlclient.internal.command.CommandBase",
          "io.vertx.sqlclient.spi.protocol.CommandBase",
          Context.class);

  @Nullable
  private static final VirtualField<Object, VertxSqlClientInfo> CONNECTION_INFO =
      getVersionedVirtualField(
          "io.vertx.sqlclient.internal.Connection",
          "io.vertx.sqlclient.spi.connection.Connection",
          VertxSqlClientInfo.class);

  public static Instrumenter<VertxSqlClientRequest, Void> instrumenter() {
    return instrumenter;
  }

  @Nullable
  @NoMuzzle
  @SuppressWarnings("unchecked") // virtual field key type is not known at compile time
  private static <T> VirtualField<Object, T> getVersionedVirtualField(
      String firstClassName, String secondClassName, Class<T> fieldClass) {
    Class<?> carrierClass = null;
    try {
      carrierClass = Class.forName(firstClassName);
    } catch (ClassNotFoundException ignored) {
      // ignored
    }
    if (carrierClass == null) {
      try {
        carrierClass = Class.forName(secondClassName);
      } catch (ClassNotFoundException ignored) {
        // ignored
      }
    }
    return carrierClass != null
        ? (VirtualField<Object, T>) VirtualField.find(carrierClass, fieldClass)
        : null;
  }

  @Nullable
  public static Context getCommandContext(Object command) {
    return COMMAND_CONTEXT != null ? COMMAND_CONTEXT.get(command) : null;
  }

  public static void setCommandContext(Object command, Context context) {
    if (COMMAND_CONTEXT != null) {
      COMMAND_CONTEXT.set(command, context);
    }
  }

  @Nullable
  public static Context captureConnectionInfo(Object command, Object connection) {
    Context context = getCommandContext(command);
    if (context == null) {
      return null;
    }
    VertxSqlClientQueryState query = context.get(QUERY_STATE);
    if (query == null) {
      return null;
    }
    VertxSqlClientInfo info = getConnectionInfo(connection);
    if (info == null) {
      return null;
    }
    query.capture(info);
    Context executionContext = context.with(QUERY_STATE, null);
    setCommandContext(command, executionContext);
    return executionContext;
  }

  @Nullable
  public static VertxSqlClientInfoProvider getClientInfoProvider(SqlClientBase sqlClientBase) {
    return CLIENT_INFO_PROVIDER.get(sqlClientBase);
  }

  public static void attachClientInfoProvider(
      SqlClientBase sqlClientBase, @Nullable VertxSqlClientInfoProvider infoProvider) {
    CLIENT_INFO_PROVIDER.set(sqlClientBase, infoProvider);
  }

  public static Future<SqlConnection> attachClientInfoProvider(
      Future<SqlConnection> future, @Nullable VertxSqlClientInfoProvider infoProvider) {
    return future.transform(
        result -> {
          if (result.succeeded() && result.result() instanceof SqlClientBase) {
            SqlClientBase sqlClientBase = (SqlClientBase) result.result();
            VertxSqlClientInfo connectionInfo = getConnectionInfo(sqlClientBase);
            attachClientInfoProvider(
                sqlClientBase, connectionInfo != null ? connectionInfo : infoProvider);
          }
          return copyResult(result);
        });
  }

  @Nullable
  public static Handler<SqlConnection> wrapConnectHandler(
      @Nullable Handler<SqlConnection> handler, VertxSqlClientInfo info) {
    if (handler == null) {
      return null;
    }
    return connection -> {
      if (connection instanceof SqlClientBase) {
        attachClientInfoProvider((SqlClientBase) connection, info);
      }
      handler.handle(connection);
    };
  }

  public static void setBuildingSupplierCapture(
      @Nullable VertxSqlClientSupplierInfo supplierCapture) {
    if (supplierCapture == null) {
      buildingSupplierCapture.remove();
    } else {
      buildingSupplierCapture.set(supplierCapture);
    }
  }

  @Nullable
  public static VertxSqlClientSupplierInfo getBuildingSupplierCapture() {
    return buildingSupplierCapture.get();
  }

  public static Future<SqlConnectOptions> captureConnectionAttempt(
      Future<SqlConnectOptions> connectOptionsFuture, ConnectionAttempt connectionAttempt) {
    return connectOptionsFuture.map(
        connectOptions -> {
          try {
            connectionAttempt.capture(connectOptions);
          } catch (Throwable t) {
            logger.log(FINE, "Failed to capture Vert.x SQL connection options", t);
          }
          return connectOptions;
        });
  }

  public static <T> Future<T> attachConnectionData(
      Future<T> future, @Nullable ConnectionAttempt connectionAttempt) {
    if (connectionAttempt == null) {
      return future;
    }
    return future.transform(
        result -> {
          try {
            VertxSqlClientInfo info = connectionAttempt.info;
            if (info != null) {
              if (result.succeeded()) {
                cacheConnectionInfo(result.result(), info);
              } else {
                connectionAttempt.captureFailureInfo(info);
              }
            }
          } catch (Throwable t) {
            logger.log(FINE, "Failed to attach Vert.x SQL connection metadata", t);
          }
          return copyResult(result);
        });
  }

  private static <T> Future<T> copyResult(AsyncResult<T> result) {
    return result.succeeded()
        ? Future.succeededFuture(result.result())
        : Future.failedFuture(result.cause());
  }

  private static void cacheConnectionInfo(Object connection, VertxSqlClientInfo info) {
    if (CONNECTION_INFO == null) {
      return;
    }
    Object candidate = connection;
    while (candidate != null) {
      CONNECTION_INFO.set(candidate, info);
      candidate = unwrap(candidate);
    }
  }

  @Nullable
  public static VertxSqlClientInfo getConnectionInfo(Object connection) {
    if (CONNECTION_INFO == null) {
      return null;
    }
    Object candidate = connection;
    while (candidate != null) {
      VertxSqlClientInfo info = CONNECTION_INFO.get(candidate);
      if (info != null) {
        if (candidate != connection) {
          CONNECTION_INFO.set(connection, info);
        }
        return info;
      }
      candidate = unwrap(candidate);
    }
    return null;
  }

  @Nullable
  private static Object unwrap(Object candidate) {
    try {
      Object unwrapped = candidate.getClass().getMethod("unwrap").invoke(candidate);
      return unwrapped != candidate ? unwrapped : null;
    } catch (ReflectiveOperationException ignored) {
      return null;
    }
  }

  public static void storeBuilderDatabases(
      Object clientBuilder, @Nullable List<SqlConnectOptions> databases) {
    if (clientBuilder instanceof ClientBuilderBase) {
      BUILDER_DATABASES.set((ClientBuilderBase<?>) clientBuilder, databases);
    }
  }

  @Nullable
  public static List<SqlConnectOptions> getBuilderDatabases(Object clientBuilder) {
    return clientBuilder instanceof ClientBuilderBase
        ? BUILDER_DATABASES.get((ClientBuilderBase<?>) clientBuilder)
        : null;
  }

  public static class ConnectionAttempt {
    private final String dbSystemName;
    @Nullable private final VertxSqlClientQueryState query;
    @Nullable private volatile VertxSqlClientInfo info;
    @Nullable private Scope scope;

    ConnectionAttempt(String dbSystemName, @Nullable VertxSqlClientQueryState query) {
      this.dbSystemName = dbSystemName;
      this.query = query;
      this.scope = query != null ? query.getContext().makeCurrent() : null;
    }

    private void capture(SqlConnectOptions connectOptions) {
      info = VertxSqlClientInfo.create(connectOptions, dbSystemName);
    }

    private void captureFailureInfo(VertxSqlClientInfo info) {
      if (query != null) {
        query.capture(info);
      }
    }

    void end(@Nullable Throwable throwable) {
      if (scope != null) {
        scope.close();
        scope = null;
      }
      if (throwable != null && query != null) {
        query.end(throwable);
      }
    }
  }

  private VertxSqlClientSingletons() {}
}
