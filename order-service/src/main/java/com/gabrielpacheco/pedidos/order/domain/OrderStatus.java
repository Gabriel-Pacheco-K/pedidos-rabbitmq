package com.gabrielpacheco.pedidos.order.domain;

/**
 * PENDING é o único estado de onde se sai: o pedido termina confirmado, cancelado (pagamento
 * recusado) ou rejeitado (sem estoque). Estados finais não mudam mais.
 */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    CANCELLED,
    REJECTED;

    public boolean canChangeTo(OrderStatus target) {
        return this == PENDING && target != PENDING;
    }
}
