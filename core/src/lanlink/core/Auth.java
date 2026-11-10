package lanlink.core;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * PIN pairing. The host sends a random nonce; the viewer answers with
 * HMAC-SHA256(PIN, nonce), so the PIN itself never crosses the network.
 */
public final class Auth {
    private Auth() {}

    private static final SecureRandom RANDOM = new SecureRandom();

    public static String newPin() {
        return String.format(Locale.US, "%06d", RANDOM.nextInt(1_000_000));
    }

    public static byte[] nonce() {
        byte[] n = new byte[16];
        RANDOM.nextBytes(n);
        return n;
    }

    public static byte[] proof(String pin, byte[] nonce) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(("lanlink:" + pin.trim()).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(nonce);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean check(String pin, byte[] nonce, byte[] proof) {
        return MessageDigest.isEqual(proof(pin, nonce), proof);
    }
}
