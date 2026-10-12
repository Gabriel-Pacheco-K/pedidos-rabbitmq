package com.gabrielpacheco.pedidos.common.config;

import com.gabrielpacheco.pedidos.common.messaging.Producer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Map;

/**
 * @param exchange       exchange de eventos (topic)
 * @param self           qual produtor este serviço é
 * @param privateKey     chave privada Ed25519 deste serviço (PKCS#8, Base64), usada para assinar
 * @param publicKeys     chaves públicas Ed25519 dos produtores consumidos, por {@link Producer#key()}
 * @param maxMessageAge  idade máxima de uma mensagem; reenvios dentro da janela já são barrados pelo inbox (id repetido)
 * @param relayInterval  intervalo do relay do outbox, em milissegundos
 */
@Validated
@ConfigurationProperties(prefix = "app.messaging")
public record MessagingProperties(
        @NotBlank String exchange,
        @NotNull Producer self,
        @NotBlank String privateKey,
        @NotNull Map<String, String> publicKeys,
        @NotNull Duration maxMessageAge,
        @NotNull Duration relayInterval) {
}
