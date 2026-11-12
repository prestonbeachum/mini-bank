package dev.minibank.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.server.PathContainer;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

/**
 * Answers a known path with an undeclared method with 405 and an Allow header, before security
 * runs. Without this, the deny-by-default security rules turn every wrong method into 403.
 * The route table comes from Spring MVC's own mappings, so it cannot drift from the controllers.
 */
class MethodNotAllowedFilter extends OncePerRequestFilter {

    private record Route(PathPattern pattern, Set<RequestMethod> methods) {}

    private final List<RequestMappingInfoHandlerMapping> mappings;

    private volatile List<Route> routes;

    MethodNotAllowedFilter(List<RequestMappingInfoHandlerMapping> mappings) {
        this.mappings = mappings;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Set<String> allowed = allowedMethods(request);
        if (allowed.isEmpty() || allowed.contains(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(405);
        response.setHeader("Allow", String.join(", ", allowed));
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter()
                .write(
                        "{\"type\":\"https://minibank.dev/problems/method-not-allowed\",\"title\":\"Method not allowed\","
                                + "\"status\":405,\"detail\":\"Allowed: %s\"}".formatted(String.join(", ", allowed)));
    }

    /** Methods declared for this path, or empty when the path is unknown (left to 401/403/404). */
    private Set<String> allowedMethods(HttpServletRequest request) {
        String uri = request.getRequestURI().substring(request.getContextPath().length());
        PathContainer path = PathContainer.parsePath(uri);
        Set<String> allowed = new TreeSet<>();
        for (Route route : routes()) {
            if (route.pattern().matches(path)) {
                if (route.methods().isEmpty()) {
                    return Set.of(); // a mapping that accepts every method
                }
                route.methods().forEach(m -> allowed.add(m.name()));
            }
        }
        if (allowed.contains("GET")) {
            allowed.add("HEAD");
        }
        if (!allowed.isEmpty()) {
            allowed.add("OPTIONS");
        }
        return allowed;
    }

    private List<Route> routes() {
        List<Route> current = routes;
        if (current == null) {
            current = new ArrayList<>();
            for (RequestMappingInfoHandlerMapping mapping : mappings) {
                for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
                    if (info.getPathPatternsCondition() == null) {
                        continue;
                    }
                    for (PathPattern pattern : info.getPathPatternsCondition().getPatterns()) {
                        current.add(
                                new Route(pattern, info.getMethodsCondition().getMethods()));
                    }
                }
            }
            routes = current = List.copyOf(current);
        }
        return current;
    }

    @Configuration(proxyBeanMethods = false)
    static class Registration {

        @Bean
        FilterRegistrationBean<MethodNotAllowedFilter> methodNotAllowedFilter(
                List<RequestMappingInfoHandlerMapping> mappings) {
            var registration = new FilterRegistrationBean<>(new MethodNotAllowedFilter(mappings));
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }
    }
}
