package com.gabrielpacheco.pedidos.inventory.domain;

public enum ReservationStatus {
    /** Itens separados, aguardando o pagamento. */
    RESERVED,
    /** Pagamento aprovado: itens baixados do estoque. */
    COMMITTED,
    /** Pagamento recusado: itens devolvidos ao estoque (compensação da saga). */
    RELEASED,
    /** Não havia estoque: nada foi separado. */
    REJECTED
}
