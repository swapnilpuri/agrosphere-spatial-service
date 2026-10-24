package com.agrosphere.spatial.domain;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.geojson.GeoJsonReader;
import org.locationtech.jts.operation.valid.IsValidOp;
public class GeometryValidator {
    private final int maxCoordinates;
    public GeometryValidator(int maxCoordinates){this.maxCoordinates=maxCoordinates;}
    public Geometry validate(String json,int srid) {
        if(srid!=4326) throw SpatialException.invalid("Canonical GeoJSON requires SRID 4326; use the explicit transform operation first");
        final Geometry g;
        try {g=new GeoJsonReader().read(json);}catch(Exception ex){throw SpatialException.invalid("Malformed GeoJSON geometry; polygon rings must be closed and contain at least four positions");}
        if(!(g instanceof Polygon || g instanceof MultiPolygon || g instanceof Point || g instanceof LineString))
            throw SpatialException.invalid("Supported geometry types: Polygon, MultiPolygon, Point, LineString");
        if(g.isEmpty() || g.getNumPoints()>maxCoordinates) throw SpatialException.invalid("Empty geometry or coordinate limit exceeded");
        for(var c:g.getCoordinates()) if(!Double.isFinite(c.x)||!Double.isFinite(c.y)||c.x < -180||c.x>180||c.y < -90||c.y>90||!Double.isNaN(c.getZ()))
            throw SpatialException.invalid("Use finite 2D WGS84 longitude/latitude coordinates within [-180,180] and [-90,90]");
        if(g.getEnvelopeInternal().getWidth()>180) throw SpatialException.invalid("Split antimeridian geometries before submission");
        var error=new IsValidOp(g).getValidationError();
        if(error!=null) throw SpatialException.invalid("Invalid geometry: "+error.getMessage()+" at "+error.getCoordinate());
        if(g instanceof Polygon || g instanceof MultiPolygon){if(g.getArea()<=0)throw SpatialException.invalid("Polygon must have nonzero area");}
        if(g instanceof LineString && g.getLength()==0) throw SpatialException.invalid("LineString must have nonzero length");
        g.setSRID(4326);return g;
    }
}
