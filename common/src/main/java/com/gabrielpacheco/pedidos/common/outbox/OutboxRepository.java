package com.gabrielpacheco.pedidos.common.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Pega o evento pendente mais antigo e trava a linha. SKIP LOCKED faz com que várias
     * instâncias do serviço possam rodar o relay ao mesmo tempo sem publicar o mesmo evento
     * duas vezes: cada uma pula as linhas que outra já travou.
     */
    @Query(value = """
            select * from outbox_events
            where published_at is null
            order by id
            limit 1
            for update skip locked
            """, nativeQuery = true)
    Optional<OutboxEvent> lockNextPending();

    long countByPublishedAtIsNull();

    /** Registra a falha numa transação própria, para que ela sobreviva ao rollback da tentativa. */
    @Modifying
    @Query("update OutboxEvent e set e.attempts = e.attempts + 1, e.lastError = :error where e.id = :id")
    void recordFailure(@Param("id") Long id, @Param("error") String error);
}
