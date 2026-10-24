package com.agrosphere.spatial.domain;
import java.util.*;
import java.time.Instant;
import java.math.BigDecimal;
public final class SpatialModels {
    private SpatialModels() {}
    public enum ReferenceType { ESTATE, DIVISION, BLOCK }
    public enum GeometryType { POLYGON, MULTIPOLYGON, POINT, LINESTRING }
    public enum FeatureStatus { ACTIVE, SUPERSEDED, INACTIVE }
    public enum SourceType { SURVEY, IMPORT, GENERATED }
    public enum LayerType { BOUNDARY, TERRAIN, IMAGERY, VECTOR, HEATMAP, ASSET }
    public enum AssetType { TREE_MODEL, BUILDING, ROAD, FACTORY, SENSOR_MARKER, OTHER }
    public record SpatialFeature(UUID id, UUID estateId, UUID referenceEntityId, ReferenceType referenceEntityType,
        String featureCode, GeometryType geometryType, String geometry, int srid, BigDecimal calculatedAreaHectares,
        int geometryVersion, FeatureStatus status, SourceType sourceType, String sourceReference, Instant createdAt, Instant updatedAt) {}
    public record SpatialLayer(UUID id,UUID estateId,String layerCode,String name,LayerType layerType,String sourceUri,
        String format,boolean enabled,Instant createdAt,Instant updatedAt) {}
    public record Vector3(double x,double y,double z) {}
    public record DigitalTwinAsset(UUID id,UUID estateId,UUID spatialFeatureId,String assetCode,AssetType assetType,
        String assetUri,String position,Vector3 orientation,Vector3 scale,String status,Instant createdAt,Instant updatedAt) {}
    public record SpatialImportJob(UUID id,UUID estateId,String sourceFilename,String sourceType,String status,
        int importedCount,int rejectedCount,String validationReport,Instant createdAt,Instant completedAt) {}
}
