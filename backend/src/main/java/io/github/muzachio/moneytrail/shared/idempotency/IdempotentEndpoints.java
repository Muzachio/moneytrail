package io.github.muzachio.moneytrail.shared.idempotency;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.aop.support.annotation.AnnotationClassFilter;
import org.springframework.core.annotation.AnnotatedMethod;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * Which endpoints are idempotent: the controller methods that take the {@code Idempotency-Key}
 * header. The OpenAPI spec decides it, since an operation that references
 * {@code #/components/parameters/IdempotencyKey} gets that parameter in its generated interface. So
 * a money-moving endpoint cannot declare the header and forget the protection, or the other way
 * round.
 */
final class IdempotentEndpoints extends StaticMethodMatcherPointcut {

    static final String HEADER = "Idempotency-Key";

    private static final Map<Method, Boolean> takesTheHeader = new ConcurrentHashMap<>();

    IdempotentEndpoints() {
        setClassFilter(new AnnotationClassFilter(Controller.class, true));
    }

    @Override
    public boolean matches(Method method, Class<?> targetClass) {
        return takesTheHeader(AopUtils.getMostSpecificMethod(method, targetClass));
    }

    /**
     * Looks at the method's parameters together with those of the interface method it implements,
     * where the generated {@code @RequestHeader} annotations are. The proxy asks about every method
     * of the class, {@code Object}'s included, which Java does not let us inspect and which are
     * never endpoints.
     */
    static boolean takesTheHeader(Method method) {
        if (method.getParameterCount() == 0 || method.getDeclaringClass() == Object.class) {
            return false;
        }
        return takesTheHeader.computeIfAbsent(
                method,
                handler -> Arrays.stream(new AnnotatedMethod(handler).getMethodParameters())
                        .map(parameter -> parameter.getParameterAnnotation(RequestHeader.class))
                        .filter(Objects::nonNull)
                        .anyMatch(header -> HEADER.equalsIgnoreCase(header.name())));
    }
}
