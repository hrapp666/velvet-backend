package com.velvet.backend.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velvet.backend.exception.AppException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 验证 Apple Sign-In identityToken (RS256 JWT)。
 *
 * <p>流程：
 * <ol>
 *   <li>从 token header 拿 kid</li>
 *   <li>从 Apple JWKS (https://appleid.apple.com/auth/keys) 找对应公钥</li>
 *   <li>jjwt 用该 RSA 公钥校验签名 + iss + aud + exp</li>
 *   <li>返回 sub claim (Apple 用户唯一 ID)</li>
 * </ol>
 *
 * <p>JWKS 在内存缓存 6 小时 · 命中失败时强刷一次 (Apple 偶尔轮换 key)。
 */
@Slf4j
@Component
public class AppleIdTokenVerifier {

    private static final String JWKS_URL = "https://appleid.apple.com/auth/keys";
    private static final String EXPECTED_ISSUER = "https://appleid.apple.com";
    private static final Duration CACHE_TTL = Duration.ofHours(6);

    @Value("${velvet.apple.bundle-id}")
    private String bundleId;

    @Value("${velvet.apple.verify-audience:true}")
    private boolean verifyAudience;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    private final ReentrantLock cacheLock = new ReentrantLock();
    private volatile Map<String, PublicKey> cachedKeys = Map.of();
    private volatile long cacheLoadedAt = 0L;

    /** 校验成功返回 Apple 用户唯一 ID (sub claim)，校验失败抛 AppException。 */
    public String verifyAndExtractSub(String identityToken) {
        if (identityToken == null || identityToken.isBlank()) {
            throw new AppException("INVALID_APPLE_TOKEN", "Apple identityToken 缺失");
        }
        try {
            String kid = extractKid(identityToken);
            PublicKey publicKey = resolveKey(kid);
            var parser = Jwts.parser()
                    .verifyWith(publicKey)
                    .requireIssuer(EXPECTED_ISSUER);
            if (verifyAudience) {
                parser = parser.requireAudience(bundleId);
            }
            Claims claims = parser.build()
                    .parseSignedClaims(identityToken)
                    .getPayload();
            String sub = claims.getSubject();
            if (sub == null || sub.isBlank()) {
                throw new AppException("INVALID_APPLE_TOKEN", "Apple identityToken 缺少 sub");
            }
            return sub;
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Apple identityToken 校验失败: {}", e.getMessage());
            throw new AppException("INVALID_APPLE_TOKEN", "Apple 身份凭证无效");
        }
    }

    private String extractKid(String token) throws Exception {
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            throw new AppException("INVALID_APPLE_TOKEN", "Apple identityToken 格式错误");
        }
        byte[] headerBytes = Base64.getUrlDecoder().decode(parts[0]);
        JsonNode header = mapper.readTree(headerBytes);
        JsonNode kidNode = header.get("kid");
        if (kidNode == null || kidNode.asText().isBlank()) {
            throw new AppException("INVALID_APPLE_TOKEN", "Apple JWT 缺少 kid");
        }
        return kidNode.asText();
    }

    private PublicKey resolveKey(String kid) throws Exception {
        Map<String, PublicKey> keys = cachedKeys;
        boolean stale = System.currentTimeMillis() - cacheLoadedAt > CACHE_TTL.toMillis();
        if (!stale && keys.containsKey(kid)) {
            return keys.get(kid);
        }
        // 缓存过期或 kid miss · 强刷一次
        keys = refreshKeys();
        PublicKey key = keys.get(kid);
        if (key == null) {
            throw new AppException("INVALID_APPLE_TOKEN", "Apple JWKS 不含 kid=" + kid);
        }
        return key;
    }

    private Map<String, PublicKey> refreshKeys() throws Exception {
        cacheLock.lock();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(JWKS_URL))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new AppException("APPLE_JWKS_FAIL", "Apple JWKS 返回 " + response.statusCode());
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode keysNode = root.get("keys");
            if (keysNode == null || !keysNode.isArray()) {
                throw new AppException("APPLE_JWKS_FAIL", "Apple JWKS 响应格式错误");
            }
            Map<String, PublicKey> built = new HashMap<>();
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            for (JsonNode jwk : keysNode) {
                String kid = optText(jwk, "kid");
                String n = optText(jwk, "n");
                String e = optText(jwk, "e");
                if (kid == null || n == null || e == null) continue;
                BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(n));
                BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(e));
                RSAPublicKeySpec spec = new RSAPublicKeySpec(modulus, exponent);
                built.put(kid, keyFactory.generatePublic(spec));
            }
            this.cachedKeys = Map.copyOf(built);
            this.cacheLoadedAt = System.currentTimeMillis();
            log.info("Apple JWKS 缓存刷新 · {} keys", built.size());
            return cachedKeys;
        } finally {
            cacheLock.unlock();
        }
    }

    private static String optText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null ? null : v.asText();
    }
}
