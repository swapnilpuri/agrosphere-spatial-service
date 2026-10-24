package com.agrosphere.spatial.adapter.out.messaging;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.amqp.core.*;
@Configuration @EnableScheduling
public class MessagingConfiguration {
    @Bean @ConditionalOnProperty(name="spatial.outbox.enabled",havingValue="true") TopicExchange publicationExchange(){return new TopicExchange("agrosphere.domain",true,false);}
    @Bean @ConditionalOnProperty(name="spatial.consumer.enabled",havingValue="true") Declarables consumerTopology(){
        var domain=new TopicExchange("agrosphere.domain",true,false);var dlx=new DirectExchange("spatial.dead",true,false);
        var dead=new Queue("spatial.estate.v2.dead",true);var queue=QueueBuilder.durable("spatial.estate.v2").deadLetterExchange("spatial.dead").deadLetterRoutingKey("estate.v2").build();
        return new Declarables(domain,dlx,dead,queue,BindingBuilder.bind(dead).to(dlx).with("estate.v2"),
            BindingBuilder.bind(queue).to(domain).with("agrosphere.EstateCreated.v2"),BindingBuilder.bind(queue).to(domain).with("agrosphere.EstateUpdated.v2"),
            BindingBuilder.bind(queue).to(domain).with("agrosphere.PlantationBlockCreated.v2"),BindingBuilder.bind(queue).to(domain).with("agrosphere.PlantationBlockUpdated.v2"));
    }
}
