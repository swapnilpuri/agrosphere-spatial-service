package com.agrosphere.spatial.application;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.*;
import com.agrosphere.spatial.application.port.out.*;
import com.agrosphere.spatial.domain.SpatialException;
@Service
public class GeoJsonImports {
    private final JdbcTemplate db;private final ObjectMapper json;private final SpatialService spatial;private final EstateAccess access;private final TransactionTemplate tx;
    public GeoJsonImports(JdbcTemplate db,ObjectMapper json,SpatialService spatial,EstateAccess access,PlatformTransactionManager manager){this.db=db;this.json=json;this.spatial=spatial;this.access=access;this.tx=new TransactionTemplate(manager);}
    public Map<String,Object> start(Requests.Import request){
        if(request.estateId()==null)throw SpatialException.invalid("estateId is required");access.write(request.estateId());
        if(request.sourceFilename()==null||!request.sourceFilename().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,190}\\.geojson"))throw SpatialException.invalid("sourceFilename must be a simple .geojson filename without paths");
        var collection=request.geojson();if(collection==null||collection.has("crs")||!collection.path("type").asText().equals("FeatureCollection")||!collection.path("features").isArray()||collection.path("features").isEmpty()||collection.path("features").size()>100)throw SpatialException.invalid("Supply an RFC 7946 FeatureCollection containing 1–100 features");
        UUID job=UUID.randomUUID();int count=collection.path("features").size();
        tx.executeWithoutResult(status->{db.update("INSERT INTO spatial_import_job(id,estate_id,source_filename,source_type,status,created_at) VALUES (?,?,?,'GEOJSON','PENDING',now())",job,request.estateId(),request.sourceFilename());});
        tx.executeWithoutResult(status->db.update("UPDATE spatial_import_job SET status='VALIDATING' WHERE id=?",job));
        try{
            tx.executeWithoutResult(status->{int index=0;for(var f:collection.path("features")){
                try{
                    if(!f.path("type").asText().equals("Feature")||f.has("crs"))throw SpatialException.invalid("Expected GeoJSON Feature without crs");
                    var props=f.path("properties").deepCopy();if(!props.isObject())throw SpatialException.invalid("Feature properties are required");
                    var input=(tools.jackson.databind.node.ObjectNode)props;
                    if(input.has("estateId")&&!input.path("estateId").asText().equals(request.estateId().toString()))throw SpatialException.invalid("Feature estateId conflicts with import estate");
                    input.put("estateId",request.estateId().toString());input.set("geometry",f.path("geometry"));input.put("srid",4326);input.put("sourceType","IMPORT");input.put("sourceReference",request.sourceFilename());
                    // Export metadata is deliberately omitted before mapping to a write request.
                    for(String k:List.of("id","geometryType","boundingBox","centroid","calculatedAreaHectares","createdAt","updatedAt"))input.remove(k);
                    spatial.feature(null,json.treeToValue(input,Requests.Feature.class));
                }catch(SpatialException e){throw new SpatialException(e.status(),"Feature["+index+"]: "+e.getMessage());}index++;
            }db.update("UPDATE spatial_import_job SET status='COMPLETED',imported_count=?,completed_at=now(),validation_report=?::jsonb WHERE id=?",count,json.writeValueAsString(Map.of("atomic",true,"errors",List.of())),job);});
        }catch(RuntimeException ex){
            String detail=ex instanceof SpatialException?ex.getMessage():"Import rejected due to conflicting references, duplicate codes or invalid feature fields";
            tx.executeWithoutResult(status->db.update("UPDATE spatial_import_job SET status='FAILED',rejected_count=?,completed_at=now(),validation_report=?::jsonb WHERE id=?",count,json.writeValueAsString(Map.of("atomic",true,"errors",List.of(detail))),job));
        }
        return spatial.metadata(job,"imports");
    }
}
