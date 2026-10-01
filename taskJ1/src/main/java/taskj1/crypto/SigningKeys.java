package taskj1.crypto;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPublicKeySpec;

public final class SigningKeys {
    private static final Provider BC = new BouncyCastleProvider();

    private SigningKeys() {
    }

    public static PrivateKey loadPrivate(Path path) throws IOException, GeneralSecurityException {
        PrivateKey key;
        try (var parser = new PEMParser(Files.newBufferedReader(path, StandardCharsets.US_ASCII))) {
            Object object = parser.readObject();
            PrivateKeyInfo keyInfo;
            if (object instanceof PrivateKeyInfo pkcs8) {
                keyInfo = pkcs8;
            } else if (object instanceof PEMKeyPair pkcs1) {
                keyInfo = pkcs1.getPrivateKeyInfo();
            } else {
                throw new InvalidKeyException("Expected an unencrypted PKCS#8 or RSA PKCS#1 private key");
            }
            key = new JcaPEMKeyConverter().setProvider(BC).getPrivateKey(keyInfo);
        }

        if (!"RSA".equalsIgnoreCase(key.getAlgorithm()) || !(key instanceof RSAPrivateCrtKey crt)) {
            throw new InvalidKeyException("The signing key must be an RSA private key with CRT parameters");
        }
        try {
            PublicKey publicKey = KeyFactory.getInstance("RSA", BC).generatePublic(
                    new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
            SecureRandom random = new SecureRandom();
            byte[] challenge = new byte[32];
            random.nextBytes(challenge);
            Signature signature = Signature.getInstance("SHA256withRSA", BC);
            signature.initSign(key, random);
            signature.update(challenge);
            byte[] signed = signature.sign();
            signature.initVerify(publicKey);
            signature.update(challenge);
            if (!signature.verify(signed)) {
                throw new InvalidKeyException("RSA signing key failed its signing self-test");
            }
        } catch (RuntimeException failure) {
            throw new InvalidKeyException("RSA signing key failed its signing self-test", failure);
        }
        return key;
    }

    public static void generate(Path privatePath, Path publicPath)
            throws IOException, GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(4096, new SecureRandom());
        KeyPair pair = generator.generateKeyPair();

        PemFiles.writeNew(privatePath, "PRIVATE KEY", pair.getPrivate().getEncoded());
        try {
            PemFiles.writeNew(publicPath, "PUBLIC KEY", pair.getPublic().getEncoded());
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(privatePath);
            } catch (IOException | RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }
}
