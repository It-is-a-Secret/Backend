package com.blursome.blursome.global.secretmoa.db;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 물리 커넥션이 끝날 때 세대 카운터를 정확히 한 번 줄이는 프록시.
 *
 * <p>Hikari는 커넥션을 {@code close()}로 닫지만, 커넥션이 깨졌거나 풀을 강제로 정리할 때는 {@code abort()}를
 * 부른다. 둘 다 물리 커넥션의 끝이므로 어느 쪽이 먼저 불리든 감소는 한 번만 한다. 위임한 호출이 실패해도
 * 감소는 한다. 실패한 커넥션을 계속 열린 것으로 세면 이전 계정을 영원히 회수하지 못한다.
 */
final class CloseTracking implements InvocationHandler {

  private final Connection target;
  private final Runnable onRelease;
  private final AtomicBoolean released = new AtomicBoolean();

  private CloseTracking(Connection target, Runnable onRelease) {
    this.target = target;
    this.onRelease = onRelease;
  }

  static Connection wrap(Connection target, Runnable onRelease) {
    return (Connection) Proxy.newProxyInstance(CloseTracking.class.getClassLoader(),
        new Class<?>[] {Connection.class}, new CloseTracking(target, onRelease));
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    switch (method.getName()) {
      case "equals":
        return proxy == args[0];
      case "hashCode":
        return System.identityHashCode(proxy);
      case "toString":
        return "CloseTracking[" + target + "]";
      default:
        break;
    }
    boolean ending = "close".equals(method.getName()) || "abort".equals(method.getName());
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    } finally {
      if (ending && released.compareAndSet(false, true)) {
        onRelease.run();
      }
    }
  }
}
