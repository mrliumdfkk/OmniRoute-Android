package com.example.omniroute;

import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The loopback listener Zed's native-app sign-in expects.
 *
 * <p>Zed's {@code native_app_signin} flow is not a plain OAuth redirect. The
 * browser is sent to
 * {@code https://zed.dev/native_app_signin?native_app_port=58443&native_app_public_key=…}
 * and, after the user signs in, Zed redirects the browser to
 * {@code http://127.0.0.1:58443/?user_id=…&access_token=…}. The access token
 * in that URL is encrypted with the public key the app generated.
 *
 * <p>Neither the runtime nor the dashboard can stand in for the app here:
 * <ul>
 *   <li>The runtime refuses {@code start-callback-server} for this provider
 *       ("Callback server not supported for provider: zed-hosted") and has no
 *       {@code device-complete} route for it, because the exchange route
 *       expects the <em>plaintext</em> token and the runtime is the one that
 *       decrypts it with the private key the app generated.</li>
 *   <li>The dashboard's fallback is to have the user copy the callback URL
 *       out of the browser's address bar by hand.</li>
 * </ul>
 *
 * <p>On a phone the app can do better than that: it can own the port itself,
 * capture the redirect and hand it to the runtime, so the sign-in is a
 * single tap with no copy-and-paste.
 *
 * <p>The listener binds to the loopback interface only. Zed is a web page in
 * the system browser on the same device, so it reaches the port over
 * {@code 127.0.0.1} and nothing off-device can.
 */
final class ZedNativeAppListener {

    private static final String TAG = "ZedNativeListener";

    /** The port Zed's sign-in page redirects to when the app supplies one. */
    static final int DEFAULT_PORT = 58443;

    /**
     * How long the listener stays up waiting for the redirect.
     *
     * <p>Matches the runtime's own callback-server window.
     */
    private static final long ACCEPT_TIMEOUT_MS = 300_000L;

    /** The result of a captured Zed redirect. */
    static final class Result {
        /** The full callback URL, exactly as Zed sent it. */
        final String callbackUrl;
        /** The decrypted user id, when the URL carried one. */
        final String userId;

        Result(String callbackUrl, String userId) {
            this.callbackUrl = callbackUrl;
            this.userId = userId;
        }
    }

    /** Reports the captured redirect, or an error. */
    interface Callback {
        void onResult(Result result);

        void onError(String message);
    }

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private Thread acceptThread;
    /**
     * The port the listener is bound to.
     *
     * <p>The runtime picks it, not the app: the authorization URL carries
     * {@code native_app_port=…} and the redirect URI points at the same
     * port, so the two always agree. It is remembered here because the
     * callback URL has to be rebuilt in absolute form, and the request line
     * only carries the path.
     */
    private int boundPort = DEFAULT_PORT;

    /**
     * Starts listening on {@code port}.
     *
     * <p>Returns immediately; the redirect is delivered on the caller's
     * callback. When the port cannot be bound the callback reports the
     * reason instead, so the user can be told to use the manual paste.
     */
    void start(int port, final Callback callback) {
        boundPort = port > 0 ? port : DEFAULT_PORT;
        acceptThread = new Thread(() -> {
            ServerSocket socket = null;
            try {
                socket = new ServerSocket();
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(
                        InetAddress.getByName("127.0.0.1"), port), 64);
                serverSocket = socket;
                socket.setSoTimeout((int) ACCEPT_TIMEOUT_MS);
                while (!stopped.get()) {
                    Socket client;
                    try {
                        client = socket.accept();
                    } catch (java.net.SocketTimeoutException timeout) {
                        callback.onError("等待 Zed 回调超时，请重试或改用手动粘贴");
                        return;
                    } catch (Exception exception) {
                        if (!stopped.get()) {
                            callback.onError("监听 Zed 回调失败：" + exception.getMessage());
                        }
                        return;
                    }
                    try {
                        handle(client, callback);
                    } catch (Exception exception) {
                        Log.w(TAG, "Failed to handle Zed callback", exception);
                    } finally {
                        try {
                            client.close();
                        } catch (Exception ignored) {
                            // Nothing useful to do with a failed close.
                        }
                    }
                    return;
                }
            } catch (Exception exception) {
                Log.w(TAG, "Cannot bind the Zed callback port", exception);
                callback.onError("无法监听端口 " + port + "：" + exception.getMessage());
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (Exception ignored) {
                        // Nothing useful to do with a failed close.
                    }
                }
            }
        }, "zed-native-app-listener");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /** Stops the listener and releases the port. */
    void stop() {
        stopped.set(true);
        ServerSocket socket = serverSocket;
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
                // Nothing useful to do with a failed close.
            }
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
    }

    /**
     * Reads the request, answers it and extracts the callback URL.
     */
    private void handle(Socket client, Callback callback) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                client.getInputStream(), StandardCharsets.UTF_8));
        String requestLine = reader.readLine();
        if (requestLine == null) {
            return;
        }
        // Drain the headers so the browser does not see a reset.
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            // Nothing in the headers matters for the callback.
        }

        String target = requestLine;
        String[] parts = requestLine.split(" ");
        if (parts.length >= 2) {
            target = parts[1];
        }
        String callbackUrl = toAbsoluteUrl(target, boundPort);

        OutputStream out = client.getOutputStream();
        String body = "Sign-in received. You can close this tab and return to OmniRoute.";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 200 OK\r\n")
                .append("Content-Type: text/plain; charset=utf-8\r\n")
                .append("Content-Length: ").append(bodyBytes.length).append("\r\n")
                .append("Connection: close\r\n")
                .append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.UTF_8));
        out.write(bodyBytes);
        out.flush();

        callback.onResult(new Result(callbackUrl, extractUserId(callbackUrl)));
    }

    /**
     * Turns a request target into the absolute URL Zed would have put in the
     * address bar.
     *
     * <p>The browser sends an origin-form target such as
     * {@code /?user_id=…&access_token=…}; the runtime's Zed parser wants the
     * full URL, so the loopback origin is put back.
     */
    private static String toAbsoluteUrl(String target, int port) {
        if (target == null || target.isEmpty()) {
            return "";
        }
        if (target.startsWith("http://") || target.startsWith("https://")) {
            return target;
        }
        String origin = "http://127.0.0.1:" + port;
        if (!target.startsWith("/")) {
            return origin + "/" + target;
        }
        return origin + target;
    }

    /**
     * Reads the {@code user_id} parameter out of a callback URL.
     *
     * <p>Only used for the UI; the runtime reads it again from the URL the app
     * posts.
     */
    private static String extractUserId(String callbackUrl) {
        if (callbackUrl == null) {
            return null;
        }
        int index = callbackUrl.indexOf("user_id=");
        if (index < 0) {
            return null;
        }
        String tail = callbackUrl.substring(index + "user_id=".length());
        int amp = tail.indexOf('&');
        if (amp >= 0) {
            tail = tail.substring(0, amp);
        }
        try {
            return URLDecoder.decode(tail, "UTF-8");
        } catch (Exception exception) {
            return tail;
        }
    }
}
