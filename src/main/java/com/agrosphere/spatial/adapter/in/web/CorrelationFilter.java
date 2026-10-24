package com.agrosphere.spatial.adapter.in.web;
import java.io.IOException;
import java.util.UUID;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.MDC;
import com.agrosphere.spatial.application.CorrelationContext;
@Component @Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {
    public static UUID current() { return CorrelationContext.current(); }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        UUID id;
        try { id=UUID.fromString(request.getHeader("X-Correlation-ID")); } catch(Exception ex) { id=UUID.randomUUID(); }
        CorrelationContext.set(id); MDC.put("correlationId",id.toString()); response.setHeader("X-Correlation-ID",id.toString());
        try { chain.doFilter(request,response); } finally { MDC.remove("correlationId"); CorrelationContext.clear(); }
    }
}
