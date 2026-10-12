package com.gabrielpacheco.pedidos.common.test;

import com.gabrielpacheco.pedidos.common.messaging.MessageSigner;
import com.gabrielpacheco.pedidos.common.messaging.Producer;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/** Pares de chaves Ed25519 gerados a cada execução de testes: nenhuma chave fica no repositório. */
public final class TestKeys {

    private static final Map<Producer, String> PRIVATE = new EnumMap<>(Producer.class);
    private static final Map<String, String> PUBLIC = new HashMap<>();
    private static final Map<Producer, MessageSigner> SIGNERS = new EnumMap<>(Producer.class);

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            for (Producer producer : Producer.values()) {
                KeyPair pair = generator.generateKeyPair();
                PRIVATE.put(producer, Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
                PUBLIC.put(producer.key(), Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
            }
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
        for (Producer producer : Producer.values()) {
            SIGNERS.put(producer, new MessageSigner(producer, PRIVATE.get(producer), PUBLIC));
        }
    }

    private TestKeys() {
    }

    public static String privateKey(Producer producer) {
        return PRIVATE.get(producer);
    }

    public static Map<String, String> publicKeys() {
        return Map.copyOf(PUBLIC);
    }

    /** Assinador com a chave privada do produtor indicado (simula aquele serviço). */
    public static MessageSigner signer(Producer producer) {
        return SIGNERS.get(producer);
    }

    /** Verificador que só tem as chaves públicas, como um consumidor real. */
    public static MessageSigner verifier(Producer self) {
        return new MessageSigner(self, null, PUBLIC);
    }
}
