package chikachi.discord.core;

import chikachi.discord.repack.com.neovisionaries.ws.client.WebSocket;
import chikachi.discord.repack.com.neovisionaries.ws.client.WebSocketAdapter;
import chikachi.discord.repack.com.neovisionaries.ws.client.WebSocketException;
import chikachi.discord.repack.com.neovisionaries.ws.client.WebSocketFactory;
import chikachi.discord.repack.net.dv8tion.jda.core.AccountType;
import chikachi.discord.repack.net.dv8tion.jda.core.JDABuilder;
import okhttp3.Request;
import okhttp3.Response;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public final class DiscordTransportTest {
    public static void main(String[] args) throws Exception {
        DiscordTransport.configure(new JDABuilder(AccountType.BOT));
        stalledTls(false, false, false);
        stalledTls(true, false, false);
        stalledTls(true, true, false);
        stalledTls(true, true, true);
        upgradedWebSocket();
        restThroughProxy();
        System.out.println("Verified legacy JDA wiring, TCP/TLS/CONNECT timeout regression, upgrade and REST proxy");
    }

    private static void stalledTls(boolean patched, boolean proxy, boolean tunnel) throws Exception {
        try (ServerSocket server = listener()) {
            FutureTask<Void> fixture = serve(server, socket -> {
                if (proxy) {
                    BufferedReader request = read(socket);
                    check(request.readLine().startsWith("CONNECT gateway.invalid:443 "), "missing HTTP CONNECT");
                    headers(request);
                    if (tunnel) {
                        write(socket, "HTTP/1.1 200 Connection established\r\n\r\n");
                    }
                }
                Thread.sleep(2400);
            });
            WebSocketFactory factory = patched
                ? DiscordTransport.webSocket(proxy ? proxyAddress(server) : null, 300)
                : new WebSocketFactory().setConnectionTimeout(800);
            WebSocket webSocket = factory.createSocket(proxy ? "wss://gateway.invalid/" : url("wss", server));
            long start = System.nanoTime();
            try {
                webSocket.connect();
                throw new AssertionError("Silent peer unexpectedly connected");
            } catch (WebSocketException expected) {
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                check(patched ? elapsed >= 100 && elapsed < 1800 : elapsed >= 1800,
                    "Unexpected read timeout behavior, elapsed " + elapsed + " ms, patched=" + patched);
            } finally {
                webSocket.getSocket().close();
            }
            fixture.get(5, TimeUnit.SECONDS);
        }
    }

    private static void upgradedWebSocket() throws Exception {
        CountDownLatch checked = new CountDownLatch(1);
        try (ServerSocket server = listener()) {
            FutureTask<Void> fixture = serve(server, socket -> {
                BufferedReader request = read(socket);
                check(request.readLine().startsWith("GET / "), "missing WebSocket upgrade request");
                String key = headers(request);
                byte[] hash = MessageDigest.getInstance("SHA-1").digest(
                    (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII));
                write(socket, "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + Base64.getEncoder().encodeToString(hash) + "\r\n\r\n");
                check(checked.await(4, TimeUnit.SECONDS), "WebSocket upgrade callback timed out");
            });
            WebSocket webSocket = DiscordTransport.webSocket(null, 1000).createSocket(url("ws", server));
            CountDownLatch connected = new CountDownLatch(1);
            webSocket.addListener(new WebSocketAdapter() {
                @Override
                public void onConnected(WebSocket socket, Map<String, List<String>> headers) {
                    connected.countDown();
                }
            });
            try {
                webSocket.connect();
                check(connected.await(3, TimeUnit.SECONDS), "WebSocket did not connect");
                check(webSocket.getSocket().getSoTimeout() == 0, "upgrade timeout still set on connected socket");
            } finally {
                checked.countDown();
                webSocket.disconnect();
            }
            fixture.get(5, TimeUnit.SECONDS);
        }
    }

    private static void restThroughProxy() throws Exception {
        try (ServerSocket server = listener()) {
            FutureTask<Void> fixture = serve(server, socket -> {
                BufferedReader request = read(socket);
                check("GET http://rest.invalid/test HTTP/1.1".equals(request.readLine()), "REST bypassed the proxy");
                headers(request);
                write(socket, "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
            });
            Request request = new Request.Builder().url("http://rest.invalid/test").build();
            try (Response response = DiscordTransport.http(proxyAddress(server)).build().newCall(request).execute()) {
                check(response.isSuccessful(), "REST request through proxy failed");
            }
            fixture.get(5, TimeUnit.SECONDS);
        }
    }

    private static ServerSocket listener() throws Exception {
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(4500);
        return server;
    }

    private static URI proxyAddress(ServerSocket server) {
        return DiscordTransport.proxyAddress(url("http", server));
    }

    private static String url(String scheme, ServerSocket server) {
        return scheme + "://127.0.0.1:" + server.getLocalPort();
    }

    private static BufferedReader read(Socket socket) throws Exception {
        return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
    }

    private static String headers(BufferedReader reader) throws Exception {
        String key = null;
        for (String line; (line = reader.readLine()) != null && !line.isEmpty();) {
            if (line.regionMatches(true, 0, "Sec-WebSocket-Key:", 0, 18)) {
                key = line.substring(18).trim();
            }
        }
        return key;
    }

    private static void write(Socket socket, String text) throws Exception {
        socket.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private static FutureTask<Void> serve(ServerSocket server, Peer peer) {
        FutureTask<Void> task = new FutureTask<>(() -> {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(4000);
                peer.handle(socket);
            }
            return null;
        });
        Thread thread = new Thread(task, "discord-test-peer");
        thread.setDaemon(true);
        thread.start();
        return task;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private interface Peer {
        void handle(Socket socket) throws Exception;
    }
}
