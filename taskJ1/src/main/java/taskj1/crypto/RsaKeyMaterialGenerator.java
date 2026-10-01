package taskj1.crypto;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;

public final class RsaKeyMaterialGenerator implements KeyMaterialGenerator {
    private static final Provider BC = new BouncyCastleProvider();
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final Duration VALIDITY = Duration.ofDays(365);

    private final PrivateKey signingKey;
    private final X500Name issuer;

    public RsaKeyMaterialGenerator(PrivateKey signingKey, String issuer) {
        this.signingKey = Objects.requireNonNull(signingKey, "signingKey");
        if (!"RSA".equalsIgnoreCase(signingKey.getAlgorithm())) {
            throw new IllegalArgumentException("The issuer signing key must be RSA");
        }
        this.issuer = new X500Name(Objects.requireNonNull(issuer, "issuer"));
    }

    @Override
    public KeyMaterial generate(String name) throws Exception {
        X500Name subject = new X500NameBuilder(BCStyle.INSTANCE)
                .addRDN(BCStyle.CN, Objects.requireNonNull(name, "name"))
                .build();
        SecureRandom random = new SecureRandom();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(8192, random);
        KeyPair pair = generator.generateKeyPair();

        BigInteger serial;
        do {
            serial = new BigInteger(159, random);
        } while (serial.signum() == 0);

        Instant notBefore = Instant.now().minus(CLOCK_SKEW);
        var certificate = new JcaX509v3CertificateBuilder(
                issuer, serial, Date.from(notBefore), Date.from(notBefore.plus(VALIDITY)),
                subject, pair.getPublic());
        certificate.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        var signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider(BC)
                .setSecureRandom(random)
                .build(signingKey);
        return new KeyMaterial(pair.getPrivate().getEncoded(), certificate.build(signer).getEncoded());
    }
}
