package com.gabrielpacheco.pedidos.common.inbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, UUID> {

    /**
     * Insere o registro só se ele ainda não existir. Retorna 1 na primeira vez e 0 nas
     * repetições. ON CONFLICT DO NOTHING não aborta a transação, ao contrário de uma
     * violação de chave, e também resolve o caso de duas entregas chegando ao mesmo tempo.
     */
    @Modifying
    @Query(value = """
            insert into processed_messages (message_id, routing_key, processed_at)
            values (:id, :routingKey, :processedAt)
            on conflict (message_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("routingKey") String routingKey, @Param("processedAt") Instant processedAt);
}
