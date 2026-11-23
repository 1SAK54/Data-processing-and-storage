package ru.nsu.vorona;

import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class KeyServerTest {
    private static KeyServer server;
    private static Thread serverThread;
    private static int port;
    private static KeyPair issuer;

    @BeforeAll
    static void start() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048); // Для тестов достаточно меньшего размера ключа
        issuer = generator.generateKeyPair();
        KeyGenerator service = new KeyGenerator(
                issuer.getPrivate(),
                new X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, "TestIssuer").build(),
                2048
        );
        server = new KeyServer("127.0.0.1", 0, 3, service);
        port = server.port();
        serverThread = new Thread(() -> {
            try { server.serve(); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        serverThread.start();
    }

    @AfterAll
    static void stop() throws Exception {
        server.close();
        serverThread.join(5000);
        assertFalse(serverThread.isAlive());
    }

    @Test
    void returnsValidCertificate() throws Exception {
        KeyClient.Response response = KeyClient.request("127.0.0.1", port, "IvanIvanov", 0, false);
        assertNotNull(response);
        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(response.certificatePem()));
        certificate.verify(issuer.getPublic());
        assertEquals("CN=IvanIvanov", certificate.getSubjectX500Principal().getName());
        assertEquals("CN=TestIssuer", certificate.getIssuerX500Principal().getName());

        String pem = new String(response.privateKeyPem(), StandardCharsets.US_ASCII);
        byte[] der = Base64.getMimeDecoder().decode(pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", ""));
        PrivateKey privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(der));
        assertEquals(2048, ((java.security.interfaces.RSAPrivateKey) privateKey).getModulus().bitLength());
        assertEquals(((java.security.interfaces.RSAPublicKey) certificate.getPublicKey()).getModulus(),
                ((java.security.interfaces.RSAPrivateKey) privateKey).getModulus());
    }

    @Test
    void sameNameUsesCachedKey() throws Exception {
        KeyClient.Response first = KeyClient.request("127.0.0.1", port, "Alex", 0, false);
        KeyClient.Response second = KeyClient.request("127.0.0.1", port, "Alex", 0, false);
        assertArrayEquals(first.privateKeyPem(), second.privateKeyPem());
        assertArrayEquals(first.certificatePem(), second.certificatePem());
    }

    @Test
    void differentNamesHaveDifferentKeys() throws Exception {
        KeyClient.Response first = KeyClient.request("127.0.0.1", port, "Anna", 0, false);
        KeyClient.Response second = KeyClient.request("127.0.0.1", port, "Maria", 0, false);
        assertFalse(java.util.Arrays.equals(first.privateKeyPem(), second.privateKeyPem()));
    }

    @Test
    void handlesOneHundredConcurrentConnections() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<KeyClient.Response>> requests = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                int delay = i % 5 == 0 ? 1 : 0;
                requests.add(executor.submit(() -> KeyClient.request("127.0.0.1", port, "Common", delay, false)));
            }
            byte[] first = requests.getFirst().get(60, TimeUnit.SECONDS).privateKeyPem();
            for (var request : requests) {
                assertArrayEquals(first, request.get(60, TimeUnit.SECONDS).privateKeyPem());
            }
        }
    }

    @Test
    void rejectsNonAsciiName() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(new byte[]{(byte) 0xFF, 0});
            DataInputStream in = new DataInputStream(socket.getInputStream());
            assertEquals(Protocol.BAD_REQUEST, in.readUnsignedByte());
        }
        assertNotNull(KeyClient.request("127.0.0.1", port, "Valid", 0, false));
    }

    @Test
    void abortedClientDoesNotBreakServer() throws Exception {
        assertNull(KeyClient.request("127.0.0.1", port, "Drop", 0, true));
        assertNotNull(KeyClient.request("127.0.0.1", port, "Next", 0, false));
    }

    @Test
    void shutdownClosesAcceptedConnections() throws Exception {
        KeyGenerator service = new KeyGenerator(issuer.getPrivate(),
                new X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, "TestIssuer").build(), 2048);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (KeyServer local = new KeyServer("127.0.0.1", 0, 1, service)) {
            int localPort = local.port();
            Thread thread = new Thread(() -> {
                try { local.serve(); }
                catch (Throwable e) { failure.set(e); }
            });
            thread.start();
            try (Socket unfinished = new Socket("127.0.0.1", localPort)) {
                unfinished.setSoTimeout(2000);
                unfinished.getOutputStream().write('X');
                unfinished.getOutputStream().flush();
                // Ответ на втором соединении подтверждает, что сервер уже принимает клиентов
                try (Socket probe = new Socket("127.0.0.1", localPort)) {
                    probe.setSoTimeout(2000);
                    probe.getOutputStream().write(new byte[]{(byte) 0xff, 0});
                    assertEquals(Protocol.BAD_REQUEST, probe.getInputStream().read());
                }
                local.close();
                thread.join(3000);
                assertFalse(thread.isAlive());
                assertNull(failure.get());
                try {
                    assertEquals(-1, unfinished.getInputStream().read());
                } catch (SocketException e) {
                    // При непрочитанных байтах ОС может закрыть соединение через TCP reset
                }
            } finally {
                local.close();
                thread.join(3000);
            }
        }
    }

    @Test
    void closedServerCannotBeStarted() throws Exception {
        KeyGenerator service = new KeyGenerator(issuer.getPrivate(),
                new X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, "TestIssuer").build(), 2048);
        try (KeyServer local = new KeyServer("127.0.0.1", 0, 1, service)) {
            local.close();
            local.close();
            assertThrows(IllegalStateException.class, local::serve);
        }
    }
}
