package com.mobile.standalone.config;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;

/**
 * 发信连接一律直连，不走 JVM 全局 SOCKS 代理。
 * <p>
 * macOS 上的 JDK 启动时会把系统代理（Clash 等）读成 socksProxyHost，JavaMail 默认的 {@code new Socket()}
 * 就会经代理去连内网 Exchange；代理解析不了 mail.mx.wanmei.net，握手直接 EOF（Got bad greeting ... [EOF]）。
 * socksNonProxyHosts 只认主机名通配，系统设置里写的 10.0.0.0/8 对 Java 不生效，所以只能在这里显式直连。
 * <p>
 * 必须是普通 SocketFactory：JavaMail 只对非 SSL 工厂调用 {@code createSocket()}，需要加密时再按 ssl.trust 套 TLS。
 */
public class DirectMailSocketFactory extends SocketFactory {

    @Override
    public Socket createSocket() {
        return new Socket(Proxy.NO_PROXY);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        Socket s = createSocket();
        s.connect(new InetSocketAddress(host, port));
        return s;
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        Socket s = createSocket();
        s.bind(new InetSocketAddress(localHost, localPort));
        s.connect(new InetSocketAddress(host, port));
        return s;
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        Socket s = createSocket();
        s.connect(new InetSocketAddress(host, port));
        return s;
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        Socket s = createSocket();
        s.bind(new InetSocketAddress(localAddress, localPort));
        s.connect(new InetSocketAddress(address, port));
        return s;
    }
}
