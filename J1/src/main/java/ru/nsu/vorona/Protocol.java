package ru.nsu.vorona;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class Protocol {
    public static final int OK = 0;
    public static final int BAD_REQUEST = 1;
    public static final int SERVER_ERROR = 2;
    public static final int MAX_NAME = 128;
    public static final int MAX_RESULT = 1024 * 1024;

    private Protocol() {}

    public static byte[] success(KeyGenerator.Result result) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(OK);
            out.writeInt(result.privateKeyPem().length);
            out.write(result.privateKeyPem());
            out.writeInt(result.certificatePem().length);
            out.write(result.certificatePem());
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] error(int status, String message) {
        try {
            byte[] data = message.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(status);
            out.writeInt(data.length);
            out.write(data);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean validName(byte[] bytes) {
        if (bytes.length < 1 || bytes.length > MAX_NAME) return false;
        for (byte b : bytes) {
            if (b < 32 || b > 126) return false;
        }
        return true;
    }
}
