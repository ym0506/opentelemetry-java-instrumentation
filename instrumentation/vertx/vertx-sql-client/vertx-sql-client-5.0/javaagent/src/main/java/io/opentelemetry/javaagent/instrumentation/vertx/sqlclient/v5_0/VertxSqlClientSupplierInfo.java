/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.vertx.sqlclient.v5_0;

import io.opentelemetry.javaagent.instrumentation.vertx.sqlclient.common.v4_0.VertxSqlClientInfo;
import io.opentelemetry.javaagent.instrumentation.vertx.sqlclient.common.v4_0.VertxSqlClientInfoProvider;

public final class VertxSqlClientSupplierInfo implements VertxSqlClientInfoProvider {
  private volatile VertxSqlClientInfo info = VertxSqlClientInfo.createUnknown(null);

  public void setDbSystemName(String dbSystemName) {
    info = VertxSqlClientInfo.createUnknown(dbSystemName);
  }

  @Override
  public VertxSqlClientInfo getInfo() {
    return info;
  }
}
