package com.agrosphere.spatial.adapter.in.messaging;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import com.networknt.schema.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.agrosphere.spatial.domain.SpatialException;
import com.agrosphere.spatial.application.CorrelationContext;
@Component
public class EstateProjection {
    private final JdbcTemplate db;private final ObjectMapper json=new ObjectMapper();private final Map<String,JsonSchema> schemas=new HashMap<>();
    public EstateProjection(JdbcTemplate db){this.db=db;
        var factory=JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        for(String type:List.of("EstateCreated","EstateUpdated","PlantationBlockCreated","PlantationBlockUpdated")){
            try(var input=getClass().getResourceAsStream("/contracts/events/v2/"+type+".schema.json")){schemas.put(type,factory.getSchema(input,SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build()));}catch(Exception e){throw new IllegalStateException("Missing pinned estate contract",e);}
        }
    }
    @Transactional public void consume(String body){
        if(body.length()>262144)throw SpatialException.invalid("Estate event exceeds 256 KiB");
        final com.fasterxml.jackson.databind.JsonNode e;
        try{e=json.readTree(body);}catch(Exception ex){throw SpatialException.invalid("Invalid estate event JSON");}
        String type=e.path("eventType").asText();var schema=schemas.get(type);
        if(schema==null||!schema.validate(e).isEmpty())throw SpatialException.invalid("Estate event violates its supported v2 schema");
        final UUID event,estate,id,correlation;
        try{event=UUID.fromString(e.path("eventId").asText());estate=UUID.fromString(e.path("estateId").asText());id=UUID.fromString(e.path("entityId").asText());correlation=UUID.fromString(e.path("correlationId").asText());java.time.Instant.parse(e.path("occurredAt").asText());}catch(Exception ex){throw SpatialException.invalid("Invalid estate event identity or timestamp");}
        var payload=e.path("payload");boolean block=type.startsWith("PlantationBlock");
        if(!id.toString().equals(payload.path("id").asText())||(!block&&!id.equals(estate))||
           (block&&(!id.toString().equals(e.path("blockId").asText())||!estate.toString().equals(payload.path("estateId").asText()))))throw SpatialException.invalid("Envelope and snapshot identities conflict");
        long revision=payload.path("version").asLong();String status=payload.path("status").asText();
        UUID division=block?UUID.fromString(payload.path("divisionId").asText()):null;
        if(block&&(id.equals(estate)||division.equals(estate)||division.equals(id)))throw SpatialException.invalid("Invalid division reference");
        if(db.update("INSERT INTO processed_event(consumer_name,event_id) VALUES ('spatial-estate-v2',?) ON CONFLICT DO NOTHING",event)==0)return;
        CorrelationContext.set(correlation); org.slf4j.MDC.put("correlationId",correlation.toString());
        try{
            db.queryForList("SELECT id FROM reference_entity WHERE id=? AND entity_type='ESTATE' FOR UPDATE",UUID.class,estate);
            db.queryForList("SELECT pg_advisory_xact_lock(?)",(long)id.hashCode());
            var revisions=db.queryForList("SELECT revision FROM reference_entity WHERE id=? AND entity_type=?",Long.class,id,block?"BLOCK":"ESTATE");
            if(!revisions.isEmpty()&&revisions.getFirst()>=revision)return;
            if(block){
                // Divisions have no M1A lifecycle events: derive membership only from a validated block snapshot.
                var previous=db.queryForList("SELECT estate_id FROM reference_entity WHERE id=? AND entity_type='DIVISION'",UUID.class,division);
                if(!previous.isEmpty()&&!previous.getFirst().equals(estate))throw SpatialException.invalid("Division belongs to another estate");
                db.update("INSERT INTO reference_entity(id,estate_id,entity_type,parent_id,status,revision,inferred) VALUES (?,?,'DIVISION',?,'ACTIVE',0,true) ON CONFLICT(id,entity_type) DO NOTHING",division,estate,estate);
            }

            var previous=db.queryForList("SELECT estate_id FROM reference_entity WHERE id=? AND entity_type=?",UUID.class,id,block?"BLOCK":"ESTATE");
            if(!previous.isEmpty()&&!previous.getFirst().equals(estate))throw SpatialException.invalid("Entity estate is immutable");
            db.update("INSERT INTO reference_entity(id,estate_id,entity_type,parent_id,status,revision,inferred) VALUES (?,?,?,?,?,?,false) ON CONFLICT(id,entity_type) DO UPDATE SET parent_id=excluded.parent_id,status=excluded.status,revision=excluded.revision WHERE reference_entity.revision<excluded.revision",id,estate,block?"BLOCK":"ESTATE",division,status,revision);
            org.slf4j.LoggerFactory.getLogger(EstateProjection.class).info("Estate projection consumed eventId={} correlationId={}",event,correlation);
        }finally{org.slf4j.MDC.remove("correlationId");CorrelationContext.clear();}
    }
}
