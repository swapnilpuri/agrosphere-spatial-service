package com.agrosphere.spatial.application;
import java.util.UUID;
public final class CorrelationContext {
    private static final ThreadLocal<UUID> CURRENT=new ThreadLocal<>();
    private CorrelationContext() {}
    public static UUID current() { UUID id=CURRENT.get(); return id==null?UUID.randomUUID():id; }
    public static void set(UUID id) { CURRENT.set(id); }
    public static void clear() { CURRENT.remove(); }
}
