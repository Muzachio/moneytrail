package io.github.muzachio.moneytrail.ledger.internal;

import io.github.muzachio.moneytrail.ledger.Account;
import io.github.muzachio.moneytrail.ledger.AccountKind;
import io.github.muzachio.moneytrail.ledger.NewAccount;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "accounts")
class AccountEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @SuppressWarnings("NullAway.Init") // Hibernate assigns it on persist.
    private UUID id;

    private UUID ledgerId;

    @Enumerated(EnumType.STRING)
    private AccountKind kind;

    private @Nullable String subtype;

    private String name;

    // char(3) in the schema, which Hibernate checks at startup.
    @JdbcTypeCode(SqlTypes.CHAR)
    private Currency currency;

    private @Nullable Instant archivedAt;

    @SuppressWarnings("NullAway.Init") // For Hibernate, which sets the fields itself.
    protected AccountEntity() {}

    AccountEntity(NewAccount account) {
        this.ledgerId = account.ledgerId();
        this.kind = account.kind();
        this.subtype = account.subtype();
        this.name = account.name();
        this.currency = account.currency();
    }

    AccountKind kind() {
        return kind;
    }

    void rename(String name) {
        this.name = name;
    }

    // Archiving twice keeps the first date.
    void archive(Instant now) {
        if (archivedAt == null) {
            archivedAt = now;
        }
    }

    void restore() {
        archivedAt = null;
    }

    Account toAccount() {
        return new Account(id, ledgerId, kind, subtype, name, currency, archivedAt != null);
    }
}
