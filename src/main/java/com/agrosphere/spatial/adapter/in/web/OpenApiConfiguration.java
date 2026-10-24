package com.agrosphere.spatial.adapter.in.web;
import org.springframework.context.annotation.*;
import org.springdoc.core.customizers.OpenApiCustomizer;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.core.util.Json31;
@Configuration
public class OpenApiConfiguration {
    @Bean OpenApiCustomizer publishedContract(){
        final OpenAPI contract;
        try(var input=getClass().getResourceAsStream("/contracts/spatial-service.openapi.json")){contract=Json31.mapper().readValue(input,OpenAPI.class);}catch(Exception e){throw new IllegalStateException("Missing pinned spatial OpenAPI contract",e);}
        return generated->{generated.setInfo(contract.getInfo());generated.setPaths(contract.getPaths());generated.setComponents(contract.getComponents());generated.setSecurity(contract.getSecurity());generated.setServers(contract.getServers());};
    }
}
