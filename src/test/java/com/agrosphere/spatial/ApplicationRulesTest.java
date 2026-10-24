package com.agrosphere.spatial;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import tools.jackson.databind.ObjectMapper;
import com.agrosphere.spatial.application.*;
import com.agrosphere.spatial.application.port.out.*;
import com.agrosphere.spatial.domain.*;
import com.agrosphere.spatial.domain.SpatialModels.*;
class ApplicationRulesTest {
    SpatialStore store=mock(SpatialStore.class);EstateAccess access=mock(EstateAccess.class);EventOutbox events=mock(EventOutbox.class);
    SpatialService service=new SpatialService(store,access,events,100,5);ObjectMapper json=new ObjectMapper();UUID estate=UUID.randomUUID();
    @Test void layerValidationRejectsUnsupportedFormat(){assertThrows(SpatialException.class,()->service.layer(null,new Requests.Layer(estate,"LAYER","Layer",LayerType.VECTOR,null,"UNKNOWN",true,null)));verify(store,never()).layer(any(),any());}
    @Test void layerEstateCannotChange(){UUID id=UUID.randomUUID();when(store.metadata(id,"layers")).thenReturn(Map.of("estateId",UUID.randomUUID().toString()));assertThrows(SpatialException.class,()->service.layer(id,new Requests.Layer(estate,"LAYER","Layer",LayerType.VECTOR,null,"GEOJSON",true,0)));}
    @Test void featureUsesServerOwnedEstateForAuthorization(){UUID id=UUID.randomUUID(),actualEstate=UUID.randomUUID();when(store.feature(id)).thenReturn(Map.of("estateId",actualEstate.toString()));assertEquals(actualEstate.toString(),service.feature(id).get("estateId"));verify(access).read(actualEstate);}
    @Test void scopeDeniedBeforePersistence(){doThrow(new org.springframework.security.access.AccessDeniedException("denied")).when(access).read(estate);assertThrows(org.springframework.security.access.AccessDeniedException.class,()->service.list(estate,"features",0,20));verifyNoInteractions(store);}
    @Test void generatedTreesCannotClaimSurveyProvenance(){var r=new Requests.Asset(estate,null,"TREE",AssetType.TREE_MODEL,"https://assets.example.invalid/tree.glb",null,new Vector3(0,0,0),new Vector3(1,1,1),"ACTIVE","REAL");assertThrows(SpatialException.class,()->service.asset(r));verify(store,never()).asset(any());}
    @Test void queryRequiresBoundedBbox(){assertThrows(SpatialException.class,()->service.query(new Requests.Query(estate,json.readTree("{\"type\":\"Point\",\"coordinates\":[80,6]}"),new double[]{-180,-90,180,90},100),"point"));}
    @Test void featureVersionPassedToPersistenceAndEvents(){UUID ref=UUID.randomUUID();var r=new Requests.Feature(estate,ref,ReferenceType.BLOCK,"BLOCK",json.readTree("{\"type\":\"Point\",\"coordinates\":[80,6]}"),4326,null,FeatureStatus.ACTIVE,SourceType.GENERATED,null,"SIMULATED");when(store.saveFeature(null,r)).thenReturn(Map.of("id",UUID.randomUUID().toString(),"referenceEntityId",ref.toString(),"geometryVersion",1,"status","ACTIVE"));service.feature(null,r);verify(events).append(eq("SpatialFeatureCreated"),eq(estate),eq(ref),eq(1),anyMap());}
}
