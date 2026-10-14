package com.qxfly.imagehost.storage.support;

import com.qxfly.imagehost.common.BizException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基于 JDK HttpClient 的极简 HTTP 工具，供 GitHub / SM.MS / ImgBB 等 REST 型图床使用。
 * 不引入额外 HTTP 客户端依赖，避免与各云厂商 SDK 的 okhttp 版本冲突。
 */
public final class HttpSupport {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private HttpSupport() {
    }

    /**
     * HTTP 响应结果。
     */
    public record HttpResult(int status, String body) {

        public boolean isOk() {
            return status >= 200 && status < 300;
        }

        public void requireOk(String action) {
            if (!isOk()) {
                throw new BizException(action + "失败 (HTTP " + status + "): " + abbreviate(body()));
            }
        }
    }

    public static HttpResult get(String url, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).GET();
        applyHeaders(builder, headers);
        return send(builder.build());
    }

    /** 二进制响应（下载文件用） */
    public record BinaryResult(int status, byte[] body) {
    }

    public static BinaryResult getBinary(String url, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).GET();
        applyHeaders(builder, headers);
        try {
            HttpResponse<byte[]> response = CLIENT.send(builder.build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            return new BinaryResult(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new BizException("下载 " + URI.create(url).getHost() + " 的文件失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("下载被中断");
        }
    }

    public static HttpResult postJson(String url, Map<String, String> headers, String json) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        applyHeaders(builder, headers);
        return send(builder.build());
    }

    public static HttpResult postForm(String url, Map<String, String> headers, Map<String, String> form) {
        StringBuilder body = new StringBuilder();
        form.forEach((k, v) -> {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(encode(k)).append('=').append(encode(v));
        });
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        applyHeaders(builder, headers);
        return send(builder.build());
    }

    /**
     * multipart/form-data 上传。
     */
    public static HttpResult postMultipart(String url,
                                           Map<String, String> headers,
                                           Map<String, String> fields,
                                           String fileField,
                                           String fileName,
                                           String contentType,
                                           byte[] file) {
        String boundary = "----QxflyImageHostBoundary" + Long.toHexString(System.nanoTime());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"" + entry.getKey() + "\"\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            if (file != null) {
                out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"" + fileField
                        + "\"; filename=\"" + (fileName == null ? "image" : fileName) + "\"\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Type: " + (contentType == null ? "application/octet-stream" : contentType) + "\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write(file);
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("构造 multipart 请求失败", e);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()));
        applyHeaders(builder, headers);
        return send(builder.build());
    }

    public static HttpResult putJson(String url, Map<String, String> headers, String json) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json; charset=utf-8")
                .PUT(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        applyHeaders(builder, headers);
        return send(builder.build());
    }

    public static HttpResult patchJson(String url, Map<String, String> headers, String json) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json; charset=utf-8")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        applyHeaders(builder, headers);
        return send(builder.build());
    }

    public static HttpResult deleteJson(String url, Map<String, String> headers, String json) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json; charset=utf-8")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(json == null ? "" : json, StandardCharsets.UTF_8));
        applyHeaders(builder, headers);
        return send(builder.build());
    }

    public static HttpResult postBinary(String url, Map<String, String> headers, String contentType, byte[] body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", contentType == null ? "application/octet-stream" : contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        applyHeaders(builder, headers);
        return send(builder.build());
    }

    /**
     * 以磁盘文件作为请求体（HttpClient 按流发送，几百 MB 也不会读进内存）。
     */
    public static HttpResult postBinaryFile(String url, Map<String, String> headers,
                                            String contentType, Path file, long length) {
        HttpRequest.BodyPublisher body;
        try {
            body = HttpRequest.BodyPublishers.ofFile(file);
        } catch (IOException e) {
            throw new BizException("读取待上传文件失败: " + e.getMessage());
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", contentType == null ? "application/octet-stream" : contentType)
                .POST(body);
        if (length > 0) {
            builder.header("Content-Length", String.valueOf(length));
        }
        applyHeaders(builder, headers);
        return send(builder.build());
    }

    /** 预置一组通用请求头 */
    public static Map<String, String> headers(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    public static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static void applyHeaders(HttpRequest.Builder builder, Map<String, String> headers) {
        builder.header("User-Agent", "QxflyImageHost/0.1");
        builder.timeout(Duration.ofMinutes(5));
        if (headers != null) {
            headers.forEach((k, v) -> {
                if (v != null && !v.isBlank()) {
                    builder.header(k, v);
                }
            });
        }
    }

    private static HttpResult send(HttpRequest request) {
        try {
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new HttpResult(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new BizException("请求 " + request.uri().getHost() + " 失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("请求被中断");
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }
}
