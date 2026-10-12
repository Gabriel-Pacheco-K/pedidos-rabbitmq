package com.gabrielpacheco.pedidos.order.service;

public final class OrderExceptions {

    private OrderExceptions() {
    }

    public static class NotFound extends RuntimeException {
        public NotFound() {
            super("Pedido não encontrado.");
        }
    }

    public static class IdempotencyConflict extends RuntimeException {
        public IdempotencyConflict() {
            super("Esta chave de idempotência já foi usada com um pedido diferente.");
        }
    }

    public static class InvalidOrder extends RuntimeException {
        public InvalidOrder(String message) {
            super(message);
        }
    }
}
