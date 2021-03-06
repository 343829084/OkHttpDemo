/*
 * Copyright (C) 2012 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.connection;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import okhttp3.Address;
import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.HttpUrl;
import okhttp3.Route;
import okhttp3.internal.Util;

/**
 * Selects routes to connect to an origin server. Each connection requires a choice of proxy server,
 * IP address, and TLS mode. Connections may also be recycled.
 * 路由选择器
 * 这个类主要是选择连接到服务器的路由，每个连接应该是代理服务器/IP地址/TLS模式 三者中的一种
 *
 *
 * 1收集路由信息，2选择路由，3维护失败路由。
 */
final class RouteSelector {
  private final Address address;//请求地址
  private final RouteDatabase routeDatabase;//路由黑名单
  private final Call call;//
  private final EventListener eventListener;

  /* State for negotiating the next proxy to use. */
  private List<Proxy> proxies = Collections.emptyList();//代理集合
  private int nextProxyIndex;

  /* State for negotiating the next socket address to use. */
  private List<InetSocketAddress> inetSocketAddresses = Collections.emptyList();

  /* State for negotiating failed routes
   * 连接失败的路由列表
    * */
  private final List<Route> postponedRoutes = new ArrayList<>();

  public RouteSelector(Address address, RouteDatabase routeDatabase, Call call,
      EventListener eventListener) {
    this.address = address;
    this.routeDatabase = routeDatabase;
    this.call = call;
    this.eventListener = eventListener;

    resetNextProxy(address.url(), address.proxy());
  }

  /**
   * Returns true if there's another set of routes to attempt. Every address has at least one route.
   * 表明是否有可以使用的路由
   */
  public boolean hasNext() {
    return hasNextProxy() || !postponedRoutes.isEmpty();
  }

  public Selection next() throws IOException {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }

    // Compute the next set of routes to attempt.
    List<Route> routes = new ArrayList<>();
    //如果设置了代理
    while (hasNextProxy()) {
      // Postponed routes are always tried last. For example, if we have 2 proxies and all the
      // routes for proxy1 should be postponed, we'll move to proxy2. Only after we've exhausted
      // all the good routes will we attempt the postponed routes.
      Proxy proxy = nextProxy();
      for (int i = 0, size = inetSocketAddresses.size(); i < size; i++) {
        Route route = new Route(address, proxy, inetSocketAddresses.get(i));
        if (routeDatabase.shouldPostpone(route)) {
          postponedRoutes.add(route);
        } else {
          routes.add(route);
        }
      }

      if (!routes.isEmpty()) {
        break;
      }
    }

    if (routes.isEmpty()) {
      // We've exhausted all Proxies so fallback to the postponed routes.
      routes.addAll(postponedRoutes);
      postponedRoutes.clear();
    }

    return new Selection(routes);
  }

  /** Prepares the proxy servers to try.
   * 准备代理服务器
   * 使用场景:需要让连接使用代理，但不用系统的代理配置情况，用户才自己设置代理
   * */
  private void resetNextProxy(HttpUrl url, Proxy proxy) {
    //外部有传入代理的话，代理集合就包含唯一集合（来源OkHttpClient）
    if (proxy != null) {
      // If the user specifies a proxy, try that and only that.
      proxies = Collections.singletonList(proxy);
    } else {
      //借助proxySelector来获得多个代理，系统默认收集的代理
      // Try each of the ProxySelector choices until one connection succeeds.
      List<Proxy> proxiesOrNull = address.proxySelector().select(url.uri());
      proxies = proxiesOrNull != null && !proxiesOrNull.isEmpty()
          ? Util.immutableList(proxiesOrNull)
          : Util.immutableList(Proxy.NO_PROXY);
    }
    nextProxyIndex = 0;
  }

  /** Returns true if there's another proxy to try.
   * 是否还有代理
   * */
  private boolean hasNextProxy() {
    return nextProxyIndex < proxies.size();
  }

  /** Returns the next proxy to try. May be PROXY.NO_PROXY but never null. */
  private Proxy nextProxy() throws IOException {
    if (!hasNextProxy()) {
      throw new SocketException("No route to " + address.url().host()
          + "; exhausted proxy configurations: " + proxies);
    }
    Proxy result = proxies.get(nextProxyIndex++);
    resetNextInetSocketAddress(result);
    return result;
  }

  /** Prepares the socket addresses to attempt for the current proxy or host. */
  private void resetNextInetSocketAddress(Proxy proxy) throws IOException {
    // Clear the addresses. Necessary if getAllByName() below throws!
    inetSocketAddresses = new ArrayList<>();

    String socketHost;
    int socketPort;
    if (proxy.type() == Proxy.Type.DIRECT || proxy.type() == Proxy.Type.SOCKS) {
      socketHost = address.url().host();
      socketPort = address.url().port();
    } else {
      SocketAddress proxyAddress = proxy.address();
      if (!(proxyAddress instanceof InetSocketAddress)) {
        throw new IllegalArgumentException(
            "Proxy.address() is not an " + "InetSocketAddress: " + proxyAddress.getClass());
      }
      InetSocketAddress proxySocketAddress = (InetSocketAddress) proxyAddress;
      socketHost = getHostString(proxySocketAddress);
      socketPort = proxySocketAddress.getPort();
    }

    if (socketPort < 1 || socketPort > 65535) {
      throw new SocketException("No route to " + socketHost + ":" + socketPort
          + "; port is out of range");
    }

    if (proxy.type() == Proxy.Type.SOCKS) {
      inetSocketAddresses.add(InetSocketAddress.createUnresolved(socketHost, socketPort));
    } else {
      eventListener.dnsStart(call, socketHost);

      // Try each address for best behavior in mixed IPv4/IPv6 environments.
      List<InetAddress> addresses = address.dns().lookup(socketHost);
      if (addresses.isEmpty()) {
        throw new UnknownHostException(address.dns() + " returned no addresses for " + socketHost);
      }

      eventListener.dnsEnd(call, socketHost, addresses);

      for (int i = 0, size = addresses.size(); i < size; i++) {
        InetAddress inetAddress = addresses.get(i);
        inetSocketAddresses.add(new InetSocketAddress(inetAddress, socketPort));
      }
    }
  }

  /**
   * Obtain a "host" from an {@link InetSocketAddress}. This returns a string containing either an
   * actual host name or a numeric IP address.
   */
  // Visible for testing
  static String getHostString(InetSocketAddress socketAddress) {
    InetAddress address = socketAddress.getAddress();
    if (address == null) {
      // The InetSocketAddress was specified with a string (either a numeric IP or a host name). If
      // it is a name, all IPs for that name should be tried. If it is an IP address, only that IP
      // address should be tried.
      return socketAddress.getHostName();
    }
    // The InetSocketAddress has a specific address: we should only try that address. Therefore we
    // return the address and ignore any host name that may be available.
    return address.getHostAddress();
  }

  /** A set of selected Routes.
   * 被挑选出来的路由
   * */
  public static final class Selection {
    private final List<Route> routes;
    private int nextRouteIndex = 0;

    Selection(List<Route> routes) {
      this.routes = routes;
    }

    public boolean hasNext() {
      return nextRouteIndex < routes.size();
    }

    public Route next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      return routes.get(nextRouteIndex++);
    }

    public List<Route> getAll() {
      return new ArrayList<>(routes);
    }
  }
}
