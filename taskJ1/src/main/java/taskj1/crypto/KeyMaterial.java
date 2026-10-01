package taskj1.crypto;

import java.util.Objects;

public record KeyMaterial(byte[] privateKeyDer, byte[] certificateDer) {
    public KeyMaterial {
        privateKeyDer = Objects.requireNonNull(privateKeyDer).clone();
        certificateDer = Objects.requireNonNull(certificateDer).clone();
    }

    @Override
    public byte[] privateKeyDer() {
        return privateKeyDer.clone();
    }

    @Override
    public byte[] certificateDer() {
        return certificateDer.clone();
    }
}
