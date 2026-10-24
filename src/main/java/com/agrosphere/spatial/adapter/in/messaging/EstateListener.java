package com.agrosphere.spatial.adapter.in.messaging;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.core.Message;
@Component @ConditionalOnProperty(name="spatial.consumer.enabled",havingValue="true")
public class EstateListener {
    private final EstateProjection projection;
    public EstateListener(EstateProjection projection){this.projection=projection;}
    @RabbitListener(queues="spatial.estate.v2") public void receive(Message message){
        String key=message.getMessageProperties().getReceivedRoutingKey();
        String body=new String(message.getBody(),StandardCharsets.UTF_8);
        String type;
        try{type=new tools.jackson.databind.ObjectMapper().readTree(body).path("eventType").asText();}catch(Exception ex){throw new IllegalArgumentException("Invalid event JSON");}
        if(!("agrosphere."+type+".v2").equals(key))throw new IllegalArgumentException("Routing key and estate event type conflict");
        projection.consume(body);
    }
}
