/**
 * Read-side queries for the dashboard: the sums of a user's income and spending by category and by
 * month. It reads the ledger's tables with SQL and never writes them (ADR-0011).
 */
@ApplicationModule(displayName = "Reports")
@NullMarked
package io.github.muzachio.moneytrail.reports;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
