package com.gabrielpacheco.pedidos.payment.messaging;

import com.gabrielpacheco.pedidos.common.events.PaymentApproved;
import com.gabrielpacheco.pedidos.common.events.PaymentDeclined;
import com.gabrielpacheco.pedidos.common.events.StockReserved;
import com.gabrielpacheco.pedidos.common.inbox.Inbox;
import com.gabrielpacheco.pedidos.common.messaging.IncomingEvent;
import com.gabrielpacheco.pedidos.common.outbox.OutboxWriter;
import com.gabrielpacheco.pedidos.payment.domain.Payment;
import com.gabrielpacheco.pedidos.payment.domain.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;

/**
 * Simula a autorização de um pagamento. Num sistema real, aqui entraria a chamada a um
 * gateway (com chave de idempotência = id do pedido, para o gateway também não cobrar duas vezes).
 */
@Component
public class PaymentEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventHandler.class);

    private final Inbox inbox;
    private final PaymentRepository payments;
    private final OutboxWriter outbox;
    private final BigDecimal approvalLimit;
    private final Clock clock;

    public PaymentEventHandler(Inbox inbox, PaymentRepository payments, OutboxWriter outbox,
                               @Value("${app.payment.approval-limit}") BigDecimal approvalLimit, Clock clock) {
        this.inbox = inbox;
        this.payments = payments;
        this.outbox = outbox;
        this.approvalLimit = approvalLimit;
        this.clock = clock;
    }

    @Transactional
    public void handle(IncomingEvent event) {
        if (!inbox.firstDelivery(event)) {
            log.info("Mensagem {} já processada; ignorando a duplicata", event.messageId());
            return;
        }
        StockReserved reserved = event.payload(StockReserved.class);
        if (payments.existsById(reserved.orderId())) {
            log.warn("Pedido {} já tem pagamento; nada a fazer", reserved.orderId());
            return;
        }

        if (reserved.amount().compareTo(approvalLimit) <= 0) {
            payments.save(new Payment(reserved.orderId(), reserved.customerId(), reserved.amount(), Payment.Status.APPROVED, null, clock.instant()));
            outbox.append(new PaymentApproved(reserved.orderId(), reserved.amount()));
        } else {
            String reason = "Valor acima do limite de aprovação automática";
            payments.save(new Payment(reserved.orderId(), reserved.customerId(), reserved.amount(), Payment.Status.DECLINED, reason, clock.instant()));
            outbox.append(new PaymentDeclined(reserved.orderId(), reason));
        }
    }
}
