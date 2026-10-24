package com.agrosphere.spatial.application;
import java.util.*;
import java.net.URI;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import com.agrosphere.spatial.application.port.out.*;
import com.agrosphere.spatial.domain.*;
import com.agrosphere.spatial.domain.SpatialModels.*;
import org.slf4j.*;
@Service @Transactional
public class SpatialService {
    private final SpatialStore store;private final EstateAccess access;private final EventOutbox events;private final GeometryValidator validator;
    private final double maxBbox;private static final Logger log=LoggerFactory.getLogger(SpatialService.class);
    public SpatialService(SpatialStore store,EstateAccess access,EventOutbox events,@Value("${spatial.limits.coordinates:20000}") int maxCoordinates,@Value("${spatial.limits.bbox-degrees:5}") double maxBbox){this.store=store;this.access=access;this.events=events;this.validator=new GeometryValidator(maxCoordinates);this.maxBbox=maxBbox;}
    public static String code(String value){if(value==null||!value.matches("[A-Z0-9][A-Z0-9_-]{0,63}"))throw SpatialException.invalid("Code must contain 1–64 uppercase letters, digits, underscore or hyphen");return value;}
    private static void required(Object... values){for(var value:values)if(value==null)throw SpatialException.invalid("Missing required request field");}
    public static void uri(String value,boolean optional){
        if(value==null&&optional)return;
        try{URI uri=URI.create(value);if(value.length()>2048||!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null)throw new IllegalArgumentException();}
        catch(Exception ex){throw SpatialException.invalid("Asset and layer URIs must be absolute HTTPS URIs without credentials or fragments");}
    }
    private static void provenance(String value){if(value==null||!Set.of("REAL","SIMULATED","ESTIMATED","PREDICTED").contains(value))throw SpatialException.invalid("Explicit dataProvenance is required");}
    private void geometry(tools.jackson.databind.JsonNode value,int srid){if(value==null||value.isNull()||value.has("crs"))throw SpatialException.invalid("Supply a GeoJSON geometry without a legacy crs member");validator.validate(value.toString(),srid);}
    @Transactional(readOnly=true) public Map<String,Object> feature(UUID id){var f=store.feature(id);access.read(estate(f));return f;}
    private static UUID estate(Map<String,Object> resource){return UUID.fromString(resource.get("estateId").toString());}
    public Map<String,Object> feature(UUID id,Requests.Feature r){
        required(r.estateId(),r.referenceEntityId(),r.referenceEntityType(),r.srid(),r.status(),r.sourceType());access.write(r.estateId());
        if(id!=null){var previous=store.feature(id);access.write(estate(previous));}
        code(r.featureCode());provenance(r.dataProvenance());geometry(r.geometry(),r.srid());
        if(r.status()==FeatureStatus.SUPERSEDED)throw SpatialException.invalid("SUPERSEDED is reserved for historical versions");
        if(r.sourceReference()!=null&&r.sourceReference().length()>500)throw SpatialException.invalid("sourceReference is limited to 500 characters");
        if(r.referenceEntityType()==ReferenceType.ESTATE&&!r.estateId().equals(r.referenceEntityId()))throw SpatialException.invalid("Estate boundary must reference its estate ID");
        var result=store.saveFeature(id,r);var ref=UUID.fromString(result.get("referenceEntityId").toString());int version=((Number)result.get("geometryVersion")).intValue();
        var payload=Map.<String,Object>of("featureId",result.get("id"),"referenceEntityType",r.referenceEntityType().name(),"status",result.get("status"),"dataProvenance",r.dataProvenance());
        events.append(id==null?"SpatialFeatureCreated":"SpatialFeatureUpdated",r.estateId(),ref,version,payload);
        events.append("SpatialBoundaryChanged",r.estateId(),ref,version,payload);
        log.info("Geometry modified featureId={} estateId={} geometryVersion={} actor={} correlationId={}",result.get("id"),r.estateId(),version,access.actor(),CorrelationContext.current());return result;
    }
    public static void page(int page,int size){if(page<0||page>100000||size<1||size>100)throw SpatialException.invalid("page must be 0–100000 and size 1–100");}
    @Transactional(readOnly=true) public Map<String,Object> list(UUID estate,String kind,int page,int size){access.read(estate);page(page,size);return store.list(estate,kind,page,size);}
    @Transactional(readOnly=true) public Map<String,Object> block(UUID id,boolean history){var result=store.block(id,history);access.read(estate(result));if(history&&((List<?>)result.get("content")).size()>1000)throw new SpatialException(422,"History exceeds inline response limit; use a reviewed database export");return result;}
    @Transactional(readOnly=true) public Map<String,Object> query(Requests.Query q,String operation){
        required(q.estateId(),q.bbox());access.read(q.estateId());geometry(q.geometry(),4326);
        if(operation.equals("point")&&!q.geometry().get("type").asText().equals("Point"))throw SpatialException.invalid("Point query requires a Point geometry");
        if(operation.equals("within")&&!q.geometry().get("type").asText().endsWith("Polygon"))throw SpatialException.invalid("Within query requires a polygon");
        var b=q.bbox();if(b.length!=4)throw SpatialException.invalid("bbox requires [west,south,east,north]");
        for(double n:b)if(!Double.isFinite(n))throw SpatialException.invalid("bbox requires finite coordinates");
        if(b[0]<-180||b[2]>180||b[1]<-90||b[3]>90||b[0]>=b[2]||b[1]>=b[3]||b[2]-b[0]>maxBbox||b[3]-b[1]>maxBbox)throw SpatialException.invalid("bbox exceeds configured geographic query limits");
        var env=validator.validate(q.geometry().toString(),4326).getEnvelopeInternal();
        if(env.getMinX()<b[0]||env.getMaxX()>b[2]||env.getMinY()<b[1]||env.getMaxY()>b[3])throw SpatialException.invalid("Query geometry must be inside bbox");
        if(q.limit()!=null&&(q.limit()<1||q.limit()>100))throw SpatialException.invalid("Query limit must be 1–100");return store.query(q,operation);
    }
    @Transactional(readOnly=true) public Map<String,Object> bbox(UUID estate){access.read(estate);return store.bbox(estate);}
    @Transactional(readOnly=true) public Map<String,Object> metadata(UUID id,String kind){var result=store.metadata(id,kind);access.read(estate(result));return result;}
    public Map<String,Object> layer(UUID id,Requests.Layer r){
        required(r.estateId(),r.layerType(),r.enabled());access.write(r.estateId());code(r.layerCode());
        if(id!=null){var old=store.metadata(id,"layers");access.write(estate(old));if(!estate(old).equals(r.estateId()))throw SpatialException.invalid("Layer estate is immutable");if(r.version()==null)throw new SpatialException(409,"version required");}
        if(r.name()==null||r.name().isBlank()||r.name().length()>200)throw SpatialException.invalid("Layer name must contain 1–200 characters");
        if(r.format()==null||!Set.of("GEOJSON","3D_TILES","RASTER","OTHER").contains(r.format()))throw SpatialException.invalid("Unsupported layer format");uri(r.sourceUri(),true);
        var result=store.layer(id,r);if(id==null)events.append("SpatialLayerCreated",r.estateId(),null,null,Map.of("layerId",result.get("id")));return result;
    }
    public Map<String,Object> asset(Requests.Asset r){
        required(r.estateId(),r.assetType(),r.orientation(),r.scale());access.write(r.estateId());code(r.assetCode());uri(r.assetUri(),false);provenance(r.dataProvenance());
        if(!Set.of("ACTIVE","INACTIVE").contains(Objects.toString(r.status(),"")))throw SpatialException.invalid("Invalid asset status");
        for(double n:new double[]{r.orientation().x(),r.orientation().y(),r.orientation().z(),r.scale().x(),r.scale().y(),r.scale().z()})if(!Double.isFinite(n))throw SpatialException.invalid("Orientation and scale require finite values");
        if(r.scale().x()<=0||r.scale().y()<=0||r.scale().z()<=0)throw SpatialException.invalid("Asset scale must be positive");
        if(r.assetType()==AssetType.TREE_MODEL&&!r.dataProvenance().equals("SIMULATED"))throw SpatialException.invalid("Generated tree models must be marked SIMULATED; they are illustrative rather than surveyed trees");
        if(r.position()!=null&&!r.position().isNull()){geometry(r.position(),4326);if(!r.position().get("type").asText().equals("Point"))throw SpatialException.invalid("Asset position must be a Point");}
        if(r.spatialFeatureId()!=null){var f=store.feature(r.spatialFeatureId());access.read(estate(f));if(!estate(f).equals(r.estateId()))throw SpatialException.invalid("Asset feature must belong to the same estate");}
        var result=store.asset(r);events.append("DigitalTwinAssetRegistered",r.estateId(),null,null,Map.of("assetId",result.get("id"),"dataProvenance",r.dataProvenance()));return result;
    }
    @Transactional(readOnly=true) public Map<String,Object> export(UUID estate,int page,int size){access.read(estate);page(page,size);return store.export(estate,page,size);}
    public Map<String,Object> transform(Requests.Transform r){
        required(r.geometry(),r.sourceSrid());
        if(!Set.of(4326,3857).contains(r.sourceSrid()))throw SpatialException.invalid("Transformation supports source EPSG:4326 or EPSG:3857 only");
        // This operation is limited to points; boundary imports must already comply with RFC 7946.
        var g=r.geometry();if(g.has("crs")||!g.path("type").asText().equals("Point")||!g.path("coordinates").isArray()||g.path("coordinates").size()!=2)throw SpatialException.invalid("Transformation requires a 2D Point");
        for(var c:g.path("coordinates"))if(!c.isNumber()||!Double.isFinite(c.asDouble())||Math.abs(c.asDouble())>(r.sourceSrid()==3857?20037508.342789244:180))throw SpatialException.invalid("Point outside supported projection range");
        if(r.sourceSrid()==4326)geometry(g,4326);
        var result=store.transform(r);geometry(new tools.jackson.databind.ObjectMapper().valueToTree(result.get("geometry")),4326);return result;
    }
}
