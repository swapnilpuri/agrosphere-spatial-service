package com.agrosphere.spatial;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import com.agrosphere.spatial.adapter.in.web.SecurityProblem;
@Configuration
public class SecurityConfiguration {
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Object realm = jwt.getClaim("realm_access");
            if (!(realm instanceof Map<?,?> map) || !(map.get("roles") instanceof Collection<?> roles)) return List.of();
            return roles.stream().filter(String.class::isInstance).map(Object::toString)
                .filter(Set.of("PLATFORM_ADMIN","ESTATE_ADMIN","ESTATE_MANAGER","AGRONOMIST","VIEWER")::contains)
                .map(r -> (org.springframework.security.core.GrantedAuthority)new SimpleGrantedAuthority("ROLE_"+r)).toList();
        });
        return http.csrf(csrf -> csrf.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health", "/actuator/health/**").permitAll().anyRequest().authenticated())
            .exceptionHandling(e -> e.authenticationEntryPoint((r,s,x)->SecurityProblem.write(r,s,401)).accessDeniedHandler((r,s,x)->SecurityProblem.write(r,s,403)))
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                .authenticationEntryPoint((r,s,x)->SecurityProblem.write(r,s,401)).accessDeniedHandler((r,s,x)->SecurityProblem.write(r,s,403))).build();
    }
}
