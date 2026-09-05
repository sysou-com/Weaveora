package studio.weaveora.identity;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Argon2id 密码哈希（§22：织影统一 Argon2id，不混用 BCrypt）。
 * 格式：$argon2id$v=19$m=65536,t=3,p=4$<salt>$<hash>（BCrypt 风格可辨识前缀自定）。
 */
@Component
public class Argon2PasswordEncoder implements PasswordEncoder {

    private static final int MEMORY_KIB = 65536;   // 64 MiB
    private static final int ITERATIONS = 3;
    private static final int PARALLELISM = 4;
    private static final int HASH_LEN = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public String encode(CharSequence rawPassword) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = argon2(rawPassword.toString().getBytes(StandardCharsets.UTF_8), salt);
        return "argon2id$" + MEMORY_KIB + "$" + ITERATIONS + "$" + PARALLELISM + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(hash);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encoded) {
        try {
            String[] parts = encoded.split("\\$");
            // parts: argon2id | m | t | p | saltB64 | hashB64
            int m = Integer.parseInt(parts[1]);
            int t = Integer.parseInt(parts[2]);
            int p = Integer.parseInt(parts[3]);
            byte[] salt = Base64.getDecoder().decode(parts[4]);
            byte[] expected = Base64.getDecoder().decode(parts[5]);
            byte[] actual = argon2(rawPassword.toString().getBytes(StandardCharsets.UTF_8), salt,
                    m, t, p, expected.length);
            return constantTimeEquals(actual, expected);
        } catch (Exception e) {
            return false;
        }
    }

    private byte[] argon2(byte[] password, byte[] salt) {
        return argon2(password, salt, MEMORY_KIB, ITERATIONS, PARALLELISM, HASH_LEN);
    }

    private byte[] argon2(byte[] password, byte[] salt, int mem, int iters, int par, int hashLen) {
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(salt)
                .withMemoryAsKB(mem)
                .withIterations(iters)
                .withParallelism(par)
                .build();
        Argon2BytesGenerator gen = new Argon2BytesGenerator();
        gen.init(params);
        byte[] out = new byte[hashLen];
        gen.generateBytes(password, out);
        return out;
    }

    private boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int r = 0;
        for (int i = 0; i < a.length; i++) r |= a[i] ^ b[i];
        return r == 0;
    }
}
