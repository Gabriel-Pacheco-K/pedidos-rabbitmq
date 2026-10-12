package com.gabrielpacheco.pedidos.common.messaging;

/**
 * Mensagem que nunca vai ser válida: assinatura errada, tipo desconhecido, JSON malformado.
 * Não adianta tentar de novo, então ela vai direto para a fila de mensagens mortas (DLQ).
 */
public class InvalidMessageException extends RuntimeException {

    public InvalidMessageException(String message) {
        super(message);
    }

    public InvalidMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
