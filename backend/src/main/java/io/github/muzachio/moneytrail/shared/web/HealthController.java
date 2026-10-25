package io.github.muzachio.moneytrail.shared.web;

import io.github.muzachio.moneytrail.shared.api.SystemApi;
import io.github.muzachio.moneytrail.shared.api.model.Health;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
class HealthController implements SystemApi {

    @Override
    public ResponseEntity<Health> getHealth() {
        return ResponseEntity.ok(new Health(Health.StatusEnum.UP));
    }
}
