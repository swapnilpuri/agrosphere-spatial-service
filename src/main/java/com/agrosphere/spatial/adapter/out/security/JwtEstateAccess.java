package com.agrosphere.spatial.adapter.out.security;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import com.agrosphere.spatial.application.port.out.EstateAccess;
@Component
public class JwtEstateAccess implements EstateAccess {
    private JwtAuthenticationToken token() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(!(auth instanceof JwtAuthenticationToken jwt) || !jwt.isAuthenticated()) throw new AccessDeniedException("JWT required");
        return jwt;
    }
    @Override public String actor(){return token().getName();}
    private boolean role(String... roles) { var jwt=token(); return jwt.getAuthorities().stream().anyMatch(a -> Arrays.stream(roles).anyMatch(r -> a.getAuthority().equals("ROLE_"+r))); }
    public void platformAdmin() { if(!role("PLATFORM_ADMIN")) throw new AccessDeniedException("Platform administrator required"); }
    public Set<UUID> readableEstates() {
        if(role("PLATFORM_ADMIN")) return null;
        if(!role("ESTATE_ADMIN","ESTATE_MANAGER","AGRONOMIST","VIEWER")) throw new AccessDeniedException("Estate role required");
        Object claim=token().getToken().getClaim("estate_ids");
        if(!(claim instanceof Collection<?> ids) || ids.size()>1000) throw new AccessDeniedException("Invalid estate scope");
        var result=new HashSet<UUID>();
        for(Object id:ids) { try { if(!(id instanceof String)) throw new IllegalArgumentException(); result.add(UUID.fromString((String)id)); } catch(IllegalArgumentException ex) { throw new AccessDeniedException("Invalid estate scope"); } }
        return Set.copyOf(result);
    }
    @Override public void read(UUID estateId) { var allowed=readableEstates(); if(allowed!=null && !allowed.contains(estateId)) throw new AccessDeniedException("Estate access denied"); }
    @Override public void write(UUID estateId) { if(!role("PLATFORM_ADMIN","ESTATE_ADMIN","ESTATE_MANAGER")) throw new AccessDeniedException("Write role required"); read(estateId); }
    public void cloneRead() { if(!role("PLATFORM_ADMIN","ESTATE_ADMIN","ESTATE_MANAGER","AGRONOMIST","VIEWER")) throw new AccessDeniedException("Estate role required"); }
}
