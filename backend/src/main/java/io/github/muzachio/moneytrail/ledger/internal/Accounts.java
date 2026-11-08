package io.github.muzachio.moneytrail.ledger.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

interface Accounts extends Repository<AccountEntity, UUID> {

    AccountEntity save(AccountEntity account);

    List<AccountEntity> findAllById(Iterable<UUID> ids);
}
