package com.agrosphere.spatial.application;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import com.agrosphere.spatial.domain.SpatialModels.*;
public final class Requests {
    private Requests() {}
    public record Feature(UUID estateId,UUID referenceEntityId,ReferenceType referenceEntityType,String featureCode,
        JsonNode geometry,Integer srid,Integer geometryVersion,FeatureStatus status,SourceType sourceType,String sourceReference,String dataProvenance) {}
    public record Layer(UUID estateId,String layerCode,String name,LayerType layerType,String sourceUri,String format,Boolean enabled,Integer version) {}
    public record Asset(UUID estateId,UUID spatialFeatureId,String assetCode,AssetType assetType,String assetUri,JsonNode position,
        Vector3 orientation,Vector3 scale,String status,String dataProvenance) {}
    public record Query(UUID estateId,JsonNode geometry,double[] bbox,Integer limit) {}
    public record Import(UUID estateId,String sourceFilename,JsonNode geojson) {}
    public record Transform(JsonNode geometry,Integer sourceSrid) {}
}
