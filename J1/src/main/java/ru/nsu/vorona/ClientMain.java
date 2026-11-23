package ru.nsu.vorona;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ClientMain {
    private ClientMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Использование: ClientMain <host> <port> <name> [--delay=N] [--abort] [--out=DIR]");
            return;
        }
        int port = Integer.parseInt(args[1]);
        int delay = 0;
        boolean abort = false;
        Path outDir = Path.of(".");
        for (int i = 3; i < args.length; i++) {
            if (args[i].startsWith("--delay=")) {
                delay = Integer.parseInt(args[i].substring(8));
            } else if (args[i].equals("--abort")) {
                abort = true;
            } else if (args[i].startsWith("--out=")) {
                outDir = Path.of(args[i].substring(6));
            } else {
                throw new IllegalArgumentException("Неизвестный аргумент: " + args[i]);
            }
        }
        KeyClient.Response response = KeyClient.request(args[0], port, args[2], delay, abort);
        if (response == null) {
            System.out.println("Соединение закрыто без чтения ответа");
            return;
        }
        Files.createDirectories(outDir);
        // Убираем символы, которые нельзя использовать в имени файла
        String safeName = args[2].replaceAll("[^A-Za-z0-9_-]", "_");
        Path keyFile = outDir.resolve(safeName + ".key");
        Path certFile = outDir.resolve(safeName + ".crt");
        Files.write(keyFile, response.privateKeyPem());
        Files.write(certFile, response.certificatePem());
        System.out.println("Сохранено: " + keyFile + ", " + certFile);
    }
}
