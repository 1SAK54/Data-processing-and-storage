package ru.nsu.vorona;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Date;

public final class KeyGenerator {
    public record Result(byte[] privateKeyPem, byte[] certificatePem) {}

    private final PrivateKey issuerKey;
    private final X500Name issuerName;
    private final int bits;
    private final SecureRandom random = new SecureRandom();

    public KeyGenerator(PrivateKey issuerKey, X500Name issuerName, int bits) {
        if (bits < 2048) throw new IllegalArgumentException("Минимальный размер RSA — 2048 бит");
        this.issuerKey = issuerKey;
        this.issuerName = issuerName;
        this.bits = bits;
    }

    public Result create(String name) throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(bits, random);
        KeyPair pair = keyGen.generateKeyPair();

        // Всё имя записывается в одно поле CN, в том числе запятые
        X500Name subject = new X500NameBuilder(BCStyle.INSTANCE)
                .addRDN(BCStyle.CN, name)
                .build();
        Instant now = Instant.now();
        BigInteger serial = new BigInteger(128, random).add(BigInteger.ONE);
        X509v3CertificateBuilder certificateBuilder = new JcaX509v3CertificateBuilder(
                issuerName,
                serial,
                Date.from(now.minusSeconds(60)),
                Date.from(now.plusSeconds(365L * 24 * 60 * 60)),
                subject,
                pair.getPublic()
        );
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(issuerKey);
        byte[] cert = certificateBuilder.build(signer).getEncoded();
        return new Result(
                PemFiles.encode("PRIVATE KEY", pair.getPrivate().getEncoded()),
                PemFiles.encode("CERTIFICATE", cert)
        );
    }
}
