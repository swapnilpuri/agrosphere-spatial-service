package com.agrosphere.spatial.adapter.out.messaging;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.slf4j.*;
@Component @ConditionalOnProperty(name="spatial.outbox.enabled",havingValue="true")
public class OutboxDispatcher {
    private static final Logger log=LoggerFactory.getLogger(OutboxDispatcher.class);
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final int maxAttempts, batchSize;
    private final long retrySeconds, confirmTimeout;
    public OutboxDispatcher(JdbcTemplate jdbc,RabbitTemplate rabbit,
        @Value("${spatial.outbox.max-attempts:10}") int maxAttempts,@Value("${spatial.outbox.batch-size:20}") int batchSize,
        @Value("${spatial.outbox.retry-seconds:5}") long retrySeconds,@Value("${spatial.outbox.confirm-timeout-seconds:5}") long confirmTimeout) {
        if(maxAttempts<1 || batchSize<1 || batchSize>100 || retrySeconds<1 || confirmTimeout<1) throw new IllegalArgumentException("Invalid outbox settings");
        this.jdbc=jdbc; this.rabbit=rabbit; this.maxAttempts=maxAttempts; this.batchSize=batchSize; this.retrySeconds=retrySeconds; this.confirmTimeout=confirmTimeout;
    }
    public record Pending(UUID id,String type,int version,String body,int attempts,String parent,String state) {
        public Pending(UUID id,String type,int version,String body,int attempts){this(id,type,version,body,attempts,null,null);}
    }
    @Scheduled(fixedDelayString="${spatial.outbox.poll-ms:1000}") @Transactional
    public void dispatch() {
        var batch=jdbc.query("SELECT event_id,event_type,event_version,payload::text,attempts,trace_parent,trace_state FROM outbox_event WHERE delivery_status='PENDING' AND next_attempt_at<=now() ORDER BY occurred_at,event_id LIMIT ? FOR UPDATE SKIP LOCKED",
            (rs,n)->new Pending(rs.getObject(1,UUID.class),rs.getString(2),rs.getInt(3),rs.getString(4),rs.getInt(5),rs.getString(6),rs.getString(7)),batchSize);
        for(var event:batch) {
            try(var traceScope=TraceTransport.restore(event.parent(),event.state())) {
                var properties=new MessageProperties(); properties.setContentType("application/json"); properties.setMessageId(event.id().toString()); properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                properties.setCorrelationId(new tools.jackson.databind.ObjectMapper().readTree(event.body()).path("correlationId").asText());
                var correlation=new CorrelationData(event.id().toString());
                rabbit.send("agrosphere.domain","agrosphere."+event.type()+".v"+event.version(),new Message(event.body().getBytes(java.nio.charset.StandardCharsets.UTF_8),properties),correlation);
                if(!correlation.getFuture().get(confirmTimeout,TimeUnit.SECONDS).ack() || correlation.getReturned()!=null) throw new IllegalStateException("Publish not confirmed or unroutable");
            } catch(Exception ex) {
                if(ex instanceof InterruptedException) Thread.currentThread().interrupt();
                int attempts=event.attempts()+1; long delay=retryDelaySeconds(attempts,retrySeconds);
                jdbc.update("UPDATE outbox_event SET attempts=?,delivery_status=?,next_attempt_at=? WHERE event_id=?",attempts,attempts>=maxAttempts?"FAILED":"PENDING",java.sql.Timestamp.from(Instant.now().plusSeconds(delay)),event.id());
                log.warn("Outbox delivery pending eventId={} attempt={} reason={}",event.id(),attempts,ex.getClass().getSimpleName());
                continue;
            }
            // Database failures must roll back; confirmation ambiguity intentionally permits redelivery.
            jdbc.update("UPDATE outbox_event SET attempts=attempts+1,delivery_status='PUBLISHED',published_at=now() WHERE event_id=?",event.id());
        }
    }
    public static long retryDelaySeconds(int attempt,long base) { return Math.min(3600,Math.multiplyExact(Math.min(base,3600),1L << Math.min(Math.max(attempt-1,0),10))); }
}
