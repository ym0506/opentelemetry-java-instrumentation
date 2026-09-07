/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.vertx.sqlclient.v5_0;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.implementsInterface;
import static io.opentelemetry.javaagent.instrumentation.vertx.sqlclient.common.v4_0.VertxSqlClientUtil.getClientInfoProvider;
import static io.opentelemetry.javaagent.instrumentation.vertx.sqlclient.common.v4_0.VertxSqlClientUtil.getDbSystemNameFromClassName;
import static io.opentelemetry.javaagent.instrumentation.vertx.sqlclient.common.v4_0.VertxSqlClientUtil.getPoolClientInfoProvider;
import static io.opentelemetry.javaagent.instrumentation.vertx.sqlclient.common.v4_0.VertxSqlClientUtil.isKnownDbSystem;
import static io.opentelemetry.javaagent.instrumentation.vertx.sqlclient.common.v4_0.VertxSqlClientUtil.resolveDbSystemName;
import static io.opentelemetry.javaagent.instrumentation.vertx.sqlclient.common.v4_0.VertxSqlClientUtil.setClientInfoProvider;
import static io.opentelemetry.javaagent.instrumentation.vertx.sqlclient.common.v4_0.VertxSqlClientUtil.setPoolClientInfoProvider;
import static io.opentelemetry.javaagent.instrumentation.vertx.sqlclient.common.v4_0.VertxSqlClientUtil.wrapContext;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import io.opentelemetry.javaagent.bootstrap.CallDepth;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import io.opentelemetry.javaagent.instrumentation.vertx.sqlclient.common.v4_0.VertxSqlClientInfo;
import io.opentelemetry.javaagent.instrumentation.vertx.sqlclient.common.v4_0.VertxSqlClientInfoCapture;
import io.opentelemetry.javaagent.instrumentation.vertx.sqlclient.common.v4_0.VertxSqlClientInfoProvider;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlConnectOptions;
import io.vertx.sqlclient.SqlConnection;
import javax.annotation.Nullable;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.Advice.AssignReturned;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

class PoolInstrumentation implements TypeInstrumentation {

  @Override
  public ElementMatcher<ClassLoader> classLoaderOptimization() {
    return hasClassesNamed("io.vertx.sqlclient.Pool");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(named("io.vertx.sqlclient.Pool"));
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
        named("pool")
            .and(isStatic())
            .and(takesArguments(3))
            .and(takesArgument(1, named("io.vertx.sqlclient.SqlConnectOptions")))
            .and(returns(named("io.vertx.sqlclient.Pool"))),
        getClass().getName() + "$PoolAdvice");

    transformer.applyAdviceToMethod(
        named("getConnection").and(takesNoArguments()).and(returns(named("io.vertx.core.Future"))),
        getClass().getName() + "$GetConnectionAdvice");
  }

  @SuppressWarnings("unused")
  public static class PoolAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class, inline = false)
    public static PoolState onEnter(
        @Advice.Argument(1) SqlConnectOptions sqlConnectOptions,
        @Advice.Origin("#t") String declaringTypeName) {
      CallDepth callDepth = CallDepth.forClass(Pool.class);
      PoolState state =
          new PoolState(
              callDepth,
              getClientInfoProvider(),
              VertxSqlClientSingletons.getBuildingSupplierCapture());
      if (callDepth.getAndIncrement() == 0) {
        String dbSystemName = resolveDbSystemName(sqlConnectOptions, declaringTypeName);
        VertxSqlClientInfoCapture infoCapture =
            new VertxSqlClientInfoCapture(
                VertxSqlClientInfo.create(sqlConnectOptions, dbSystemName));
        setClientInfoProvider(infoCapture);
        VertxSqlClientSingletons.setBuildingSupplierCapture(null);
      }
      return state;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class, inline = false)
    public static void onExit(
        @Advice.Return @Nullable Pool pool,
        @Advice.Argument(1) SqlConnectOptions sqlConnectOptions,
        @Advice.Enter PoolState state) {
      if (state.callDepth.decrementAndGet() > 0) {
        return;
      }

      VertxSqlClientInfoProvider infoProvider = getClientInfoProvider();
      if (pool != null && infoProvider instanceof VertxSqlClientInfoCapture) {
        VertxSqlClientInfoCapture infoCapture = (VertxSqlClientInfoCapture) infoProvider;
        VertxSqlClientInfo info = infoCapture.getInfo();
        String dbSystemName = info != null ? info.getDbSystemName() : null;
        if (dbSystemName == null || !isKnownDbSystem(dbSystemName)) {
          dbSystemName = getDbSystemNameFromClassName(pool);
        }
        infoCapture.setInfo(VertxSqlClientInfo.create(sqlConnectOptions, dbSystemName));
      }
      if (pool != null) {
        setPoolClientInfoProvider(pool, infoProvider);
      }
      setClientInfoProvider(state.previousProvider);
      VertxSqlClientSingletons.setBuildingSupplierCapture(state.previousSupplier);
    }

    public static final class PoolState {
      public final CallDepth callDepth;
      @Nullable public final VertxSqlClientInfoProvider previousProvider;
      @Nullable public final VertxSqlClientSupplierInfo previousSupplier;

      public PoolState(
          CallDepth callDepth,
          @Nullable VertxSqlClientInfoProvider previousProvider,
          @Nullable VertxSqlClientSupplierInfo previousSupplier) {
        this.callDepth = callDepth;
        this.previousProvider = previousProvider;
        this.previousSupplier = previousSupplier;
      }
    }
  }

  @SuppressWarnings("unused")
  public static class GetConnectionAdvice {
    @AssignReturned.ToReturned
    @Advice.OnMethodExit(suppress = Throwable.class, inline = false)
    public static Future<SqlConnection> onExit(
        @Advice.This Pool pool, @Advice.Return Future<SqlConnection> future) {
      VertxSqlClientInfoProvider infoProvider = getPoolClientInfoProvider(pool);
      return wrapContext(VertxSqlClientSingletons.attachClientInfoProvider(future, infoProvider));
    }
  }
}
