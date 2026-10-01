package taskj1.crypto;

@FunctionalInterface
public interface KeyMaterialGenerator {
    KeyMaterial generate(String name) throws Exception;
}
