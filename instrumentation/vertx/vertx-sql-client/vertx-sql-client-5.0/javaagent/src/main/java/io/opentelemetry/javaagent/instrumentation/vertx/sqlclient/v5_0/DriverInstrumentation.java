/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.vertx.sqlclient.v5_0;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.implementsInterface;
import static io.opentelemetry.javaagent.instrumentation.vertx.sqlclient.common.v4_0.VertxSqlClientUtil.getClientInfoProvider;
import static io.opentelemetry.javaagent.instrumentation.vertx.sqlclient.common.v4_0.VertxSqlClientUtil.getDbSystemNameFromClassName;
import static io.opentelemetry.javaagent.instrumentation.vertx.sqlclient.common.v4_0.VertxSqlClientUtil.isKnownDbSystem;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import io.opentelemetry.javaagent.instrumentation.vertx.sqlclient.common.v4_0.VertxSqlClientInfo;
import io.opentelemetry.javaagent.instrumentation.vertx.sqlclient.common.v4_0.VertxSqlClientInfoCapture;
import io.opentelemetry.javaagent.instrumentation.vertx.sqlclient.common.v4_0.VertxSqlClientInfoProvider;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

class DriverInstrumentation implements TypeInstrumentation {

  @Override
  public ElementMatcher<ClassLoader> classLoaderOptimization() {
    return hasClassesNamed("io.vertx.sqlclient.spi.Driver");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(named("io.vertx.sqlclient.spi.Driver"));
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
        named("newPool")
            .and(not(isStatic()))
            .and(takesArguments(6))
            .and(takesArgument(1, named("java.util.function.Supplier")))
            .and(returns(named("io.vertx.sqlclient.Pool"))),
        getClass().getName() + "$NewPoolAdvice");
  }

  @SuppressWarnings("unused")
  public static class NewPoolAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class, inline = false)
    public static void onEnter(@Advice.This Object driver) {
      String dbSystem = getDbSystemNameFromClassName(driver);
      VertxSqlClientSupplierInfo supplierCapture =
          VertxSqlClientSingletons.getBuildingSupplierCapture();
      if (supplierCapture != null) {
        supplierCapture.setDbSystemName(dbSystem);
      } else {
        VertxSqlClientInfoProvider provider = getClientInfoProvider();
        if (provider instanceof VertxSqlClientInfoCapture) {
          VertxSqlClientInfo info = provider.getInfo();
          if (info == null || !isKnownDbSystem(info.getDbSystemName())) {
            // Pool.pool republishes its complete snapshot after the driver has built the pool.
            ((VertxSqlClientInfoCapture) provider)
                .setInfo(VertxSqlClientInfo.createUnknown(dbSystem));
          }
        }
      }
    }
  }
}
