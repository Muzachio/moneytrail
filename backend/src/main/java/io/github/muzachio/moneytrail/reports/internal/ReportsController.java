package io.github.muzachio.moneytrail.reports.internal;

import io.github.muzachio.moneytrail.identity.SignedIn;
import io.github.muzachio.moneytrail.personal.CategoryLabel;
import io.github.muzachio.moneytrail.shared.Money;
import io.github.muzachio.moneytrail.shared.api.ReportsApi;
import io.github.muzachio.moneytrail.shared.api.model.CategorySpending;
import io.github.muzachio.moneytrail.shared.api.model.MonthTotals;
import io.github.muzachio.moneytrail.shared.api.model.ReportGrouping;
import io.github.muzachio.moneytrail.shared.api.model.SpendingReport;
import java.time.LocalDate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ReportsController implements ReportsApi {

    private final SpendingReports reports;

    ReportsController(SpendingReports reports) {
        this.reports = reports;
    }

    /**
     * The schema has already checked that both days and the grouping are there and well formed;
     * {@link ReportPeriod} checks that they make a period.
     */
    @Override
    public ResponseEntity<SpendingReport> getSpendingReport(LocalDate from, LocalDate to, ReportGrouping groupBy) {
        ReportPeriod period = new ReportPeriod(from, to);
        return ResponseEntity.ok(
                switch (groupBy) {
                    case CATEGORY -> toApi(reports.byCategory(SignedIn.userId(), period));
                    case MONTH -> toApi(reports.byMonth(SignedIn.userId(), period));
                });
    }

    private static SpendingReport toApi(SpendingByCategory report) {
        return new SpendingReport(toApi(report.income()), toApi(report.spending()))
                .categories(report.categories().stream()
                        .map(ReportsController::toApi)
                        .toList());
    }

    private static SpendingReport toApi(SpendingByMonth report) {
        return new SpendingReport(toApi(report.income()), toApi(report.spending()))
                .months(report.months().stream().map(ReportsController::toApi).toList());
    }

    private static CategorySpending toApi(SpendingByCategory.CategorySpending category) {
        CategorySpending body = new CategorySpending(category.categoryId(), toApi(category.spending()));
        CategoryLabel label = category.label();
        if (label.key() != null) {
            body.key(label.key());
        }
        if (label.name() != null) {
            body.name(label.name());
        }
        return body;
    }

    private static MonthTotals toApi(SpendingByMonth.MonthTotals month) {
        return new MonthTotals(month.month().toString(), toApi(month.income()), toApi(month.spending()));
    }

    private static io.github.muzachio.moneytrail.shared.api.model.Money toApi(Money money) {
        return new io.github.muzachio.moneytrail.shared.api.model.Money(
                money.minor(), money.currency().getCurrencyCode());
    }
}
