package io.github.muzachio.moneytrail.ledger.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

interface Accounts extends Repository<AccountEntity, UUID> {

    // Flushed at once: its account_balances row, written with plain SQL, references it, and a name
    // that is taken must fail here, where DefaultLedgerService can tell the caller why.
    AccountEntity saveAndFlush(AccountEntity account);

    Optional<AccountEntity> findById(UUID id);

    List<AccountEntity> findAllById(Iterable<UUID> ids);

    List<AccountEntity> findByLedgerIdOrderById(UUID ledgerId);
}
