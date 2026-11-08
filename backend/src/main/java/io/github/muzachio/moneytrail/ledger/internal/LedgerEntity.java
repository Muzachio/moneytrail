package io.github.muzachio.moneytrail.ledger.internal;

import io.github.muzachio.moneytrail.ledger.Ledger;
import io.github.muzachio.moneytrail.ledger.LedgerOwner;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "ledgers")
class LedgerEntity {

    // Version 7 UUIDs are time-ordered, so new rows land at the end of the primary key index.
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @SuppressWarnings("NullAway.Init") // Hibernate assigns it on persist.
    private UUID id;

    @Enumerated(EnumType.STRING)
    private LedgerOwner.Type ownerType;

    private UUID ownerId;

    @SuppressWarnings("NullAway.Init") // For Hibernate, which sets the fields itself.
    protected LedgerEntity() {}

    LedgerEntity(LedgerOwner owner) {
        this.ownerType = owner.type();
        this.ownerId = owner.id();
    }

    Ledger toLedger() {
        return new Ledger(id, new LedgerOwner(ownerType, ownerId));
    }
}
