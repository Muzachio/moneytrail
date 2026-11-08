package io.github.muzachio.moneytrail.ledger.internal;

import java.util.UUID;
import org.springframework.data.repository.Repository;

interface Ledgers extends Repository<LedgerEntity, UUID> {

    LedgerEntity save(LedgerEntity ledger);

    boolean existsById(UUID id);
}
