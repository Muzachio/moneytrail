package io.github.muzachio.moneytrail.ledger;

import io.github.muzachio.moneytrail.shared.Money;
import java.util.UUID;

/** One line of a journal entry: a debit when the amount is positive, a credit when negative. */
public record Posting(UUID accountId, Money amount) {}
