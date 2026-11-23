package ru.nsu.vorona;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

public final class PemFiles {
    private PemFiles() {}

    public static byte[] encode(String type, byte[] der) {
        String data = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der);
        return ("-----BEGIN " + type + "-----\n" + data + "\n-----END " + type + "-----\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    public static PrivateKey readPrivateKey(Path path) throws Exception {
        String value = Files.readString(path, StandardCharsets.US_ASCII);
        String begin = "-----BEGIN PRIVATE KEY-----";
        String end = "-----END PRIVATE KEY-----";
        int first = value.indexOf(begin);
        int last = value.indexOf(end);
        if (first < 0 || last < first) throw new IOException("Ожидался PKCS#8 PEM-ключ");
        String base64 = value.substring(first + begin.length(), last);
        byte[] der = Base64.getMimeDecoder().decode(base64);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }
}
