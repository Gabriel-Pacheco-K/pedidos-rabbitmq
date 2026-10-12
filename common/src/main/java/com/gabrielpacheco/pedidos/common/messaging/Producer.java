package com.gabrielpacheco.pedidos.common.messaging;

/**
 * Serviços que publicam eventos. Cada um assina as mensagens com a própria chave.
 */
public enum Producer {
    ORDER,
    INVENTORY,
    PAYMENT;

    public String key() {
        return name().toLowerCase();
    }
}
