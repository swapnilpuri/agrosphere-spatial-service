package com.agrosphere.spatial;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.agrosphere.spatial.domain.*;
import com.agrosphere.spatial.application.SpatialService;
class GeometryValidationTest {
    final GeometryValidator validator=new GeometryValidator(100);
    String geometry(String type,String coordinates){return "{\"type\":\""+type+"\",\"coordinates\":"+coordinates+"}";}
    @Test void polygonEnvelopeAndMultipolygon(){var g=validator.validate(geometry("Polygon","[[[80,6],[80.01,6],[80.01,6.01],[80,6.01],[80,6]]]"),4326);assertEquals(80,g.getEnvelopeInternal().getMinX());assertEquals(6.01,g.getEnvelopeInternal().getMaxY());assertEquals(4326,g.getSRID());assertTrue(validator.validate(geometry("MultiPolygon","[[[[80,6],[80.01,6],[80.01,6.01],[80,6]]]]"),4326).isValid());}
    @Test void rejectsUnclosedRings(){assertThrows(SpatialException.class,()->validator.validate(geometry("Polygon","[[[80,6],[81,6],[81,7],[80,7]]]"),4326));}
    @Test void rejectsSelfIntersection(){assertThrows(SpatialException.class,()->validator.validate(geometry("Polygon","[[[80,6],[81,7],[81,6],[80,7],[80,6]]]"),4326));}
    @Test void rejectsInvalidHoles(){assertThrows(SpatialException.class,()->validator.validate(geometry("Polygon","[[[80,6],[81,6],[81,7],[80,7],[80,6]],[[82,6],[83,6],[83,7],[82,6]]]"),4326));}
    @Test void rejectsEmptyAndDegenerate(){assertThrows(SpatialException.class,()->validator.validate(geometry("Point","[]"),4326));assertThrows(SpatialException.class,()->validator.validate(geometry("LineString","[[80,6],[80,6]]"),4326));assertThrows(SpatialException.class,()->validator.validate(geometry("Polygon","[[[80,6],[81,6],[82,6],[80,6]]]"),4326));}
    @Test void rejectsUnsupportedRangesCrsAndAltitude(){for(String g:new String[]{geometry("Point","[181,6]"),geometry("Point","[80,91]"),geometry("Point","[80,6,100]"),geometry("GeometryCollection","[]")})assertThrows(SpatialException.class,()->validator.validate(g,4326));assertThrows(SpatialException.class,()->validator.validate(geometry("Point","[80,6]"),3857));}
    @Test void validatesCodeAndLayerUri(){assertEquals("BLOCK_1",SpatialService.code("BLOCK_1"));assertThrows(SpatialException.class,()->SpatialService.code("../BAD"));SpatialService.uri("https://assets.example.invalid/tiles/tileset.json",false);for(String uri:new String[]{"file:///etc/passwd","http://localhost/test","https://user:pass@example.invalid/model"})assertThrows(SpatialException.class,()->SpatialService.uri(uri,false));}
    @Test void enforcesCoordinateAndPaginationLimits(){assertThrows(SpatialException.class,()->new GeometryValidator(1).validate(geometry("LineString","[[80,6],[81,6]]"),4326));assertThrows(SpatialException.class,()->SpatialService.page(0,101));}
}
