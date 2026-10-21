package com.example.omniroute;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal client for the OCI registry API.
 *
 * <p>Only what the runtime installer needs is implemented: resolve a tag to
 * an image manifest, pick the arm64 variant, and stream the layer blobs. The
 * registry may require a bearer token even for public images, so the token
 * endpoint is queried first when the registry answers 401.
 */
final class OciImageClient {

    private static final String TAG = "OciImageClient";
    private static final int TIMEOUT_MS = 30_000;

    /**
     * Timeout for a single range request.
     *
     * <p>The storage backend can be slow, and a range that takes a minute
     * to arrive is answered with a closed connection when the read timeout
     * is short. Java reports such a premature close as a clean end of
     * stream, so the truncation is only visible as a short chunk.
     */
    private static final int RANGE_TIMEOUT_MS = 120_000;

    /**
     * Size of a single range request.
     *
     * <p>Long transfers over the storage backend can be cut off mid-stream
     * without an error, so the blobs are fetched in modest chunks; a chunk
     * that arrives short is detected and retried instead of corrupting the
     * file.
     */
    private static final long CHUNK_BYTES = 4L << 20;

    /** How often a range request is repeated before the download is aborted. */
    private static final int CHUNK_ATTEMPTS = 4;

    /** One layer of an image. */
    static final class Layer {
        final String digest;
        final long size;

        Layer(String digest, long size) {
            this.digest = digest;
            this.size = size;
        }
    }

    /** A resolved image manifest. */
    static final class Manifest {
        final String configDigest;
        final List<Layer> layers;

        Manifest(String configDigest, List<Layer> layers) {
            this.configDigest = configDigest;
            this.layers = layers;
        }

        long totalCompressedSize() {
            long total = 0L;
            for (Layer layer : layers) {
                total += layer.size;
            }
            return total;
        }
    }

    /** Callback for blob download progress. */
    interface BlobProgress {
        void onProgress(long downloaded, long total);
    }

    private final String registry;
    private final String repository;
    private final Map<String, String> tokens = new LinkedHashMap<>();
    private String cachedToken;

    OciImageClient(String registry, String repository) {
        this.registry = registry;
        this.repository = repository;
    }

    /**
     * Resolves a tag to the arm64 manifest and returns it with its layers.
     */
    Manifest resolveArm64(String tag) throws IOException {
        // The tag may point at an index (a multi-architecture manifest list).
        // The list carries a platform field per entry, so pick the linux/arm64
        // one and then resolve that digest as a plain manifest.
        String reference = "manifests/" + tag;
        Response index = request("GET", reference, "application/vnd.oci.image.index.v1+json",
                "application/vnd.docker.distribution.manifest.list.v2+json",
                "application/vnd.oci.image.manifest.v1+json");

        String contentType = index.contentType == null ? "" : index.contentType;
        if (contentType.contains("manifest.list") || contentType.contains("image.index")) {
            String digest = findArm64Digest(index.body);
            if (digest == null) {
                throw new IOException("官方镜像没有 arm64 变体");
            }
            index = request("GET", "manifests/" + digest, "application/vnd.oci.image.manifest.v1+json",
                    "application/vnd.docker.distribution.manifest.v2+json");
        }

        Manifest manifest = parseManifest(index.body);
        if (manifest.layers.isEmpty()) {
            throw new IOException("官方镜像没有可用层");
        }
        return manifest;
    }

    /**
     * Finds the digest of the linux/arm64 entry inside a manifest list.
     */
    private String findArm64Digest(String body) throws IOException {
        try {
            org.json.JSONObject root = new org.json.JSONObject(body);
            org.json.JSONArray manifests = root.optJSONArray("manifests");
            if (manifests == null) {
                return null;
            }
            for (int i = 0; i < manifests.length(); i++) {
                org.json.JSONObject entry = manifests.optJSONObject(i);
                if (entry == null) {
                    continue;
                }
                org.json.JSONObject platform = entry.optJSONObject("platform");
                if (platform == null) {
                    continue;
                }
                if (!"arm64".equals(platform.optString("architecture"))) {
                    continue;
                }
                String os = platform.optString("os");
                if (!os.isEmpty() && !"linux".equals(os)) {
                    // Windows on arm64 exists upstream but is not usable here.
                    continue;
                }
                String digest = entry.optString("digest", "");
                if (!digest.isEmpty()) {
                    return digest;
                }
            }
            return null;
        } catch (org.json.JSONException malformed) {
            throw new IOException("官方源返回的镜像索引无法解析", malformed);
        }
    }

    /** Parses the layer list and the config digest of a manifest. */
    private Manifest parseManifest(String body) throws IOException {
        try {
            org.json.JSONObject root = new org.json.JSONObject(body);
            org.json.JSONObject config = root.optJSONObject("config");
            String configDigest = config == null ? "" : config.optString("digest", "");
            if (configDigest.isEmpty()) {
                throw new IOException("官方镜像清单缺少 config");
            }
            org.json.JSONArray layers = root.optJSONArray("layers");
            if (layers == null) {
                throw new IOException("官方镜像清单缺少 layers");
            }
            List<Layer> parsed = new ArrayList<>();
            for (int i = 0; i < layers.length(); i++) {
                org.json.JSONObject entry = layers.optJSONObject(i);
                if (entry == null) {
                    continue;
                }
                String digest = entry.optString("digest", "");
                if (digest.isEmpty()) {
                    continue;
                }
                parsed.add(new Layer(digest, entry.optLong("size", 0L)));
            }
            return new Manifest(configDigest, parsed);
        } catch (org.json.JSONException malformed) {
            throw new IOException("官方源返回的镜像清单无法解析", malformed);
        }
    }

    /**
     * Downloads a layer blob to {@code target}, resuming when possible.
     */
    /**
     * Downloads one blob into {@code target}.
     *
     * <p>The registry answers a blob request with a 307 redirect to a signed
     * CDN URL, and a long transfer over that URL can be cut off mid-stream
     * without an error. The download therefore runs as a sequence of fixed
     * size range requests against the resolved URL, and every chunk is
     * checked against the expected length, so a truncated transfer is
     * detected instead of silently producing a corrupt file.
     *
     * <p>A partially written file is kept on disk, so an interrupted download
     * resumes where it stopped.
     */
    void downloadBlob(String digest, long expectedSize, File target,
            BlobProgress progress) throws IOException {
        File partial = new File(target.getAbsolutePath() + ".part");
        String url = resolveBlob(digest);
        long total = expectedSize;

        if (partial.isFile() && partial.length() > total) {
            // The partial file cannot belong to this blob; start over.
            partial.delete();
        }
        long existing = partial.isFile() ? partial.length() : 0L;
        if (existing == total && total > 0) {
            return;
        }

        try (FileOutputStream output = new FileOutputStream(partial, existing > 0)) {
            long position = existing;
            while (position < total) {
                long end = Math.min(position + CHUNK_BYTES - 1, total - 1);
                int expected = (int) (end - position + 1);
                byte[] chunk = fetchChunkWithRetry(url, position, end, expected);
                output.write(chunk);
                position = end + 1;
                if (progress != null) {
                    progress.onProgress(position, total);
                }
            }
            output.flush();
        }

        if (!partial.renameTo(target)) {
            // A rename across volumes fails; fall back to a copy.
            copyFile(partial, target);
            partial.delete();
        }
    }

    /**
     * Resolves a blob into the signed URL that actually serves its bytes.
     *
     * <p>The registry issues a 307 to the storage backend. The redirect is
     * followed manually, because the range requests are sent to the final
     * URL, and the signed URL must not carry the registry credentials.
     *
     * <p>The redirect response carries no body, so the size of the blob
     * cannot be read from it; the caller takes it from the manifest instead.
     */
    private String resolveBlob(String digest) throws IOException {
        URL url = new URL("https://" + registry + "/v2/" + repository + "/blobs/" + digest);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestMethod("GET");
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/octet-stream");
        authorize(connection, "repository:" + repository + ":pull");
        try {
            int status = connection.getResponseCode();
            if (status == 307 || status == 301 || status == 302 || status == 308) {
                String location = connection.getHeaderField("Location");
                if (location == null) {
                    throw new IOException("官方源未返回层下载地址");
                }
                return location;
            }
            if (status == 200) {
                // Some registries serve the blob directly.
                return url.toString();
            }
            throw new IOException("下载层失败（HTTP " + status + "）：" + digest);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Fetches one byte range, retrying when the transfer is cut short.
     *
     * <p>The storage backend sometimes closes the connection before the
     * whole range has been sent. A short chunk is not a fatal error: the
     * same range is requested again, and only a range that keeps failing
     * aborts the download.
     */
    private byte[] fetchChunkWithRetry(String url, long start, long end,
            int expected) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= CHUNK_ATTEMPTS; attempt++) {
            try {
                byte[] chunk = fetchChunk(url, start, end);
                if (chunk.length == expected) {
                    return chunk;
                }
                last = new IOException(String.format(
                        "第 %d-%d 字节只收到 %d/%d", start, end, chunk.length, expected));
            } catch (IOException failure) {
                last = failure;
            }
            Log.w(TAG, "range " + start + "-" + end + " failed, retrying", last);
        }
        throw last;
    }

    /** Fetches one byte range of a blob. */
    private byte[] fetchChunk(String url, long start, long end) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(RANGE_TIMEOUT_MS);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Range", "bytes=" + start + "-" + end);
        try {
            int status = connection.getResponseCode();
            if (status != 206 && status != 200) {
                throw new IOException("下载层失败（HTTP " + status + "）");
            }
            if (status == 200 && start > 0) {
                // The server ignored the range and restarted the body.
                throw new IOException("官方源不支持断点续传");
            }
            long declared = connection.getContentLengthLong();
            if (declared >= 0 && declared != end - start + 1) {
                throw new IOException("官方源返回的分段长度异常");
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(
                    (int) Math.min(end - start + 1, 1 << 20));
            try (InputStream input = connection.getInputStream()) {
                byte[] scratch = new byte[1 << 16];
                int count;
                while ((count = input.read(scratch)) > 0) {
                    buffer.write(scratch, 0, count);
                }
            }
            return buffer.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    /** Performs a registry request and returns the body and the content type. */
    private Response request(String method, String path, String... accept) throws IOException {
        URL url = new URL("https://" + registry + "/v2/" + repository + "/" + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestMethod(method);
        StringBuilder acceptHeader = new StringBuilder();
        for (String value : accept) {
            if (acceptHeader.length() > 0) {
                acceptHeader.append(", ");
            }
            acceptHeader.append(value);
        }
        connection.setRequestProperty("Accept", acceptHeader.toString());
        authorize(connection, "repository:" + repository + ":pull");

        int status = connection.getResponseCode();
        if (status == 401) {
            String challenge = connection.getHeaderField("WWW-Authenticate");
            connection.disconnect();
            fetchToken(challenge, "repository:" + repository + ":pull");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", acceptHeader.toString());
            authorize(connection, "repository:" + repository + ":pull");
            status = connection.getResponseCode();
        }
        if (status != 200) {
            String error = readError(connection);
            connection.disconnect();
            throw new IOException("官方源请求失败（HTTP " + status + "）：" + error);
        }

        String contentType = connection.getContentType();
        String body;
        try (InputStream input = connection.getInputStream()) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[1 << 16];
            int count;
            while ((count = input.read(chunk)) > 0) {
                buffer.write(chunk, 0, count);
            }
            body = new String(buffer.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
        return new Response(body, contentType);
    }

    /** Reads a short error body for the message. */
    private String readError(HttpURLConnection connection) {
        try (InputStream input = connection.getErrorStream()) {
            if (input == null) {
                return "";
            }
            byte[] buffer = new byte[4096];
            int count = input.read(buffer);
            if (count <= 0) {
                return "";
            }
            return new String(buffer, 0, count, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * Adds the authorization header, fetching a token when needed.
     */
    private void authorize(HttpURLConnection connection, String scope) throws IOException {
        if (cachedToken != null && !cachedToken.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + cachedToken);
        }
    }

    /**
     * Fetches a pull token for the challenge the registry returned.
     */
    private void fetchToken(String challenge, String scope) throws IOException {
        if (challenge == null) {
            throw new IOException("官方源要求认证但未返回认证方式");
        }
        if (challenge.toLowerCase(java.util.Locale.US).startsWith("basic")) {
            // An anonymous pull was refused and the registry wants basic
            // auth; there is nothing sensible to do without credentials.
            throw new IOException("官方源要求认证，无法匿名获取运行时");
        }
        if (challenge.toLowerCase(java.util.Locale.US).startsWith("bearer")) {
            String realm = extract(challenge, "realm");
            String service = extract(challenge, "service");
            if (realm == null) {
                throw new IOException("官方源认证信息不完整");
            }
            StringBuilder url = new StringBuilder(realm);
            url.append("?scope=").append(encode(scope));
            if (service != null) {
                url.append("&service=").append(encode(service));
            }
            HttpURLConnection connection = (HttpURLConnection) new URL(url.toString()).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            int status = connection.getResponseCode();
            if (status != 200) {
                connection.disconnect();
                throw new IOException("获取访问令牌失败（HTTP " + status + "）");
            }
            String body;
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[1 << 16];
                int count;
                StringBuilder builder = new StringBuilder();
                while ((count = input.read(buffer)) > 0) {
                    builder.append(new String(buffer, 0, count, java.nio.charset.StandardCharsets.UTF_8));
                }
                body = builder.toString();
            } finally {
                connection.disconnect();
            }
            int tokenAt = body.indexOf("\"token\"");
            if (tokenAt < 0) {
                tokenAt = body.indexOf("\"access_token\"");
            }
            if (tokenAt < 0) {
                throw new IOException("官方源未返回访问令牌");
            }
            int colon = body.indexOf(':', tokenAt);
            int open = body.indexOf('"', colon + 1);
            int close = body.indexOf('"', open + 1);
            if (colon < 0 || open < 0 || close < 0) {
                throw new IOException("官方源返回的访问令牌格式错误");
            }
            cachedToken = body.substring(open + 1, close);
        }
    }

    /** Extracts a quoted parameter from a challenge header. */
    private String extract(String challenge, String name) {
        int at = challenge.indexOf(name + "=");
        if (at < 0) {
            return null;
        }
        int start = challenge.indexOf('"', at);
        if (start < 0) {
            return null;
        }
        int end = challenge.indexOf('"', start + 1);
        if (end < 0) {
            return null;
        }
        return challenge.substring(start + 1, end);
    }

    /** Percent-encodes a query parameter. */
    private String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception unsupported) {
            return value;
        }
    }

    /** Determines the total size of a blob response. */
    private long contentLength(HttpURLConnection connection, long offset) {
        long declared = connection.getContentLengthLong();
        if (declared > 0) {
            return declared + offset;
        }
        String range = connection.getHeaderField("Content-Range");
        if (range != null) {
            int slash = range.lastIndexOf('/');
            if (slash > 0 && slash + 1 < range.length()) {
                try {
                    return Long.parseLong(range.substring(slash + 1).trim());
                } catch (NumberFormatException ignored) {
                    // Fall through to the unknown-length case.
                }
            }
        }
        return -1L;
    }

    /** Copies a file, replacing the destination. */
    private void copyFile(File source, File target) throws IOException {
        try (InputStream input = new java.io.FileInputStream(source);
             OutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[1 << 16];
            int count;
            while ((count = input.read(buffer)) > 0) {
                output.write(buffer, 0, count);
            }
        }
    }

    /** Deletes a file, ignoring failures. */
    static void deleteQuietly(File file) {
        if (file == null) {
            return;
        }
        try {
            file.delete();
        } catch (Exception failure) {
            Log.w(TAG, "could not delete " + file, failure);
        }
    }

    /** A raw registry response. */
    private static final class Response {
        final String body;
        final String contentType;

        Response(String body, String contentType) {
            this.body = body;
            this.contentType = contentType;
        }
    }
}
