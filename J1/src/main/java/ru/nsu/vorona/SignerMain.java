package ru.nsu.vorona;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;

public final class SignerMain {
    private SignerMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            System.out.println("Использование: SignerMain <файл.key> [размер_ключа]");
            return;
        }
        int bits = args.length == 2 ? Integer.parseInt(args[1]) : 3072;
        if (bits < 2048) throw new IllegalArgumentException("Размер ключа должен быть >= 2048");
        Path path = Path.of(args[0]);
        if (Files.exists(path)) throw new IllegalArgumentException("Файл уже существует: " + path);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        Files.write(path, PemFiles.encode("PRIVATE KEY", generator.generateKeyPair().getPrivate().getEncoded()));
        System.out.println("Ключ подписи создан: " + path.toAbsolutePath());
    }
}
