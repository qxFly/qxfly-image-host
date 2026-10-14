package com.qxfly.imagehost;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端接口测试（H2 内存库 + 真实 HTTP + 本地存储）。
 * <p>
 * 覆盖：登录鉴权 → 建本地存储配置 → 上传 → 匿名取图 → 图库/下载/导入 →
 * 回收站搬移与彻底删除 → 上传大小设置 → 角色权限。
 * 不依赖 MySQL，也不写任何真实存储，CI 上可直接跑。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiIntegrationTest {

    /** 1x1 的合法 PNG，ImageIO 能读出宽高 */
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private com.qxfly.imagehost.mapper.ImageMapper imageMapper;

    @Autowired
    private com.qxfly.imagehost.mapper.ShareLinkMapper shareLinkMapper;

    /** 造一条图片记录（内容哈希可控），用来构造重复数据 */
    private long insertFakeImage(long userId, String name, String hash) {
        var entity = new com.qxfly.imagehost.entity.ImageEntity();
        entity.setUserId(userId);
        entity.setStorageConfigId(configId);
        entity.setStorageType("LOCAL");
        entity.setOriginalName(name);
        entity.setObjectKey("images/fake/" + java.util.UUID.randomUUID() + ".png");
        entity.setUrl("/files/" + configId + "/" + entity.getObjectKey());
        entity.setMimeType("image/png");
        entity.setSize(1024L);
        entity.setHash(hash);
        entity.setDeleted(0);
        imageMapper.insert(entity);
        return entity.getId();
    }

    /** 整个测试类共用同一个目录：@TempDir 会按用例重建，配置里存的路径就对不上了 */
    private Path storeDir;

    @BeforeAll
    void prepareStoreDir() throws IOException {
        storeDir = Files.createTempDirectory("qxfly-it-store-");
    }

    @AfterAll
    void cleanStoreDir() throws IOException {
        if (storeDir == null || !Files.exists(storeDir)) {
            return;
        }
        try (var paths = Files.walk(storeDir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private String token;
    private long configId;
    private long imageId;
    private String imageUrl;
    private String userToken;

    // ---------------------------------------------------------------- 工具

    private HttpHeaders auth(String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private JsonNode post(String url, String bearer, String json) {
        ResponseEntity<JsonNode> response = rest.exchange(url, HttpMethod.POST,
                new HttpEntity<>(json, auth(bearer)), JsonNode.class);
        return response.getBody();
    }

    private JsonNode put(String url, String bearer, String json) {
        ResponseEntity<JsonNode> response = rest.exchange(url, HttpMethod.PUT,
                new HttpEntity<>(json, auth(bearer)), JsonNode.class);
        return response.getBody();
    }

    private JsonNode get(String url, String bearer) {
        ResponseEntity<JsonNode> response = rest.exchange(url, HttpMethod.GET,
                new HttpEntity<>(auth(bearer)), JsonNode.class);
        return response.getBody();
    }

    private JsonNode upload(String bearer, byte[] content, String fileName, Long config) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        if (config != null) {
            body.add("storageConfigId", String.valueOf(config));
        }
        return rest.postForEntity("/api/images/upload", new HttpEntity<>(body, headers), JsonNode.class).getBody();
    }

    // ---------------------------------------------------------------- 用例

    @Test
    @Order(1)
    void 登录与鉴权() {
        assertThat(post("/api/auth/login", "", "{\"username\":\"admin\",\"password\":\"wrong\"}")
                .path("code").asInt()).isNotEqualTo(200);

        JsonNode login = post("/api/auth/login", "", "{\"username\":\"admin\",\"password\":\"admin123\"}");
        assertThat(login.path("code").asInt()).isEqualTo(200);
        token = login.path("data").path("token").asText();
        assertThat(token).isNotBlank();

        // 不带 token 访问业务接口 -> 401
        ResponseEntity<JsonNode> anonymous = rest.getForEntity("/api/images/stats", JsonNode.class);
        assertThat(anonymous.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @Order(2)
    void 建本地存储配置并校验目录() {
        JsonNode created = post("/api/storage-configs", token,
                "{\"name\":\"IT Local\",\"type\":\"LOCAL\",\"config\":{\"baseDir\":\""
                        + storeDir.toString().replace("\\", "\\\\") + "\",\"pathPrefix\":\"images\"}}");
        assertThat(created.path("code").asInt()).isEqualTo(200);
        configId = created.path("data").path("id").asLong();
        assertThat(configId).isPositive();

        JsonNode tested = post("/api/storage-configs/" + configId + "/test", token, "{}");
        assertThat(tested.path("code").asInt()).isEqualTo(200);
        // 测试接口返回 {"message": "..."}
        assertThat(tested.path("data").path("message").asText()).contains("目录可写");
    }

    @Test
    @Order(3)
    void 上传图片并落到磁盘() throws Exception {
        JsonNode uploaded = upload(token, PNG, "it.png", configId);
        assertThat(uploaded.path("code").asInt()).isEqualTo(200);
        JsonNode data = uploaded.path("data");
        imageId = data.path("id").asLong();
        imageUrl = data.path("url").asText();

        assertThat(imageUrl).contains("/files/" + configId + "/");
        assertThat(data.path("size").asLong()).isEqualTo(PNG.length);
        assertThat(data.path("width").asInt()).isEqualTo(1);
        assertThat(data.path("height").asInt()).isEqualTo(1);
        assertThat(Files.exists(storeDir.resolve(data.path("objectKey").asText()))).isTrue();

        // 后端没有原生缩略图时，会下发按需缩略图的签名地址
        String thumbUrl = data.path("thumbUrl").asText();
        assertThat(thumbUrl).contains("/thumb/" + imageId + "?w=");
        assertThat(thumbUrl).startsWith("http");

        // 缩略图匿名可取（<img> 带不上 JWT），带透明通道的原图会输出 PNG
        ResponseEntity<byte[]> thumb = rest.getForEntity(thumbUrl, byte[].class);
        assertThat(thumb.getStatusCode().value()).isEqualTo(200);
        assertThat(thumb.getHeaders().getContentType().toString()).isEqualTo("image/png");
        // 原图就 1x1，缩略图不会放大
        assertThat(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(thumb.getBody())).getWidth())
                .isEqualTo(1);

        // 签名不对 -> 403（防止按 id 枚举）
        assertThat(rest.getForEntity("/thumb/" + imageId + "?w=400&s=bad", byte[].class).getStatusCode().value())
                .isEqualTo(403);
    }

    @Test
    @Order(4)
    void 图片直链匿名可访问且内容一致() {
        ResponseEntity<byte[]> response = rest.getForEntity(imageUrl, byte[].class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(PNG);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("image/png");

        ResponseEntity<JsonNode> missing = rest.getForEntity("/files/" + configId + "/images/none.png", JsonNode.class);
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @Order(5)
    void 图库与下载接口() {
        JsonNode page = get("/api/images?page=1&size=10", token);
        assertThat(page.path("code").asInt()).isEqualTo(200);
        JsonNode row = null;
        for (JsonNode record : page.path("data").path("records")) {
            if (record.path("id").asLong() == imageId) {
                row = record;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.path("previewUrl").asText()).isEqualTo(imageUrl);

        ResponseEntity<byte[]> download = rest.exchange("/api/images/" + imageId + "/download",
                HttpMethod.GET, new HttpEntity<>(auth(token)), byte[].class);
        assertThat(download.getStatusCode().value()).isEqualTo(200);
        assertThat(download.getBody()).isEqualTo(PNG);
    }

    @Test
    @Order(6)
    void 从存储导入能列出磁盘上的文件() {
        JsonNode preview = get("/api/images/import/preview?storageConfigId=" + configId, token);
        assertThat(preview.path("code").asInt()).isEqualTo(200);
        assertThat(preview.path("data").path("scanned").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(preview.path("data").path("imported").asInt()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(7)
    void 回收站搬移与彻底删除() {
        // 移入回收站：磁盘上搬到 _trash/
        assertThat(rest.exchange("/api/images/" + imageId, HttpMethod.DELETE,
                new HttpEntity<>(auth(token)), JsonNode.class).getBody().path("code").asInt()).isEqualTo(200);

        JsonNode trash = get("/api/images/trash?page=1&size=10", token);
        JsonNode row = trash.path("data").path("records").get(0);
        assertThat(row.path("id").asLong()).isEqualTo(imageId);
        assertThat(row.path("previewUrl").asText()).contains("/_trash/");
        assertThat(Files.exists(storeDir.resolve("images"))).isFalse();

        // 恢复：文件搬回原位
        String ids = "{\"imageIds\":[" + imageId + "]}";
        assertThat(post("/api/images/trash/restore", token, ids).path("code").asInt()).isEqualTo(200);
        assertThat(rest.getForEntity(imageUrl, byte[].class).getStatusCode().value()).isEqualTo(200);

        // 再删 + 彻底删除：磁盘文件消失
        rest.exchange("/api/images/" + imageId, HttpMethod.DELETE, new HttpEntity<>(auth(token)), JsonNode.class);
        assertThat(post("/api/images/trash/delete", token, ids).path("code").asInt()).isEqualTo(200);
        assertThat(rest.getForEntity(imageUrl, byte[].class).getStatusCode().value()).isEqualTo(404);
        assertThat(get("/api/images/trash?page=1&size=10", token).path("data").path("total").asInt()).isZero();
    }

    @Test
    @Order(8)
    void 单张大小上限可改且支持无上限() {
        assertThat(put("/api/settings", token, "{\"maxUploadSize\":1024}").path("data")
                .path("maxUploadSizeText").asText()).isEqualTo("1.0 KB");
        byte[] big = new byte[4096];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        assertThat(upload(token, big, "big.png", configId).path("code").asInt()).isEqualTo(400);

        assertThat(put("/api/settings", token, "{\"unlimitedUploadSize\":true}").path("data")
                .path("maxUploadSizeText").asText()).isEqualTo("无上限");
        JsonNode ok = upload(token, big, "big.png", configId);
        assertThat(ok.path("code").asInt()).isEqualTo(200);
        assertThat(ok.path("data").path("size").asLong()).isEqualTo(4096);
        long extraId = ok.path("data").path("id").asLong();

        // 清理并恢复默认
        rest.exchange("/api/images/" + extraId, HttpMethod.DELETE, new HttpEntity<>(auth(token)), JsonNode.class);
        post("/api/images/trash/delete", token, "{\"imageIds\":[" + extraId + "]}");
        assertThat(put("/api/settings", token, "{\"maxUploadSize\":31457280}").path("data")
                .path("maxUploadSizeText").asText()).isEqualTo("30.0 MB");
    }

    @Test
    @Order(9)
    void 普通用户只能看不能管() {
        JsonNode created = post("/api/users", token,
                "{\"username\":\"it_user\",\"password\":\"it12345678\",\"nickname\":\"只读\"}");
        assertThat(created.path("code").asInt()).isEqualTo(200);
        long userId = created.path("data").path("id").asLong();

        JsonNode login = post("/api/auth/login", "", "{\"username\":\"it_user\",\"password\":\"it12345678\"}");
        userToken = login.path("data").path("token").asText();

        // 能看图库
        assertThat(get("/api/images?page=1&size=5", userToken).path("code").asInt()).isEqualTo(200);
        // 不能看系统设置（403）
        assertThat(rest.exchange("/api/settings", HttpMethod.GET, new HttpEntity<>(auth(userToken)), JsonNode.class)
                .getStatusCode().value()).isEqualTo(403);
        // 不能上传（403）
        assertThat(upload(userToken, PNG, "x.png", configId).path("code").asInt()).isNotEqualTo(200);

        rest.exchange("/api/users/" + userId, HttpMethod.DELETE, new HttpEntity<>(auth(token)), JsonNode.class);
    }

    @Test
    @Order(13)
    void 分享链接可公开访问且支持密码过期与撤销() {
        long a = upload(token, withSuffix(1), "share-a.png", configId).path("data").path("id").asLong();
        long b = upload(token, withSuffix(2), "share-b.png", configId).path("data").path("id").asLong();

        JsonNode created = post("/api/shares", token,
                "{\"title\":\"IT 相册\",\"imageIds\":[" + a + "," + b + "]}");
        assertThat(created.path("code").asInt()).isEqualTo(200);
        String shareToken = created.path("data").path("token").asText();
        long shareId = created.path("data").path("id").asLong();
        assertThat(created.path("data").path("url").asText()).isEqualTo("/#/s/" + shareToken);
        assertThat(created.path("data").path("imageCount").asInt()).isEqualTo(2);

        // 公开访问：完全不带 Authorization
        JsonNode viewed = post("/api/shares/" + shareToken + "/view", "", "{}");
        assertThat(viewed.path("code").asInt()).isEqualTo(200);
        assertThat(viewed.path("data").path("images").size()).isEqualTo(2);
        assertThat(viewed.path("data").path("viewCount").asLong()).isEqualTo(1);

        // 带密码的分享：不给密码 / 密码错都进不去，给对才放行
        JsonNode locked = post("/api/shares", token,
                "{\"title\":\"有密码\",\"imageIds\":[" + a + "],\"password\":\"p@ss1234\"}");
        String lockedToken = locked.path("data").path("token").asText();
        assertThat(locked.path("data").path("passwordProtected").asBoolean()).isTrue();
        assertThat(post("/api/shares/" + lockedToken + "/view", "", "{}").path("code").asInt()).isEqualTo(403);
        assertThat(post("/api/shares/" + lockedToken + "/view", "", "{\"password\":\"wrong\"}")
                .path("code").asInt()).isEqualTo(403);
        assertThat(post("/api/shares/" + lockedToken + "/view", "", "{\"password\":\"p@ss1234\"}")
                .path("code").asInt()).isEqualTo(200);

        // 过期
        var row = shareLinkMapper.selectById(locked.path("data").path("id").asLong());
        row.setExpiresAt(java.time.LocalDateTime.now().minusDays(1));
        shareLinkMapper.updateById(row);
        assertThat(post("/api/shares/" + lockedToken + "/view", "", "{\"password\":\"p@ss1234\"}")
                .path("message").asText()).contains("过期");

        // 撤销后立刻失效
        rest.exchange("/api/shares/" + shareId, HttpMethod.DELETE, new HttpEntity<>(auth(token)), JsonNode.class);
        assertThat(post("/api/shares/" + shareToken + "/view", "", "{}").path("code").asInt()).isEqualTo(404);

        // 收尾
        rest.exchange("/api/images/" + a, HttpMethod.DELETE, new HttpEntity<>(auth(token)), JsonNode.class);
        rest.exchange("/api/images/" + b, HttpMethod.DELETE, new HttpEntity<>(auth(token)), JsonNode.class);
        post("/api/images/trash/delete", token, "{\"imageIds\":[" + a + "," + b + "]}");
    }

    @Test
    @Order(14)
    void 防盗链可以按白名单拦截() {
        long id = upload(token, withSuffix(3), "hotlink.png", configId).path("data").path("id").asLong();
        String url = get("/api/images?page=1&size=10", token).path("data").path("records").get(0).path("url").asText();

        // 默认关闭：随便什么 Referer 都能取
        assertThat(getWithReferer(url, "http://evil.example.com/").getStatusCode().value()).isEqualTo(200);

        // 打开白名单：站外被拦，本站与白名单放行，无 Referer 默认放行
        put("/api/settings", token,
                "{\"hotlinkEnabled\":true,\"hotlinkDomains\":\"good.example.com\",\"hotlinkAllowEmpty\":true}");
        assertThat(getWithReferer(url, "http://evil.example.com/").getStatusCode().value()).isEqualTo(403);
        assertThat(getWithReferer(url, "https://good.example.com/page").getStatusCode().value()).isEqualTo(200);
        assertThat(getWithReferer(url, null).getStatusCode().value()).isEqualTo(200);

        // 关掉恢复原状
        put("/api/settings", token, "{\"hotlinkEnabled\":false}");
        assertThat(getWithReferer(url, "http://evil.example.com/").getStatusCode().value()).isEqualTo(200);

        rest.exchange("/api/images/" + id, HttpMethod.DELETE, new HttpEntity<>(auth(token)), JsonNode.class);
        post("/api/images/trash/delete", token, "{\"imageIds\":[" + id + "]}");
    }

    private byte[] withSuffix(int index) {
        byte[] bytes = new byte[PNG.length + 1];
        System.arraycopy(PNG, 0, bytes, 0, PNG.length);
        bytes[PNG.length] = (byte) index;
        return bytes;
    }

    private ResponseEntity<byte[]> getWithReferer(String url, String referer) {
        HttpHeaders headers = new HttpHeaders();
        if (referer != null) {
            headers.set("Referer", referer);
        }
        return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
    }

    @Test
    @Order(12)
    void 重复图片能查出并清理且保留最早一张() {
        // 秒传会拦住正常上传，重复数据来自导入 / 迁移，这里直接造两条同 hash 的记录
        long adminId = get("/api/auth/me", token).path("data").path("id").asLong();
        long first = insertFakeImage(adminId, "dup-a.png", "dup-hash-1");
        long second = insertFakeImage(adminId, "dup-b.png", "dup-hash-1");

        JsonNode groups = get("/api/images/duplicates?page=1&size=10", token);
        assertThat(groups.path("code").asInt()).isEqualTo(200);
        assertThat(groups.path("data").path("total").asInt()).isGreaterThanOrEqualTo(1);
        JsonNode group = null;
        for (JsonNode item : groups.path("data").path("groups")) {
            if ("dup-hash-1".equals(item.path("hash").asText())) {
                group = item;
            }
        }
        assertThat(group).isNotNull();
        assertThat(group.path("count").asInt()).isEqualTo(2);
        assertThat(group.path("images").get(0).path("id").asLong()).isEqualTo(first);
        assertThat(group.path("images").get(0).path("keep").asBoolean()).isTrue();

        // 清理：保留最早的一张（first），另一张移入回收站
        assertThat(rest.exchange("/api/images/duplicates?hash=dup-hash-1", HttpMethod.DELETE,
                new HttpEntity<>(auth(token)), JsonNode.class).getBody().path("data").asInt()).isEqualTo(1);
        JsonNode trash = get("/api/images/trash?page=1&size=50", token);
        boolean secondTrashed = false;
        for (JsonNode row : trash.path("data").path("records")) {
            if (row.path("id").asLong() == second) {
                secondTrashed = true;
            }
        }
        assertThat(secondTrashed).isTrue();

        // 收尾：把两条都彻底删掉
        post("/api/images/trash/delete", token, "{\"imageIds\":[" + first + "," + second + "]}");
    }

    @Test
    @Order(10)
    void 审计日志覆盖关键操作且普通用户无权查看() {
        JsonNode page = get("/api/audit-logs?page=1&size=200", token);
        assertThat(page.path("code").asInt()).isEqualTo(200);
        java.util.List<String> actions = new java.util.ArrayList<>();
        for (JsonNode row : page.path("data").path("records")) {
            actions.add(row.path("action").asText());
        }
        assertThat(actions)
                .contains("LOGIN", "UPLOAD", "TRASH_MOVE", "TRASH_RESTORE", "IMAGE_PURGE",
                        "STORAGE_CREATE", "USER_CREATE", "SETTINGS_UPDATE");

        // 失败也要留痕
        post("/api/auth/login", "", "{\"username\":\"admin\",\"password\":\"nope\"}");
        JsonNode failed = get("/api/audit-logs?action=LOGIN_FAILED&size=5", token);
        assertThat(failed.path("data").path("total").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(failed.path("data").path("records").get(0).path("detail").asText()).contains("用户名或密码错误");
        assertThat(failed.path("data").path("records").get(0).path("ip").asText()).isNotBlank();

        // 普通用户看不到
        post("/api/users", token, "{\"username\":\"it_audit\",\"password\":\"it12345678\"}");
        JsonNode login = post("/api/auth/login", "", "{\"username\":\"it_audit\",\"password\":\"it12345678\"}");
        String reader = login.path("data").path("token").asText();
        assertThat(rest.exchange("/api/audit-logs", HttpMethod.GET, new HttpEntity<>(auth(reader)), JsonNode.class)
                .getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @Order(11)
    void 登录失败次数达到上限会被锁定() {
        assertThat(put("/api/settings", token, "{\"loginMaxAttempts\":3,\"loginLockMinutes\":5}")
                .path("data").path("loginMaxAttempts").asInt()).isEqualTo(3);

        post("/api/users", token, "{\"username\":\"it_locked\",\"password\":\"it12345678\"}");
        for (int i = 0; i < 3; i++) {
            assertThat(post("/api/auth/login", "", "{\"username\":\"it_locked\",\"password\":\"bad\"}")
                    .path("code").asInt()).isNotEqualTo(200);
        }
        // 锁上之后，即使密码正确也进不来
        JsonNode blocked = post("/api/auth/login", "", "{\"username\":\"it_locked\",\"password\":\"it12345678\"}");
        assertThat(blocked.path("code").asInt()).isEqualTo(403);
        assertThat(blocked.path("message").asText()).contains("锁定");

        JsonNode lockedLog = get("/api/audit-logs?action=LOGIN_LOCKED&size=5", token);
        assertThat(lockedLog.path("data").path("total").asInt()).isGreaterThanOrEqualTo(1);

        // 恢复默认，别影响别的测试
        put("/api/settings", token, "{\"loginMaxAttempts\":5,\"loginLockMinutes\":15}");
    }
}
