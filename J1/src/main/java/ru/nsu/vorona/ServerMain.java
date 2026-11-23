package ru.nsu.vorona;

import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;

import java.nio.file.Path;

public final class ServerMain {
    private ServerMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.out.println("Использование: ServerMain <порт> <потоки> <ключ_подписи.key> <имя_issuer>");
            return;
        }
        int port = Integer.parseInt(args[0]);
        int workers = Integer.parseInt(args[1]);
        var signerKey = PemFiles.readPrivateKey(Path.of(args[2]));
        var issuer = new X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, args[3]).build();
        KeyGenerator generator = new KeyGenerator(signerKey, issuer, 8192);
        try (KeyServer server = new KeyServer("0.0.0.0", port, workers, generator)) {
            System.out.println("Сервер слушает порт " + server.port());
            server.serve();
        }
    }
}
