package io.github.muzachio.moneytrail.ledger;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Invented users: every journal entry records who created it, and that must be a real user. */
final class LedgerTestUsers {

    private LedgerTestUsers() {}

    static UUID create(JdbcClient jdbc) {
        return jdbc.sql("INSERT INTO users (email, name) VALUES (:email, 'Ledger Test') RETURNING id")
                .param("email", "ledger-" + UUID.randomUUID() + "@example.com")
                .query(UUID.class)
                .single();
    }
}
