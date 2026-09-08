package studio.weaveora.infra.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-256-GCM 密文工具：内容加密入库，接口只回打码值。 */
public final class AesGcm {

    private static final Logger log = LoggerFactory.getLogger(AesGcm.class);
    private static final SecureRandom RND = new SecureRandom();

    private AesGcm() {
    }

    private static SecretKeySpec key(String storeKey) {
        try {
            byte[] seed = (storeKey == null || storeKey.isBlank()
                    ? "weaveora-dev-fallback-key" : storeKey).getBytes(StandardCharsets.UTF_8);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(seed);
            return new SecretKeySpec(digest, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("AES key init failed", e);
        }
    }

    /** 密文格式：base64(iv + tagPrefix? GCM tag 自动) → base64(iv||ct)。 */
    public static String encrypt(String storeKey, String plain) {
        if (plain == null || plain.isBlank()) {
            return null;
        }
        try {
            byte[] iv = new byte[12];
            RND.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(storeKey), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            log.warn("encrypt failed: {}", e.getMessage());
            return null;
        }
    }

    public static String decrypt(String storeKey, String cipher) {
        if (cipher == null || cipher.isBlank()) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(cipher);
            byte[] iv = new byte[12];
            System.arraycopy(all, 0, iv, 0, 12);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(storeKey), new GCMParameterSpec(128, iv));
            byte[] pt = c.doFinal(all, 12, all.length - 12);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("decrypt failed: {}", e.getMessage());
            return null;
        }
    }

    /** 打码：sk-abc... 保留前 4 尾 4；短串全打码。 */
    public static String mask(String plain) {
        if (plain == null || plain.isBlank()) {
            return "";
        }
        if (plain.length() <= 8) {
            return "****";
        }
        return plain.substring(0, 4) + "****" + plain.substring(plain.length() - 4);
    }
}
