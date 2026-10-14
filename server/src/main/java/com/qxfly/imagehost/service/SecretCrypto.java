package com.qxfly.imagehost.service;

import com.qxfly.imagehost.config.AppProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 存储配置中敏感字段的加解密（AES-256/GCM）。
 * 密文格式：enc:v1:{base64(iv)}:{base64(cipherText)}
 */
@Component
public class SecretCrypto {

    private static final String PREFIX = "enc:v1:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec keySpec;
    private final SecureRandom random = new SecureRandom();

    public SecretCrypto(AppProperties appProperties) {
        try {
            byte[] key = MessageDigest.getInstance("SHA-256")
                    .digest(appProperties.getCryptoKey().getBytes(StandardCharsets.UTF_8));
            this.keySpec = new SecretKeySpec(key, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("初始化加密组件失败", e);
        }
    }

    public String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty() || plainText.startsWith(PREFIX)) {
            return plainText;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder encoder = Base64.getEncoder();
            return PREFIX + encoder.encodeToString(iv) + ":" + encoder.encodeToString(cipherText);
        } catch (Exception e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    public String decrypt(String value) {
        if (value == null || !value.startsWith(PREFIX)) {
            return value;
        }
        try {
            String[] parts = value.substring(PREFIX.length()).split(":", 2);
            Base64.Decoder decoder = Base64.getDecoder();
            byte[] iv = decoder.decode(parts[0]);
            byte[] cipherText = decoder.decode(parts[1]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 口令被更换等情况：返回原文避免整个应用不可用，由调用方在测试连接时暴露问题
            return value;
        }
    }
}
