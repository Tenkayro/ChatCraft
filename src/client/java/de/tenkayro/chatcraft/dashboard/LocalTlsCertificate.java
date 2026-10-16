package de.tenkayro.chatcraft.dashboard;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

final class LocalTlsCertificate {
    private static final byte[] SHA256_WITH_RSA = sequence(oid("1.2.840.113549.1.1.11"), tagged(0x05, new byte[0]));
    private static final DateTimeFormatter UTC_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'")
        .withZone(ZoneOffset.UTC);

    private LocalTlsCertificate() { }

    static SSLContext createContext() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        KeyPair pair = generator.generateKeyPair();
        X509Certificate certificate = createCertificate(pair);

        char[] password = new char[0];
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("chatcraft-local", pair.getPrivate(), password,
            new java.security.cert.Certificate[]{certificate});
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagerFactory.getKeyManagers(), null, new SecureRandom());
        return context;
    }

    private static X509Certificate createCertificate(KeyPair pair) throws Exception {
        Instant now = Instant.now();
        byte[] name = sequence(set(sequence(oid("2.5.4.3"), utf8("ChatCraft localhost"))));
        byte[] subjectAlternativeNames = sequence(
            tagged(0x82, "localhost".getBytes(StandardCharsets.US_ASCII)),
            tagged(0x87, new byte[]{127, 0, 0, 1}),
            tagged(0x87, new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1})
        );
        byte[] extensions = tagged(0xA3, sequence(
            sequence(oid("2.5.29.17"), octetString(subjectAlternativeNames)),
            sequence(oid("2.5.29.37"), octetString(sequence(oid("1.3.6.1.5.5.7.3.1"))))
        ));
        byte[] serialBytes = new byte[16];
        new SecureRandom().nextBytes(serialBytes);
        byte[] toBeSigned = sequence(
            tagged(0xA0, integer(BigInteger.valueOf(2))),
            integer(new BigInteger(1, serialBytes)),
            SHA256_WITH_RSA,
            name,
            sequence(utcTime(now.minusSeconds(86_400)), utcTime(now.plusSeconds(31_536_000))),
            name,
            pair.getPublic().getEncoded(),
            extensions
        );

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate());
        signer.update(toBeSigned);
        byte[] encoded = sequence(toBeSigned, SHA256_WITH_RSA, bitString(signer.sign()));
        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
            .generateCertificate(new ByteArrayInputStream(encoded));
        certificate.checkValidity();
        certificate.verify(pair.getPublic());
        return certificate;
    }

    private static byte[] sequence(byte[]... values) { return tagged(0x30, concat(values)); }
    private static byte[] set(byte[]... values) { return tagged(0x31, concat(values)); }
    private static byte[] utf8(String value) { return tagged(0x0C, value.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] octetString(byte[] value) { return tagged(0x04, value); }
    private static byte[] integer(BigInteger value) { return tagged(0x02, value.toByteArray()); }
    private static byte[] utcTime(Instant value) {
        return tagged(0x17, UTC_TIME.format(value).getBytes(StandardCharsets.US_ASCII));
    }
    private static byte[] bitString(byte[] value) {
        byte[] content = new byte[value.length + 1];
        System.arraycopy(value, 0, content, 1, value.length);
        return tagged(0x03, content);
    }

    private static byte[] oid(String value) {
        long[] parts = Arrays.stream(value.split("\\.")).mapToLong(Long::parseLong).toArray();
        if (parts.length < 2) throw new IllegalArgumentException("Invalid OID");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeBase128(output, parts[0] * 40 + parts[1]);
        for (int i = 2; i < parts.length; i++) writeBase128(output, parts[i]);
        return tagged(0x06, output.toByteArray());
    }

    private static void writeBase128(ByteArrayOutputStream output, long value) {
        int groups = 1;
        for (long remaining = value >>> 7; remaining != 0; remaining >>>= 7) groups++;
        for (int group = groups - 1; group >= 0; group--) {
            int next = (int) ((value >>> (group * 7)) & 0x7F);
            output.write(group == 0 ? next : next | 0x80);
        }
    }

    private static byte[] tagged(int tag, byte[] content) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(content.length + 6);
        output.write(tag);
        writeLength(output, content.length);
        output.writeBytes(content);
        return output.toByteArray();
    }

    private static void writeLength(ByteArrayOutputStream output, int length) {
        if (length < 128) {
            output.write(length);
            return;
        }
        int bytes = 0;
        for (int value = length; value > 0; value >>>= 8) bytes++;
        output.write(0x80 | bytes);
        for (int shift = (bytes - 1) * 8; shift >= 0; shift -= 8) output.write((length >>> shift) & 0xFF);
    }

    private static byte[] concat(byte[]... values) {
        int length = Arrays.stream(values).mapToInt(value -> value.length).sum();
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, result, offset, value.length);
            offset += value.length;
        }
        return result;
    }
}
