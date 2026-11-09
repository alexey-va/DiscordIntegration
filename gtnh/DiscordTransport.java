package chikachi.discord.core;

import chikachi.discord.repack.com.neovisionaries.ws.client.WebSocket;
import chikachi.discord.repack.com.neovisionaries.ws.client.WebSocketAdapter;
import chikachi.discord.repack.com.neovisionaries.ws.client.WebSocketFactory;
import chikachi.discord.repack.net.dv8tion.jda.core.JDABuilder;
import okhttp3.OkHttpClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class DiscordTransport {
    private static final int CONNECT_MILLIS = 8000;
    private static final int IO_MILLIS = 15000;

    static void configure(JDABuilder builder) {
        URI proxy = proxyAddress(System.getProperty("discordintegration.httpProxy", "").trim());
        builder.setHttpClientBuilder(http(proxy));
        builder.setWebsocketFactory(webSocket(proxy, IO_MILLIS));
    }

    static OkHttpClient.Builder http(URI proxy) {
        OkHttpClient.Builder http = new OkHttpClient.Builder()
            .connectTimeout(CONNECT_MILLIS, TimeUnit.MILLISECONDS)
            .readTimeout(IO_MILLIS, TimeUnit.MILLISECONDS)
            .writeTimeout(IO_MILLIS, TimeUnit.MILLISECONDS);
        if (proxy != null) {
            http.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxy.getHost(), proxy.getPort())));
        }
        return http;
    }

    static WebSocketFactory webSocket(URI proxy, int readMillis) {
        WebSocketFactory factory = new WebSocketFactory() {
            @Override
            public WebSocket createSocket(URI uri, int timeout) throws IOException {
                WebSocket socket = super.createSocket(uri, timeout);
                try {
                    socket.getSocket().setSoTimeout(readMillis);
                } catch (IOException e) {
                    try {
                        socket.getSocket().close();
                    } catch (IOException closeError) {
                        e.addSuppressed(closeError);
                    }
                    throw e;
                }
                return socket.addListener(new WebSocketAdapter() {
                    @Override
                    public void onConnected(WebSocket connected, Map<String, List<String>> headers) throws IOException {
                        // The old factory only bounds TCP connect. Keep the read limit through
                        // proxy CONNECT, TLS and HTTP Upgrade, then let JDA handle heartbeats.
                        connected.getSocket().setSoTimeout(0);
                    }
                });
            }
        }.setConnectionTimeout(CONNECT_MILLIS);
        if (proxy != null) {
            factory.getProxySettings().setHost(proxy.getHost()).setPort(proxy.getPort());
        }
        return factory;
    }

    static URI proxyAddress(String value) {
        if (value.isEmpty()) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            if ("http".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                && uri.getPort() > 0 && uri.getPort() <= 65535 && uri.getUserInfo() == null
                && uri.getQuery() == null && uri.getFragment() == null
                && (uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
                return uri;
            }
        } catch (IllegalArgumentException ignored) {
            // Keep invalid configuration, including accidental credentials, out of logs.
        }
        throw new IllegalArgumentException("discordintegration.httpProxy must be http://host:port");
    }
}
