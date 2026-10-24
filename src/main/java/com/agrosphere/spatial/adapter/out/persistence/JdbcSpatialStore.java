package com.agrosphere.spatial.adapter.out.persistence;
import java.util.*;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Value;
import com.agrosphere.spatial.application.*;
import com.agrosphere.spatial.application.port.out.SpatialStore;
import com.agrosphere.spatial.domain.SpatialException;
@Repository
public class JdbcSpatialStore implements SpatialStore {
    private final JdbcTemplate db; private final ObjectMapper json; private final double minArea,overlap;
    public JdbcSpatialStore(JdbcTemplate db,ObjectMapper json,@Value("${spatial.rules.minimum-area-hectares:0}") double minArea,
        @Value("${spatial.rules.overlap-tolerance-hectares:0}") double overlap) {
        if(!Double.isFinite(minArea)||minArea<0||!Double.isFinite(overlap)||overlap<0)throw new IllegalArgumentException("Invalid area rules");
        this.db=db;this.json=json;this.minArea=minArea;this.overlap=overlap;
    }
    public static final String FEATURE_JSON="""
        jsonb_build_object('id',f.id,'estateId',f.estate_id,'referenceEntityId',f.reference_entity_id,'referenceEntityType',f.reference_entity_type,
          'featureCode',f.feature_code,'geometryType',f.geometry_type,'geometry',ST_AsGeoJSON(f.geometry,15,0)::jsonb,'srid',f.srid,
          'boundingBox',jsonb_build_array(ST_XMin(Box3D(f.geometry)),ST_YMin(Box3D(f.geometry)),ST_XMax(Box3D(f.geometry)),ST_YMax(Box3D(f.geometry))),
          'centroid',ST_AsGeoJSON(ST_Centroid(f.geometry),15,0)::jsonb,'calculatedAreaHectares',f.calculated_area_hectares,
          'geometryVersion',f.geometry_version,'status',f.status,'sourceType',f.source_type,'sourceReference',f.source_reference,
          'dataProvenance',f.data_provenance,'createdAt',f.created_at,'updatedAt',f.updated_at)
        """;
    private static final String LAYER_JSON="jsonb_build_object('id',id,'estateId',estate_id,'layerCode',layer_code,'name',name,'layerType',layer_type,'sourceUri',source_uri,'format',format,'enabled',enabled,'version',version,'createdAt',created_at,'updatedAt',updated_at)";
    private static final String ASSET_JSON="jsonb_build_object('id',id,'estateId',estate_id,'spatialFeatureId',spatial_feature_id,'assetCode',asset_code,'assetType',asset_type,'assetUri',asset_uri,'position',ST_AsGeoJSON(position,15,0)::jsonb,'orientation',orientation,'scale',scale,'status',status,'dataProvenance',data_provenance,'createdAt',created_at,'updatedAt',updated_at)";
    private static final String JOB_JSON="jsonb_build_object('id',id,'estateId',estate_id,'sourceFilename',source_filename,'sourceType',source_type,'status',status,'importedCount',imported_count,'rejectedCount',rejected_count,'validationReport',validation_report,'createdAt',created_at,'completedAt',completed_at)";
    @SuppressWarnings("unchecked") public Map<String,Object> decode(String s){return json.readValue(s,Map.class);}
    private List<Map<String,Object>> rows(String sql,Object... args){return db.query(sql,(rs,n)->decode(rs.getString(1)),args);}
    private Map<String,Object> one(String sql,Object... args){var rows=rows(sql,args);if(rows.isEmpty())throw new SpatialException(404,"Spatial resource not found");return rows.getFirst();}
    @Override public Map<String,Object> feature(UUID id){return one("SELECT "+FEATURE_JSON+" FROM spatial_feature f WHERE id=?",id);}
    @Override public void reference(UUID estate,UUID id,String type){
        // Serializes all boundary writes in one estate, including overlap and containment checks.
        var parents=db.queryForList("SELECT id FROM reference_entity WHERE id=? AND estate_id=? AND entity_type='ESTATE' AND status='ACTIVE' FOR UPDATE",UUID.class,estate,estate);
        if(parents.isEmpty())throw SpatialException.invalid("Active estate reference is unavailable; replay the estate contract snapshot first");
        if(db.queryForObject("SELECT count(*) FROM reference_entity WHERE id=? AND estate_id=? AND entity_type=? AND status='ACTIVE'",Integer.class,id,estate,type)!=1)
            throw SpatialException.invalid("Active entity does not belong to the estate reference projection");
    }
    @Override public Map<String,Object> saveFeature(UUID id,Requests.Feature r){
        reference(r.estateId(),r.referenceEntityId(),r.referenceEntityType().name());
        String geometry=r.geometry().toString();boolean polygon=r.geometry().get("type").asText().endsWith("Polygon");
        Double area=polygon?db.queryForObject("SELECT ST_Area(ST_GeomFromGeoJSON(?)::geography)/10000",Double.class,geometry):null;
        if(polygon && (area==null||area<=0||area<minArea))throw SpatialException.invalid("Polygon area is below the configured minimum hectares");
        if(r.status()==com.agrosphere.spatial.domain.SpatialModels.FeatureStatus.ACTIVE){
            UUID excluded=id==null?new UUID(0,0):id;
            if(db.queryForObject("SELECT count(*) FROM spatial_feature WHERE reference_entity_id=? AND reference_entity_type=? AND status='ACTIVE' AND id<>?",Integer.class,r.referenceEntityId(),r.referenceEntityType().name(),excluded)>0)
                throw new SpatialException(409,"An active authoritative feature already exists for this entity");
            if(r.referenceEntityType()!=com.agrosphere.spatial.domain.SpatialModels.ReferenceType.ESTATE){
                var parent=db.queryForList("SELECT parent_id FROM reference_entity WHERE id=? AND entity_type=?",UUID.class,r.referenceEntityId(),r.referenceEntityType().name()).getFirst();
                Integer outside=db.queryForObject("SELECT count(*) FROM spatial_feature WHERE estate_id=? AND status='ACTIVE' AND reference_entity_id IN (?,?) AND geometry_type IN ('POLYGON','MULTIPOLYGON') AND NOT ST_Covers(geometry,ST_GeomFromGeoJSON(?))",Integer.class,r.estateId(),r.estateId(),parent,geometry);
                if(outside>0)throw SpatialException.invalid("Geometry must be covered by the available estate and division boundaries");
            }
            // Parent changes may not strand existing child boundaries.
            if(r.referenceEntityType()!=com.agrosphere.spatial.domain.SpatialModels.ReferenceType.BLOCK){
                if(db.queryForObject("SELECT count(*) FROM spatial_feature f JOIN reference_entity r ON r.id=f.reference_entity_id AND r.entity_type=f.reference_entity_type WHERE f.estate_id=? AND f.id<>? AND f.status='ACTIVE' AND (r.parent_id=? OR ?='ESTATE') AND NOT ST_Covers(ST_GeomFromGeoJSON(?),f.geometry)",Integer.class,r.estateId(),excluded,r.referenceEntityId(),r.referenceEntityType().name(),geometry)>0)
                    throw SpatialException.invalid("Updated parent boundary must contain its existing active children");
            }
            if(r.referenceEntityType()==com.agrosphere.spatial.domain.SpatialModels.ReferenceType.BLOCK && polygon &&
                db.queryForObject("SELECT count(*) FROM spatial_feature WHERE estate_id=? AND reference_entity_type='BLOCK' AND status='ACTIVE' AND id<>? AND geometry && ST_GeomFromGeoJSON(?) AND ST_Area(ST_CollectionExtract(ST_Intersection(geometry,ST_GeomFromGeoJSON(?)),3)::geography)/10000 > ?",Integer.class,r.estateId(),excluded,geometry,geometry,overlap)>0)
                throw SpatialException.invalid("Block overlaps an active block beyond the configured hectare tolerance");
        }
        UUID resultId=id==null?UUID.randomUUID():id;
        if(id==null){
            db.update("INSERT INTO spatial_feature(id,estate_id,reference_entity_id,reference_entity_type,feature_code,geometry_type,geometry,calculated_area_hectares,geometry_version,status,source_type,source_reference,data_provenance,created_at,updated_at) VALUES (?,?,?,?,?,?,ST_GeomFromGeoJSON(?),?,1,?,?,?,?,clock_timestamp(),clock_timestamp())",resultId,r.estateId(),r.referenceEntityId(),r.referenceEntityType().name(),r.featureCode(),r.geometry().get("type").asText().toUpperCase(Locale.ROOT),geometry,area,r.status().name(),r.sourceType().name(),r.sourceReference(),r.dataProvenance());
        }else{
            var old=one("SELECT "+FEATURE_JSON+" FROM spatial_feature f WHERE id=? FOR UPDATE",id);
            if(!old.get("estateId").toString().equals(r.estateId().toString())||!old.get("referenceEntityId").toString().equals(r.referenceEntityId().toString())||!old.get("referenceEntityType").equals(r.referenceEntityType().name()))throw SpatialException.invalid("Feature estate and reference identity are immutable");
            if(r.geometryVersion()==null||((Number)old.get("geometryVersion")).intValue()!=r.geometryVersion())throw new SpatialException(409,"Stale geometryVersion; retrieve the latest feature before updating");
            db.update("INSERT INTO spatial_feature_version(feature_id,geometry_version,geometry,snapshot,valid_from,valid_to) SELECT id,geometry_version,geometry,("+FEATURE_JSON+" || jsonb_build_object('status','SUPERSEDED')),updated_at,clock_timestamp() FROM spatial_feature f WHERE id=?",id);
            int changed=db.update("UPDATE spatial_feature SET feature_code=?,geometry_type=?,geometry=ST_GeomFromGeoJSON(?),calculated_area_hectares=?,geometry_version=geometry_version+1,status=?,source_type=?,source_reference=?,data_provenance=?,updated_at=clock_timestamp() WHERE id=? AND geometry_version=?",r.featureCode(),r.geometry().get("type").asText().toUpperCase(Locale.ROOT),geometry,area,r.status().name(),r.sourceType().name(),r.sourceReference(),r.dataProvenance(),id,r.geometryVersion());
            if(changed!=1)throw new SpatialException(409,"Concurrent geometry update");
        }
        return feature(resultId);
    }
    @Override public Map<String,Object> list(UUID estate,String kind,int page,int size){
        String from,projection;
        switch(kind){case "features","boundaries"->{from="spatial_feature f";projection=FEATURE_JSON;}case "layers"->{from="spatial_layer";projection=LAYER_JSON;}case "assets"->{from="digital_twin_asset";projection=ASSET_JSON;}default->throw new IllegalArgumentException("Invalid collection");}
        String where=" WHERE estate_id=?"+(kind.equals("boundaries")?" AND status='ACTIVE' AND geometry_type IN ('POLYGON','MULTIPOLYGON')":"");
        return Map.of("content",rows("SELECT "+projection+" FROM "+from+where+" ORDER BY id LIMIT ? OFFSET ?",estate,size,(long)page*size),"page",page,"size",size,"totalElements",db.queryForObject("SELECT count(*) FROM "+from+where,Long.class,estate));
    }
    @Override public Map<String,Object> block(UUID id,boolean history){
        var current=rows("SELECT "+FEATURE_JSON+" FROM spatial_feature f WHERE reference_entity_id=? AND reference_entity_type='BLOCK' ORDER BY created_at,id",id);
        if(current.isEmpty())throw new SpatialException(404,"Block geometry not found");
        if(!history)return current.stream().filter(f->f.get("status").equals("ACTIVE")).findFirst().orElseThrow(()->new SpatialException(404,"Active block geometry not found"));
        if(db.queryForObject("SELECT count(*) FROM spatial_feature_version v JOIN spatial_feature f ON f.id=v.feature_id WHERE f.reference_entity_id=? AND f.reference_entity_type='BLOCK'",Integer.class,id)+current.size()>1000)throw new SpatialException(422,"History exceeds 1000 versions; request a reviewed database export");
        var versions=rows("SELECT v.snapshot || jsonb_build_object('validFrom',v.valid_from,'validTo',v.valid_to) FROM spatial_feature_version v JOIN spatial_feature f ON f.id=v.feature_id WHERE f.reference_entity_id=? AND f.reference_entity_type='BLOCK' ORDER BY v.valid_from",id);
        versions.addAll(current);return Map.of("estateId",current.getFirst().get("estateId"),"content",versions);
    }
    @Override public Map<String,Object> query(Requests.Query q,String operation){
        String predicate=switch(operation){case "point"->"ST_Covers(f.geometry,g.geom)";case "intersects"->"ST_Intersects(f.geometry,g.geom)";case "within"->"ST_CoveredBy(f.geometry,g.geom)";default->throw new IllegalArgumentException();};
        double[] b=q.bbox();int limit=q.limit()==null?100:q.limit();
        var found=rows("SELECT "+FEATURE_JSON+" FROM spatial_feature f CROSS JOIN (SELECT ST_GeomFromGeoJSON(?) AS geom) g WHERE f.estate_id=? AND f.status='ACTIVE' AND f.geometry && ST_MakeEnvelope(?,?,?,?,4326) AND "+predicate+" ORDER BY f.id LIMIT ?",q.geometry().toString(),q.estateId(),b[0],b[1],b[2],b[3],limit+1);
        boolean truncated=found.size()>limit;if(truncated)found.removeLast();return Map.of("content",found,"limit",limit,"truncated",truncated);
    }
    @Override public Map<String,Object> bbox(UUID estate){return one("SELECT jsonb_build_object('estateId',?::uuid,'bbox',CASE WHEN count(*)=0 THEN NULL ELSE jsonb_build_array(ST_XMin(ST_Extent(geometry)),ST_YMin(ST_Extent(geometry)),ST_XMax(ST_Extent(geometry)),ST_YMax(ST_Extent(geometry))) END,'srid',4326) FROM spatial_feature WHERE estate_id=? AND status='ACTIVE'",estate,estate);}
    @Override public Map<String,Object> metadata(UUID id,String kind){return switch(kind){case "layers"->one("SELECT "+LAYER_JSON+" FROM spatial_layer WHERE id=?",id);case "assets"->one("SELECT "+ASSET_JSON+" FROM digital_twin_asset WHERE id=?",id);case "imports"->one("SELECT "+JOB_JSON+" FROM spatial_import_job WHERE id=?",id);default->throw new IllegalArgumentException();};}
    @Override public Map<String,Object> layer(UUID id,Requests.Layer r){
        reference(r.estateId(),r.estateId(),"ESTATE");UUID result=id==null?UUID.randomUUID():id;
        if(id==null)db.update("INSERT INTO spatial_layer(id,estate_id,layer_code,name,layer_type,source_uri,format,enabled,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,now(),now())",result,r.estateId(),r.layerCode(),r.name(),r.layerType().name(),r.sourceUri(),r.format(),r.enabled());
        else if(db.update("UPDATE spatial_layer SET layer_code=?,name=?,layer_type=?,source_uri=?,format=?,enabled=?,version=version+1,updated_at=now() WHERE id=? AND estate_id=? AND version=?",r.layerCode(),r.name(),r.layerType().name(),r.sourceUri(),r.format(),r.enabled(),id,r.estateId(),r.version())!=1)throw new SpatialException(409,"Stale layer version");
        return metadata(result,"layers");
    }
    @Override public Map<String,Object> asset(Requests.Asset r){
        reference(r.estateId(),r.estateId(),"ESTATE"); UUID id=UUID.randomUUID();
        db.update("INSERT INTO digital_twin_asset(id,estate_id,spatial_feature_id,asset_code,asset_type,asset_uri,position,orientation,scale,status,data_provenance,created_at,updated_at) VALUES (?,?,?,?,?,?,ST_GeomFromGeoJSON(?),?::jsonb,?::jsonb,?,?,now(),now())",id,r.estateId(),r.spatialFeatureId(),r.assetCode(),r.assetType().name(),r.assetUri(),r.position()==null?null:r.position().toString(),json.writeValueAsString(r.orientation()),json.writeValueAsString(r.scale()),r.status(),r.dataProvenance());return metadata(id,"assets");
    }
    @Override public Map<String,Object> export(UUID estate,int page,int size){
        var pageResult=list(estate,"features",page,size);var features=new ArrayList<Map<String,Object>>();
        for(var value:(List<?>)pageResult.get("content")){@SuppressWarnings("unchecked") var f=new LinkedHashMap<>((Map<String,Object>)value);Object geometry=f.remove("geometry");features.add(Map.of("type","Feature","id",f.get("id"),"geometry",geometry,"bbox",f.get("boundingBox"),"properties",f));}
        return Map.of("type","FeatureCollection","features",features,"page",page,"size",size,"totalElements",pageResult.get("totalElements"));
    }
    @Override public Map<String,Object> transform(Requests.Transform r){
        return one("SELECT jsonb_build_object('geometry',ST_AsGeoJSON(ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(?),?),4326),15,0)::jsonb,'srid',4326,'sourceSrid',?)",r.geometry().toString(),r.sourceSrid(),r.sourceSrid());
    }
}
