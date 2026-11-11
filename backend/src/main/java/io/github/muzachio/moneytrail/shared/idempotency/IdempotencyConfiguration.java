package io.github.muzachio.moneytrail.shared.idempotency;

import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;

/**
 * Wraps every idempotent controller method in {@link IdempotentRequests}, through a Spring AOP
 * proxy, the way {@code @Transactional} methods are wrapped. A {@code HandlerInterceptor} cannot do
 * this: it runs before and after the handler but not around it, so it could neither hold one
 * transaction over the business writes and the stored response, nor commit it before the response
 * is written.
 */
@Configuration(proxyBeanMethods = false)
class IdempotencyConfiguration {

    /**
     * An infrastructure bean, like Spring's own advisors, so it is applied by whichever auto-proxy
     * creator the application has. Static, and resolving the interceptor only on the first call:
     * advisors are created before the other beans, and its dependencies (the database) must not be.
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor idempotentEndpointsAdvisor(ObjectProvider<IdempotentRequests> idempotentRequests) {
        MethodInterceptor interceptor =
                invocation -> idempotentRequests.getObject().invoke(invocation);
        return new DefaultPointcutAdvisor(new IdempotentEndpoints(), interceptor);
    }
}
