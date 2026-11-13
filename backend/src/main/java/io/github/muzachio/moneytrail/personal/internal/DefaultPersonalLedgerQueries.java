package io.github.muzachio.moneytrail.personal.internal;

import static java.util.stream.Collectors.toMap;

import io.github.muzachio.moneytrail.ledger.AccountKind;
import io.github.muzachio.moneytrail.ledger.Ledger;
import io.github.muzachio.moneytrail.ledger.LedgerOwner;
import io.github.muzachio.moneytrail.ledger.LedgerService;
import io.github.muzachio.moneytrail.personal.CategoryLabel;
import io.github.muzachio.moneytrail.personal.PersonalLedgerQueries;
import java.util.Currency;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class DefaultPersonalLedgerQueries implements PersonalLedgerQueries {

    private final LedgerService ledger;

    DefaultPersonalLedgerQueries(LedgerService ledger) {
        this.ledger = ledger;
    }

    @Override
    public Optional<UUID> ledgerOf(UUID userId) {
        return ledger.findLedger(LedgerOwner.user(userId)).map(Ledger::id);
    }

    @Override
    public Currency currency() {
        return PersonalLedgers.CURRENCY;
    }

    /** {@link UserCategory#of} decides the label, so it is the same as the categories endpoint's. */
    @Override
    public Map<UUID, CategoryLabel> categoryLabels(UUID ledgerId) {
        return ledger.accounts(ledgerId).stream()
                .filter(account -> account.kind() == AccountKind.EXPENSE || account.kind() == AccountKind.INCOME)
                .map(UserCategory::of)
                .collect(toMap(UserCategory::id, category -> new CategoryLabel(category.key(), category.name())));
    }
}
