package com.agrosphere.spatial;
import java.util.*;
import java.time.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import com.sun.net.httpserver.HttpServer;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import tools.jackson.databind.*;
import com.agrosphere.spatial.adapter.out.messaging.OutboxDispatcher;
import com.agrosphere.spatial.application.*;
import com.agrosphere.spatial.application.port.out.*;
import com.agrosphere.spatial.domain.*;
import com.agrosphere.spatial.adapter.in.messaging.EstateProjection;
import org.testcontainers.utility.DockerImageName;
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SpatialServiceIT {
    static PostgreSQLContainer postgres;
    static GenericContainer<?> broker;
    static HttpServer oidc;
    static RSAKey key;
    static String issuer,base;
    static ConfigurableApplicationContext context;
    static ObjectMapper json;
    static JdbcTemplate jdbc;
    static UUID estateId=UUID.fromString("00000000-0000-0000-0000-000000000001"),divisionId=UUID.fromString("00000000-0000-0000-0000-000000000002"),blockId=UUID.fromString("00000000-0000-0000-0000-000000000003");
    static String featureId;
    static HttpClient http=HttpClient.newHttpClient();
    @BeforeAll static void start() throws Exception {
        postgres=new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:17-3.6").asCompatibleSubstituteFor("postgres")).withInitScript("postgis.sql");postgres.start();
        broker=new GenericContainer<>("rabbitmq:4.3.6-management").withExposedPorts(5672)
            .withEnv("RABBITMQ_DEFAULT_USER","integration").withEnv("RABBITMQ_DEFAULT_PASS","integration-test-only");broker.start();
        key=new RSAKeyGenerator(2048).keyID("integration-key").generate();
        oidc=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);issuer="http://127.0.0.1:"+oidc.getAddress().getPort();
        oidc.createContext("/.well-known/openid-configuration",exchange->{byte[] body=("{\"issuer\":\""+issuer+"\",\"jwks_uri\":\""+issuer+"/jwks\",\"id_token_signing_alg_values_supported\":[\"RS256\"]}").getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});
        oidc.createContext("/jwks",exchange->{byte[] body=new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});oidc.start();
        context=SpringApplication.run(SpatialServiceApplication.class,
            "--server.port=0","--spring.datasource.url="+postgres.getJdbcUrl(),"--spring.datasource.username="+postgres.getUsername(),"--spring.datasource.password="+postgres.getPassword(),
            "--spring.security.oauth2.resourceserver.jwt.issuer-uri="+issuer,"--spatial.security.audience=spatial-service","--spatial.outbox.enabled=false","--spatial.consumer.enabled=true","--management.otlp.metrics.export.enabled=false","--management.tracing.export.otlp.enabled=false",
            "--spring.rabbitmq.host="+broker.getHost(),"--spring.rabbitmq.port="+broker.getMappedPort(5672),"--spring.rabbitmq.username=integration","--spring.rabbitmq.password=integration-test-only");
        base="http://localhost:"+((WebServerApplicationContext)context).getWebServer().getPort();json=context.getBean(ObjectMapper.class);jdbc=context.getBean(JdbcTemplate.class);
    }
    @AfterAll static void stop() { if(context!=null)context.close();if(oidc!=null)oidc.stop(0);if(broker!=null)broker.stop();if(postgres!=null)postgres.stop(); }
    static String token(String role,List<String> estates,String audience,Instant expires) throws Exception { return token(role,estates,audience,expires,issuer); }
    static String token(String role,List<String> estates,String audience,Instant expires,String tokenIssuer) throws Exception {
        var claims=new JWTClaimsSet.Builder().issuer(tokenIssuer).subject("integration-user").audience(audience).issueTime(Date.from(Instant.now().minusSeconds(5))).expirationTime(Date.from(expires))
            .claim("realm_access",Map.of("roles",List.of(role))).claim("estate_ids",estates).build();
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),claims);jwt.sign(new RSASSASigner(key));return jwt.serialize();
    }
    static String admin() throws Exception { return token("PLATFORM_ADMIN",List.of(),"spatial-service",Instant.now().plusSeconds(300)); }
    static HttpResponse<String> request(String method,String uri,Object body,String token) throws Exception {
        var r=HttpRequest.newBuilder(URI.create(base+uri)).header("Content-Type","application/json").header("X-Correlation-ID","00000000-0000-0000-0000-000000000099");
        if(token!=null)r.header("Authorization","Bearer "+token);
        return http.send(r.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    static JsonNode body(HttpResponse<String> response,int status) { assertEquals(status,response.statusCode(),response.body());return json.readTree(response.body()); }
    @Test @Order(1) void migrationsAndProjection() throws Exception {
        assertEquals(3,jdbc.queryForObject("select count(*) from flyway_schema_history where success",Integer.class));
        assertTrue(jdbc.queryForObject("select count(*) from pg_indexes where indexdef like '%USING gist%'",Integer.class)>=3);
        var projection=context.getBean(EstateProjection.class);
        var events=json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("examples/synthetic-estate-events.json")));
        // Block arrives before estate; references must converge without cross-service queries.
        projection.consume(events.get(1).toString());projection.consume(events.get(0).toString());
        for(var event:events)projection.consume(event.toString());
        int count=jdbc.queryForObject("select count(*) from processed_event",Integer.class);for(var event:events)projection.consume(event.toString());assertEquals(count,jdbc.queryForObject("select count(*) from processed_event",Integer.class));
        var newest=events.get(1).deepCopy();((tools.jackson.databind.node.ObjectNode)newest).put("eventId",UUID.randomUUID().toString());((tools.jackson.databind.node.ObjectNode)newest).put("eventType","PlantationBlockUpdated");((tools.jackson.databind.node.ObjectNode)newest.path("payload")).put("version",3);
        projection.consume(newest.toString());var stale=events.get(1).deepCopy();((tools.jackson.databind.node.ObjectNode)stale).put("eventId",UUID.randomUUID().toString());projection.consume(stale.toString());assertEquals(3,jdbc.queryForObject("select revision from reference_entity where id=?",Integer.class,blockId));
        var malformed=newest.deepCopy();((tools.jackson.databind.node.ObjectNode)malformed).put("eventVersion",1);assertThrows(SpatialException.class,()->projection.consume(malformed.toString()));
    }
    @Test @Order(2) void sampleImportAndGeographicArea() throws Exception {
        var collection=json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("examples/synthetic-estate.geojson")));
        var job=body(request("POST","/api/v1/spatial/imports/geojson",Map.of("estateId",estateId,"sourceFilename","synthetic-estate.geojson","geojson",collection),admin()),202);
        assertEquals("COMPLETED",job.path("status").asText(),job.toString());assertEquals(13,job.path("importedCount").asInt());
        var block=body(request("GET","/api/v1/spatial/blocks/"+blockId+"/geometry",null,admin()),200);featureId=block.path("id").asText();
        double hectares=block.path("calculatedAreaHectares").asDouble();assertTrue(hectares>18&&hectares<20,"Unexpected spheroidal area "+hectares);
        assertEquals(80.0002,block.path("boundingBox").get(0).asDouble(),1e-10);
        var expected=jdbc.queryForObject("select ST_Area(geometry::geography)/10000 from spatial_feature where id=?",Double.class,UUID.fromString(featureId));assertEquals(expected,hectares,1e-7);
        save("feature",block);save("import",job);
    }
    static void save(String name,JsonNode value)throws Exception{var directory=java.nio.file.Path.of("target","api-contract-samples");java.nio.file.Files.createDirectories(directory);java.nio.file.Files.writeString(directory.resolve(name+".json"),value.toString());}
    static Map<String,Object> featureRequest(JsonNode f){var props=new LinkedHashMap<String,Object>();for(String k:List.of("estateId","referenceEntityId","referenceEntityType","featureCode","geometry","srid","geometryVersion","status","sourceType","sourceReference","dataProvenance"))props.put(k,json.convertValue(f.path(k),Object.class));return props;}
    @Test @Order(3) void spatialQueriesAndExport() throws Exception {
        for(String op:List.of("point","intersects","within")){
            Object geometry=op.equals("point")?Map.of("type","Point","coordinates",List.of(80.001,6.001)):json.readTree("{\"type\":\"Polygon\",\"coordinates\":[[[80,6],[80.02,6],[80.02,6.01],[80,6.01],[80,6]]]}");
            var query=body(request("POST","/api/v1/spatial/query/"+op,Map.of("estateId",estateId,"geometry",geometry,"bbox",List.of(79.99,5.99,80.03,6.02),"limit",100),admin()),200);assertFalse(query.path("content").isEmpty());save("query",query);
        }
        var export=body(request("GET","/api/v1/spatial/estates/"+estateId+"/export/geojson",null,admin()),200);assertEquals("FeatureCollection",export.path("type").asText());assertEquals(13,export.path("features").size());save("export",export);
        save("bbox",body(request("GET","/api/v1/spatial/estates/"+estateId+"/bbox",null,admin()),200));
        var transformed=body(request("POST","/api/v1/spatial/transform",Map.of("sourceSrid",3857,"geometry",Map.of("type","Point","coordinates",List.of(8905559.263461886,669141.0570442461))),admin()),200);assertEquals(80,transformed.path("geometry").path("coordinates").get(0).asDouble(),1e-6);
    }
    @Test @Order(4) void historyConflictContainmentAndInvalidGeometry() throws Exception {
        var original=body(request("GET","/api/v1/spatial/features/"+featureId,null,admin()),200);var update=featureRequest(original);
        var updated=body(request("PUT","/api/v1/spatial/features/"+featureId,update,admin()),200);assertEquals(2,updated.path("geometryVersion").asInt());body(request("PUT","/api/v1/spatial/features/"+featureId,update,admin()),409);
        var history=body(request("GET","/api/v1/spatial/blocks/"+blockId+"/history",null,admin()),200);assertEquals(2,history.path("content").size());assertEquals("SUPERSEDED",history.path("content").get(0).path("status").asText());
        update=featureRequest(updated);update.put("geometry",Map.of("type","Polygon","coordinates",List.of(List.of(List.of(80,6),List.of(81,7),List.of(81,6),List.of(80,7),List.of(80,6)))));body(request("PUT","/api/v1/spatial/features/"+featureId,update,admin()),400);
        update=featureRequest(updated);update.put("geometry",Map.of("type","Polygon","coordinates",List.of(List.of(List.of(80.03,6),List.of(80.04,6),List.of(80.04,6.01),List.of(80.03,6)))));body(request("PUT","/api/v1/spatial/features/"+featureId,update,admin()),400);
        update=featureRequest(updated);update.put("featureCode","DUPLICATE");body(request("POST","/api/v1/spatial/features",update,admin()),409);
        assertEquals(1,jdbc.queryForObject("select count(*) from spatial_feature_version where feature_id=?",Integer.class,UUID.fromString(featureId)));
    }
    @Test @Order(5) void layerAndAssetMetadata() throws Exception {
        var layer=new LinkedHashMap<String,Object>(Map.of("estateId",estateId,"layerCode","TILES","name","Synthetic tiles metadata","layerType","ASSET","format","3D_TILES","enabled",true,"sourceUri","https://assets.example.invalid/synthetic/tileset.json"));
        var created=body(request("POST","/api/v1/spatial/layers",layer,admin()),201);save("layer",created);layer.put("version",0);body(request("PUT","/api/v1/spatial/layers/"+created.path("id").asText(),layer,admin()),200);body(request("PUT","/api/v1/spatial/layers/"+created.path("id").asText(),layer,admin()),409);
        for(var asset:json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("examples/synthetic-assets.json"))))save("asset",body(request("POST","/api/v1/spatial/assets",asset,admin()),201));
        body(request("GET","/api/v1/spatial/estates/"+estateId+"/assets",null,admin()),200);
    }
    @Test @Order(6) void authorizationAndBodyLimits() throws Exception {
        body(request("GET","/api/v1/spatial/features/"+featureId,null,null),401);
        String viewer=token("VIEWER",List.of(estateId.toString()),"spatial-service",Instant.now().plusSeconds(300));
        var original=body(request("GET","/api/v1/spatial/features/"+featureId,null,viewer),200);body(request("PUT","/api/v1/spatial/features/"+featureId,featureRequest(original),viewer),403);
        body(request("POST","/api/v1/spatial/imports/geojson",Map.of("estateId",estateId),viewer),403);
        String outsider=token("ESTATE_MANAGER",List.of(UUID.randomUUID().toString()),"spatial-service",Instant.now().plusSeconds(300));body(request("GET","/api/v1/spatial/features/"+featureId,null,outsider),403);
        body(request("GET","/api/v1/spatial/features/"+featureId,null,token("PLATFORM_ADMIN",List.of(),"wrong",Instant.now().plusSeconds(300))),401);
        body(request("GET","/api/v1/spatial/features/"+featureId,null,token("PLATFORM_ADMIN",List.of(),"spatial-service",Instant.now().minusSeconds(120))),401);
        body(request("POST","/api/v1/spatial/features",Map.of("padding","x".repeat(2097152)),admin()),413);
        String agronomist=token("AGRONOMIST",List.of(estateId.toString()),"spatial-service",Instant.now().plusSeconds(300));body(request("GET","/api/v1/spatial/features/"+featureId,null,agronomist),200);
    }
    @Test @Order(7) void atomicImportRollbackAndFailedJob() throws Exception {
        int before=jdbc.queryForObject("select count(*) from outbox_event",Integer.class);
        var collection=json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("examples/synthetic-estate.geojson")));
        var job=body(request("POST","/api/v1/spatial/imports/geojson",Map.of("estateId",estateId,"sourceFilename","duplicate.geojson","geojson",collection),admin()),202);assertEquals("FAILED",job.path("status").asText());assertEquals(0,job.path("importedCount").asInt());assertEquals(before,jdbc.queryForObject("select count(*) from outbox_event",Integer.class));
    }
    @Test @Order(8) void publisherConfirmsRetryAndContracts() throws Exception {
        var rabbit=context.getBean(RabbitTemplate.class);var admin=new RabbitAdmin(rabbit);admin.declareExchange(new TopicExchange("agrosphere.domain",true,false));
        var queue=new org.springframework.amqp.core.Queue("spatial-test-events",true);admin.declareQueue(queue);admin.declareBinding(BindingBuilder.bind(queue).to(new TopicExchange("agrosphere.domain")).with("agrosphere.*.v1"));
        var exports=java.nio.file.Path.of("target","event-contract-samples");java.nio.file.Files.createDirectories(exports);
        for(var row:jdbc.queryForList("select event_id,payload::text as envelope from outbox_event"))java.nio.file.Files.writeString(exports.resolve(row.get("event_id")+".json"),row.get("envelope").toString());
        var tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));var dispatcher=new OutboxDispatcher(jdbc,rabbit,2,100,1,5);tx.executeWithoutResult(status->dispatcher.dispatch());assertEquals(0,jdbc.queryForObject("select count(*) from outbox_event where delivery_status<>'PUBLISHED'",Integer.class));
        var first=rabbit.receive(queue.getName(),5000);assertNotNull(first);String id=first.getMessageProperties().getMessageId();while(rabbit.receive(queue.getName(),100)!=null){}
        jdbc.update("update outbox_event set delivery_status='PENDING',published_at=null,next_attempt_at=now() where event_id=?",UUID.fromString(id));tx.executeWithoutResult(status->dispatcher.dispatch());assertEquals(id,rabbit.receive(queue.getName(),5000).getMessageProperties().getMessageId());
        admin.removeBinding(BindingBuilder.bind(queue).to(new TopicExchange("agrosphere.domain")).with("agrosphere.*.v1"));jdbc.update("update outbox_event set delivery_status='PENDING',published_at=null,next_attempt_at=now(),attempts=0 where event_id=?",UUID.fromString(id));tx.executeWithoutResult(status->dispatcher.dispatch());assertEquals("PENDING",jdbc.queryForObject("select delivery_status from outbox_event where event_id=?",String.class,UUID.fromString(id)));jdbc.update("update outbox_event set next_attempt_at=now() where event_id=?",UUID.fromString(id));tx.executeWithoutResult(status->dispatcher.dispatch());assertEquals("FAILED",jdbc.queryForObject("select delivery_status from outbox_event where event_id=?",String.class,UUID.fromString(id)));
    }
    @Test @Order(9) void consumerDlqAndHealth() throws Exception {
        var rabbit=context.getBean(RabbitTemplate.class);rabbit.convertAndSend("agrosphere.domain","agrosphere.EstateCreated.v2","{}");
        assertNotNull(rabbit.receive("spatial.estate.v2.dead",10000));
        body(request("GET","/actuator/health/readiness",null,null),200);body(request("GET","/v3/api-docs",null,null),401);
        var api=body(request("GET","/v3/api-docs",null,admin()),200);assertTrue(api.path("paths").has("/api/v1/spatial/features"));assertTrue(api.path("components").path("schemas").has("Feature"));assertEquals("#/components/schemas/Feature",api.path("paths").path("/api/v1/spatial/features").path("post").path("responses").path("201").path("content").path("application/json").path("schema").path("$ref").asText());java.nio.file.Files.writeString(java.nio.file.Path.of("target/openapi-runtime.json"),api.toString());
    }
    @Test @Order(10) void overlappingBlocksAreRejectedAndConcurrentUpdatesConflict() throws Exception {
        var original=body(request("GET","/api/v1/spatial/features/"+featureId,null,admin()),200);
        var second=body(request("GET","/api/v1/spatial/blocks/00000000-0000-0000-0000-000000000011/geometry",null,admin()),200);
        var overlapping=featureRequest(second);overlapping.put("geometry",original.get("geometry"));body(request("PUT","/api/v1/spatial/features/"+second.path("id").asText(),overlapping,admin()),400);
        var update=featureRequest(original);String jwt=admin();
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var a=executor.submit(()->request("PUT","/api/v1/spatial/features/"+featureId,update,jwt));var b=executor.submit(()->request("PUT","/api/v1/spatial/features/"+featureId,update,jwt));
            var statuses=new java.util.HashSet<>(List.of(a.get().statusCode(),b.get().statusCode()));assertEquals(Set.of(200,409),statuses);
        }
        var parent=body(request("GET","/api/v1/spatial/blocks/"+blockId+"/history",null,admin()),200);assertEquals(3,parent.path("content").size());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO spatial_feature SELECT gen_random_uuid(),estate_id,reference_entity_id,reference_entity_type,'RACE_DUP',geometry_type,geometry,srid,calculated_area_hectares,1,status,source_type,source_reference,data_provenance,now(),now() FROM spatial_feature WHERE id=?",UUID.fromString(featureId)));
    }
    @Test @Order(11) void domainOutboxRollbackAndRealBrokerConsumption() throws Exception {
        int outbox=jdbc.queryForObject("select count(*) from outbox_event",Integer.class);
        var tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(status->{var id=UUID.randomUUID();jdbc.update("INSERT INTO spatial_layer(id,estate_id,layer_code,name,layer_type,format,enabled,created_at,updated_at) VALUES (?,?,'ROLLBACK','Rollback','VECTOR','GEOJSON',true,now(),now())",id,estateId);context.getBean(EventOutbox.class).append("SpatialLayerCreated",estateId,null,null,Map.of("layerId",id));throw new IllegalStateException("rollback");}));
        assertEquals(outbox,jdbc.queryForObject("select count(*) from outbox_event",Integer.class));assertEquals(0,jdbc.queryForObject("select count(*) from spatial_layer where layer_code='ROLLBACK'",Integer.class));
        var event=json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("examples/synthetic-estate-events.json"))).get(0).deepCopy();((tools.jackson.databind.node.ObjectNode)event).put("eventType","EstateUpdated");((tools.jackson.databind.node.ObjectNode)event).put("eventId",UUID.randomUUID().toString());((tools.jackson.databind.node.ObjectNode)event.path("payload")).put("version",5);
        context.getBean(RabbitTemplate.class).send("agrosphere.domain","agrosphere.EstateUpdated.v2",new Message(event.toString().getBytes(StandardCharsets.UTF_8)));
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while(jdbc.queryForObject("select revision from reference_entity where id=? and entity_type='ESTATE'",Integer.class,estateId)!=5&&System.nanoTime()<deadline)Thread.sleep(50);
        assertEquals(5,jdbc.queryForObject("select revision from reference_entity where id=? and entity_type='ESTATE'",Integer.class,estateId));
    }

    @Test @Order(12) void nonAreaTypesMultipolygonAndConfigurableMinimum() throws Exception {
        var original=body(request("GET","/api/v1/spatial/features/"+featureId,null,admin()),200);var input=featureRequest(original);
        for(var kind:List.of("Point","LineString","MultiPolygon")){
            input.put("featureCode","EXTRA_"+kind.toUpperCase(java.util.Locale.ROOT));input.put("status","INACTIVE");
            Object coordinates=switch(kind){case "Point"->List.of(80.001,6.001);case "LineString"->List.of(List.of(80.001,6.001),List.of(80.002,6.001));default->List.of(json.convertValue(original.path("geometry").path("coordinates"),Object.class));};
            input.put("geometry",Map.of("type",kind,"coordinates",coordinates));var created=body(request("POST","/api/v1/spatial/features",input,admin()),201);
            if(!kind.equals("MultiPolygon"))assertTrue(created.path("calculatedAreaHectares").isNull());else assertTrue(created.path("calculatedAreaHectares").asDouble()>18);
        }
        var minimumStore=new com.agrosphere.spatial.adapter.out.persistence.JdbcSpatialStore(jdbc,json,100,0);
        var request=json.convertValue(featureRequest(original),Requests.Feature.class);
        var tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));assertThrows(SpatialException.class,()->tx.executeWithoutResult(status->minimumStore.saveFeature(UUID.fromString(featureId),request)));
        String forged=token("PLATFORM_ADMIN",List.of(),"spatial-service",Instant.now().plusSeconds(300),"https://untrusted.invalid");body(request("GET","/api/v1/spatial/features/"+featureId,null,forged),401);
        var invalid=SignedJWT.parse(admin());invalid=new SignedJWT(invalid.getHeader(),invalid.getJWTClaimsSet());invalid.sign(new RSASSASigner(new RSAKeyGenerator(2048).generate()));body(request("GET","/api/v1/spatial/features/"+featureId,null,invalid.serialize()),401);
    }

}
