package io.github.muzachio.moneytrail.ledger.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

interface Accounts extends Repository<AccountEntity, UUID> {

    // Flushed at once: its account_balances row, written with plain SQL, references it.
    AccountEntity saveAndFlush(AccountEntity account);

    List<AccountEntity> findAllById(Iterable<UUID> ids);
}
