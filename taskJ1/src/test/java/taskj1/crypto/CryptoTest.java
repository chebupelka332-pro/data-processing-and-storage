package taskj1.crypto;

import org.bouncycastle.asn1.ASN1String;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CryptoTest {
    private static KeyPair testIssuer;

    @TempDir
    Path directory;

    @BeforeAll
    static void createFastTestIssuer() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        testIssuer = generator.generateKeyPair();
    }

    @Test
    void writesAndLoadsPkcs8WithoutOverwriting() throws Exception {
        Path path = directory.resolve("issuer.pem");
        byte[] der = testIssuer.getPrivate().getEncoded();
        PemFiles.writeNew(path, "PRIVATE KEY", der);

        PrivateKey loaded = SigningKeys.loadPrivate(path);
        assertEquals(((RSAKey) testIssuer.getPrivate()).getModulus(), ((RSAKey) loaded).getModulus());
        assertSignatureMatches(loaded, testIssuer.getPublic());
        assertArrayEquals(der, readPem(path).getContent());

        List<String> lines = Files.readAllLines(path, StandardCharsets.US_ASCII);
        assertEquals("-----BEGIN PRIVATE KEY-----", lines.getFirst());
        assertEquals("-----END PRIVATE KEY-----", lines.getLast());
        for (int i = 1; i < lines.size() - 2; i++) {
            assertEquals(64, lines.get(i).length());
        }
        assertTrue(lines.get(lines.size() - 2).length() >= 1);
        assertTrue(lines.get(lines.size() - 2).length() <= 64);
        if (Files.getFileStore(directory).supportsFileAttributeView(PosixFileAttributeView.class)) {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(path));
        }

        byte[] original = Files.readAllBytes(path);
        assertThrows(FileAlreadyExistsException.class,
                () -> PemFiles.writeNew(path, "PRIVATE KEY", new byte[]{1, 2, 3}));
        assertArrayEquals(original, Files.readAllBytes(path));
    }

    @Test
    void loadsTraditionalPkcs1PrivateKey() throws Exception {
        byte[] pkcs1 = org.bouncycastle.asn1.pkcs.RSAPrivateKey.getInstance(
                        PrivateKeyInfo.getInstance(testIssuer.getPrivate().getEncoded()).parsePrivateKey())
                .getEncoded();
        Path path = directory.resolve("traditional.pem");
        PemFiles.writeNew(path, "RSA PRIVATE KEY", pkcs1);
        assertSignatureMatches(SigningKeys.loadPrivate(path), testIssuer.getPublic());
    }

    @Test
    void rejectsNonRsaAndPublicKeyInputs() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        Path nonRsa = directory.resolve("ec.pem");
        PemFiles.writeNew(nonRsa, "PRIVATE KEY", generator.generateKeyPair().getPrivate().getEncoded());
        assertThrows(GeneralSecurityException.class, () -> SigningKeys.loadPrivate(nonRsa));

        Path publicOnly = directory.resolve("public.pem");
        PemFiles.writeNew(publicOnly, "PUBLIC KEY", testIssuer.getPublic().getEncoded());
        assertThrows(GeneralSecurityException.class, () -> SigningKeys.loadPrivate(publicOnly));
    }

    @Test
    void rejectsInconsistentCrtParametersDuringStartupSelfTest() throws Exception {
        RSAPrivateCrtKey key = (RSAPrivateCrtKey) testIssuer.getPrivate();
        var inconsistent = new org.bouncycastle.asn1.pkcs.RSAPrivateKey(
                key.getModulus(), key.getPublicExponent(), key.getPrivateExponent(),
                key.getPrimeP(), key.getPrimeQ(), BigInteger.ONE, BigInteger.ONE,
                key.getCrtCoefficient());
        var info = new PrivateKeyInfo(
                new AlgorithmIdentifier(PKCSObjectIdentifiers.rsaEncryption, DERNull.INSTANCE),
                inconsistent);
        Path path = directory.resolve("inconsistent.pem");
        PemFiles.writeNew(path, "PRIVATE KEY", info.getEncoded());
        assertThrows(GeneralSecurityException.class, () -> SigningKeys.loadPrivate(path));
    }

    @Test
    void requiresAnExistingParentDirectory() {
        Path parent = directory.resolve("missing");
        assertThrows(NoSuchFileException.class,
                () -> PemFiles.writeNew(parent.resolve("key.pem"), "PRIVATE KEY", new byte[]{1}));
        assertFalse(Files.exists(parent));
    }

    @Test
    void keyMaterialDefensivelyCopiesItsArrays() {
        byte[] privateDer = {1, 2};
        byte[] certificateDer = {3, 4};
        KeyMaterial material = new KeyMaterial(privateDer, certificateDer);
        privateDer[0] = 0;
        certificateDer[0] = 0;
        material.privateKeyDer()[1] = 0;
        material.certificateDer()[1] = 0;
        assertArrayEquals(new byte[]{1, 2}, material.privateKeyDer());
        assertArrayEquals(new byte[]{3, 4}, material.certificateDer());
    }

    @Test
    @Tag("integration")
    void generates8192BitClientMaterialWithALiteralCommonName() throws Exception {
        Path issuerPrivatePath = directory.resolve("issuer-private.pem");
        Path issuerPublicPath = directory.resolve("issuer-public.pem");
        SigningKeys.generate(issuerPrivatePath, issuerPublicPath);
        PrivateKey issuerPrivate = SigningKeys.loadPrivate(issuerPrivatePath);
        PemObject issuerPublicPem = readPem(issuerPublicPath);
        assertEquals("PRIVATE KEY", readPem(issuerPrivatePath).getType());
        assertEquals("PUBLIC KEY", issuerPublicPem.getType());
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PublicKey issuerPublic = keyFactory.generatePublic(new X509EncodedKeySpec(issuerPublicPem.getContent()));
        assertEquals(4096, ((RSAKey) issuerPrivate).getModulus().bitLength());
        assertEquals(4096, ((RSAKey) issuerPublic).getModulus().bitLength());
        assertSignatureMatches(issuerPrivate, issuerPublic);

        String issuer = "CN=Task J1 Issuer,O=Example";
        String name = "alice,OU=not-an-ou";
        Instant before = Instant.now();
        KeyMaterial material = new RsaKeyMaterialGenerator(issuerPrivate, issuer).generate(name);
        Instant after = Instant.now();
        PrivateKey clientPrivate = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(material.privateKeyDer()));
        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(material.certificateDer()));

        assertEquals(8192, ((RSAKey) clientPrivate).getModulus().bitLength());
        assertEquals(8192, ((RSAKey) certificate.getPublicKey()).getModulus().bitLength());
        assertEquals(((RSAKey) clientPrivate).getModulus(), ((RSAKey) certificate.getPublicKey()).getModulus());
        assertSignatureMatches(clientPrivate, certificate.getPublicKey());
        certificate.verify(issuerPublic);
        assertEquals(PKCSObjectIdentifiers.sha256WithRSAEncryption.getId(), certificate.getSigAlgOID());
        assertEquals(new X500Name(issuer), X500Name.getInstance(certificate.getIssuerX500Principal().getEncoded()));
        RDN[] subject = X500Name.getInstance(certificate.getSubjectX500Principal().getEncoded()).getRDNs();
        assertEquals(1, subject.length);
        assertEquals(1, subject[0].size());
        assertEquals(BCStyle.CN, subject[0].getFirst().getType());
        assertEquals(name, ((ASN1String) subject[0].getFirst().getValue()).getString());

        assertEquals(3, certificate.getVersion());
        assertEquals(-1, certificate.getBasicConstraints());
        assertTrue(certificate.getCriticalExtensionOIDs().contains(Extension.basicConstraints.getId()));
        assertTrue(certificate.getSerialNumber().signum() > 0);
        assertTrue(certificate.getSerialNumber().bitLength() <= 160);
        certificate.checkValidity();
        Instant notBefore = certificate.getNotBefore().toInstant();
        assertFalse(notBefore.isAfter(after));
        assertFalse(notBefore.isBefore(before.minus(Duration.ofMinutes(5)).minusSeconds(1)));
        assertEquals(Duration.ofDays(365), Duration.between(notBefore, certificate.getNotAfter().toInstant()));
    }

    @Test
    @Tag("integration")
    void issuerGenerationRollsBackOnlyItsOwnPrivateFile() throws Exception {
        Path privatePath = directory.resolve("issuer-private.pem");
        Path publicPath = directory.resolve("issuer-public.pem");
        PemFiles.writeNew(publicPath, "PUBLIC KEY", testIssuer.getPublic().getEncoded());
        byte[] originalPublic = Files.readAllBytes(publicPath);
        assertThrows(FileAlreadyExistsException.class, () -> SigningKeys.generate(privatePath, publicPath));
        assertFalse(Files.exists(privatePath));
        assertArrayEquals(originalPublic, Files.readAllBytes(publicPath));

        PemFiles.writeNew(privatePath, "PRIVATE KEY", testIssuer.getPrivate().getEncoded());
        byte[] originalPrivate = Files.readAllBytes(privatePath);
        Path unusedPublic = directory.resolve("unused-public.pem");
        assertThrows(FileAlreadyExistsException.class, () -> SigningKeys.generate(privatePath, unusedPublic));
        assertArrayEquals(originalPrivate, Files.readAllBytes(privatePath));
        assertFalse(Files.exists(unusedPublic));
    }

    private static PemObject readPem(Path path) throws IOException {
        try (var reader = new PemReader(Files.newBufferedReader(path, StandardCharsets.US_ASCII))) {
            return reader.readPemObject();
        }
    }

    private static void assertSignatureMatches(PrivateKey privateKey, PublicKey publicKey)
            throws GeneralSecurityException {
        byte[] message = "taskj1 crypto round-trip".getBytes(StandardCharsets.UTF_8);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(message);
        byte[] signed = signature.sign();
        signature.initVerify(publicKey);
        signature.update(message);
        assertTrue(signature.verify(signed));
    }
}
