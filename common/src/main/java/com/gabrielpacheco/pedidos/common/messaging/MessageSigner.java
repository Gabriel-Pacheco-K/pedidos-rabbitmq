package com.gabrielpacheco.pedidos.common.messaging;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;

/**
 * Assina e verifica mensagens com Ed25519 (assinatura de chave pública).
 *
 * <p>Cada serviço tem só a própria chave privada, usada para assinar o que publica, e as
 * chaves públicas dos produtores que consome, usadas só para verificar. Com uma chave
 * pública não se assina nada: um serviço invadido não consegue se passar pelos outros.
 *
 * <p>A assinatura cobre o produtor, o id, o tipo, o horário de envio e o corpo. Cada campo
 * vai prefixado pelo seu tamanho, para que não dê para deslocar bytes de um campo para
 * outro mantendo a mesma assinatura.
 */
public class MessageSigner {

    private static final String ALGORITHM = "Ed25519";

    private final Producer self;
    private final PrivateKey privateKey;
    private final Map<Producer, PublicKey> publicKeys = new EnumMap<>(Producer.class);

    /**
     * @param self              produtor que este serviço representa
     * @param privateKeyBase64  chave privada própria (PKCS#8, Base64); pode ser nula em quem só verifica
     * @param publicKeysBase64  chaves públicas (X.509, Base64) por {@link Producer#key()}
     */
    public MessageSigner(Producer self, String privateKeyBase64, Map<String, String> publicKeysBase64) {
        this.self = self;
        try {
            KeyFactory factory = KeyFactory.getInstance(ALGORITHM);
            this.privateKey = privateKeyBase64 == null || privateKeyBase64.isBlank()
                    ? null
                    : factory.generatePrivate(new PKCS8EncodedKeySpec(decode(privateKeyBase64, "chave privada")));
            for (Producer producer : Producer.values()) {
                String encoded = publicKeysBase64.get(producer.key());
                if (encoded != null && !encoded.isBlank()) {
                    publicKeys.put(producer, factory.generatePublic(new X509EncodedKeySpec(decode(encoded, "chave pública de " + producer.key()))));
                }
            }
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Chave de assinatura inválida: " + ex.getMessage(), ex);
        }
    }

    public Producer self() {
        return self;
    }

    public String sign(String messageId, String routingKey, long timestamp, byte[] body) {
        if (privateKey == null) {
            throw new IllegalStateException("Este serviço não tem chave privada para assinar.");
        }
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initSign(privateKey);
            signature.update(canonical(self, messageId, routingKey, timestamp, body));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Falha ao assinar mensagem", ex);
        }
    }

    public boolean verify(Producer producer, String messageId, String routingKey, long timestamp, byte[] body, String signatureBase64) {
        PublicKey key = publicKeys.get(producer);
        if (key == null || signatureBase64 == null) {
            return false;
        }
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initVerify(key);
            signature.update(canonical(producer, messageId, routingKey, timestamp, body));
            return signature.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (IllegalArgumentException | GeneralSecurityException ex) {
            return false;
        }
    }

    private static byte[] canonical(Producer producer, String messageId, String routingKey, long timestamp, byte[] body) {
        byte[] p = producer.key().getBytes(StandardCharsets.UTF_8);
        byte[] id = messageId.getBytes(StandardCharsets.UTF_8);
        byte[] rk = routingKey.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(4 * Integer.BYTES + Long.BYTES + p.length + id.length + rk.length + body.length)
                .putInt(p.length).put(p)
                .putInt(id.length).put(id)
                .putInt(rk.length).put(rk)
                .putLong(timestamp)
                .putInt(body.length).put(body)
                .array();
    }

    private static byte[] decode(String base64, String what) {
        try {
            return Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Base64 inválido na " + what, ex);
        }
    }
}
