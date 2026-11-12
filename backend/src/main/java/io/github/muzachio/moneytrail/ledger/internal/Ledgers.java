package io.github.muzachio.moneytrail.ledger.internal;

import io.github.muzachio.moneytrail.ledger.LedgerOwner;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

interface Ledgers extends Repository<LedgerEntity, UUID> {

    LedgerEntity save(LedgerEntity ledger);

    boolean existsById(UUID id);

    Optional<LedgerEntity> findByOwnerTypeAndOwnerId(LedgerOwner.Type ownerType, UUID ownerId);
}
