package ru.nsu.vorona;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class KeyClient {
    public record Response(byte[] privateKeyPem, byte[] certificatePem) {}

    private KeyClient() {}

    public static Response request(String host, int port, String name, int delaySeconds, boolean abort) throws Exception {
        byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
        if (!Protocol.validName(nameBytes) || !name.equals(new String(nameBytes, StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("Имя должно содержать 1–128 печатных ASCII-символов");
        }
        if (delaySeconds < 0) throw new IllegalArgumentException("Задержка не может быть отрицательной");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            // Не ограничиваем время ожидания: перед запросом может быть очередь генерации
            socket.setSoTimeout(0);
            socket.getOutputStream().write(nameBytes);
            socket.getOutputStream().write(0);
            socket.getOutputStream().flush();
            if (abort) return null;
            if (delaySeconds > 0) Thread.sleep(1000L * delaySeconds);

            DataInputStream in = new DataInputStream(socket.getInputStream());
            int status = in.readUnsignedByte();
            if (status != Protocol.OK) {
                byte[] message = readBytes(in);
                throw new IOException("Сервер вернул ошибку " + status + ": " + new String(message, StandardCharsets.UTF_8));
            }
            return new Response(readBytes(in), readBytes(in));
        }
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > Protocol.MAX_RESULT) throw new IOException("Недопустимая длина ответа: " + length);
        byte[] data = new byte[length];
        in.readFully(data);
        return data;
    }
}
