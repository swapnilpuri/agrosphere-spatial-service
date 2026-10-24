package com.agrosphere.spatial.adapter.out.messaging;
import java.util.*;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import com.agrosphere.spatial.application.CorrelationContext;
import com.agrosphere.spatial.application.port.out.EventOutbox;
@Component
public class JdbcEventOutbox implements EventOutbox {
    private final JdbcTemplate db;private final ObjectMapper json;
    public JdbcEventOutbox(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
    public void append(String type,UUID estate,UUID reference,Integer version,Map<String,Object> payload){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Outbox requires domain transaction");
        UUID id=UUID.randomUUID();var now=Instant.now();var envelope=new LinkedHashMap<String,Object>();
        envelope.put("eventId",id);envelope.put("eventType",type);envelope.put("eventVersion",1);envelope.put("occurredAt",now.toString());
        envelope.put("correlationId",CorrelationContext.current());envelope.put("sourceService","spatial-service");envelope.put("estateId",estate);
        if(reference!=null)envelope.put("referenceEntityId",reference);if(version!=null)envelope.put("geometryVersion",version);envelope.put("payload",payload);
        var transport=TraceTransport.capture();
        db.update("INSERT INTO outbox_event(event_id,aggregate_id,event_type,event_version,correlation_id,occurred_at,payload,trace_parent,trace_state) VALUES (?,?,?,1,?,?,?::jsonb,?,?)",id,estate,type,CorrelationContext.current(),java.sql.Timestamp.from(now),json.writeValueAsString(envelope),transport.get("traceparent"),transport.get("tracestate"));
    }
}
