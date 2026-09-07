/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.vertx.sqlclient.common.v4_0;

import javax.annotation.Nullable;

/** Publishes the final snapshot to clients attached before their database system is inferred. */
public final class VertxSqlClientInfoCapture implements VertxSqlClientInfoProvider {

  @Nullable private volatile VertxSqlClientInfo info;

  public VertxSqlClientInfoCapture(@Nullable VertxSqlClientInfo info) {
    this.info = info;
  }

  public void setInfo(@Nullable VertxSqlClientInfo info) {
    this.info = info;
  }

  @Override
  @Nullable
  public VertxSqlClientInfo getInfo() {
    return info;
  }
}
