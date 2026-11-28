package io.github.muzachio.moneytrail.personal.internal;

import static java.util.stream.Collectors.toMap;

import io.github.muzachio.moneytrail.ledger.Account;
import io.github.muzachio.moneytrail.ledger.Ledger;
import io.github.muzachio.moneytrail.ledger.LedgerOwner;
import io.github.muzachio.moneytrail.ledger.LedgerService;
import io.github.muzachio.moneytrail.personal.AccountUse;
import io.github.muzachio.moneytrail.personal.CategoryLabel;
import io.github.muzachio.moneytrail.personal.PersonalLedgerQueries;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class DefaultPersonalLedgerQueries implements PersonalLedgerQueries {

    private final LedgerService ledger;
    private final Categories categories;

    DefaultPersonalLedgerQueries(LedgerService ledger, Categories categories) {
        this.ledger = ledger;
        this.categories = categories;
    }

    @Override
    public Optional<UUID> ledgerOf(UUID userId) {
        return ledger.findLedger(LedgerOwner.user(userId)).map(Ledger::id);
    }

    @Override
    public Currency currency() {
        return PersonalLedgers.CURRENCY;
    }

    /** {@link Categories#inLedger} decides the label, so it is the same as the categories endpoint's. */
    @Override
    public Map<UUID, CategoryLabel> categoryLabels(UUID ledgerId) {
        return categories.inLedger(ledgerId).stream().collect(toMap(UserCategory::id, category -> {
            KnownName key = category.key();
            return new CategoryLabel(
                    key == null ? null : key.key(),
                    category.name(),
                    category.icon().value());
        }));
    }

    /** {@link UserAccount.Type#of} decides which accounts are the user's, as for the accounts endpoint. */
    @Override
    public Map<UUID, AccountUse> accountUses(UUID ledgerId) {
        Map<UUID, AccountUse> uses = new HashMap<>();
        for (Account account : ledger.accounts(ledgerId)) {
            UserAccount.Type.of(account).ifPresent(type -> uses.put(account.id(), type.use()));
        }
        return uses;
    }
}
