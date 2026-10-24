-- PostGIS is provisioned by infrastructure, not by the service's non-superuser role.
DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_extension WHERE extname='postgis') THEN RAISE EXCEPTION 'PostGIS must be provisioned in the spatial database by infrastructure'; END IF; END $$;
CREATE TABLE reference_entity (
 id uuid NOT NULL, estate_id uuid NOT NULL, entity_type varchar(16) NOT NULL CHECK(entity_type IN ('ESTATE','DIVISION','BLOCK')),
 parent_id uuid, status varchar(16) NOT NULL CHECK(status IN ('ACTIVE','INACTIVE')), revision bigint NOT NULL CHECK(revision>=0),
 inferred boolean NOT NULL DEFAULT false, PRIMARY KEY(id,entity_type), UNIQUE(id,estate_id,entity_type)
);
CREATE INDEX reference_estate_idx ON reference_entity(estate_id);
CREATE TABLE spatial_feature (
 id uuid PRIMARY KEY, estate_id uuid NOT NULL, reference_entity_id uuid NOT NULL, reference_entity_type varchar(16) NOT NULL,
 feature_code varchar(64) NOT NULL, geometry_type varchar(20) NOT NULL CHECK(geometry_type IN ('POLYGON','MULTIPOLYGON','POINT','LINESTRING')),
 geometry geometry(Geometry,4326) NOT NULL, srid integer NOT NULL DEFAULT 4326 CHECK(srid=4326),
 calculated_area_hectares numeric(20,8), geometry_version integer NOT NULL CHECK(geometry_version>=1),
 status varchar(16) NOT NULL CHECK(status IN ('ACTIVE','INACTIVE','SUPERSEDED')),
 source_type varchar(16) NOT NULL CHECK(source_type IN ('SURVEY','IMPORT','GENERATED')), source_reference varchar(500),
 data_provenance varchar(16) NOT NULL CHECK(data_provenance IN ('REAL','SIMULATED','ESTIMATED','PREDICTED')),
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 UNIQUE(id,estate_id), UNIQUE(estate_id,feature_code),
 FOREIGN KEY(reference_entity_id,estate_id,reference_entity_type) REFERENCES reference_entity(id,estate_id,entity_type),
 CHECK(NOT ST_IsEmpty(geometry) AND ST_IsValid(geometry)), CHECK(upper(GeometryType(geometry))=geometry_type),
 CHECK(ST_NDims(geometry)=2), CHECK(ST_XMin(Box3D(geometry))>=-180 AND ST_XMax(Box3D(geometry))<=180 AND ST_YMin(Box3D(geometry))>=-90 AND ST_YMax(Box3D(geometry))<=90)
);
CREATE UNIQUE INDEX feature_active_entity_uq ON spatial_feature(reference_entity_id,reference_entity_type) WHERE status='ACTIVE';
CREATE INDEX feature_geometry_gist ON spatial_feature USING gist(geometry);
CREATE INDEX feature_estate_reference_idx ON spatial_feature(estate_id,reference_entity_type,reference_entity_id);
CREATE TABLE spatial_feature_version (
 feature_id uuid NOT NULL REFERENCES spatial_feature(id), geometry_version integer NOT NULL, geometry geometry(Geometry,4326) NOT NULL,
 snapshot jsonb NOT NULL, valid_from timestamptz NOT NULL, valid_to timestamptz NOT NULL, status varchar(16) NOT NULL DEFAULT 'SUPERSEDED',
 PRIMARY KEY(feature_id,geometry_version), CHECK(valid_to>=valid_from)
);
CREATE INDEX feature_version_geometry_gist ON spatial_feature_version USING gist(geometry);
CREATE TABLE spatial_layer (
 id uuid PRIMARY KEY, estate_id uuid NOT NULL, layer_code varchar(64) NOT NULL, name varchar(200) NOT NULL,
 layer_type varchar(16) NOT NULL CHECK(layer_type IN ('BOUNDARY','TERRAIN','IMAGERY','VECTOR','HEATMAP','ASSET')),
 source_uri varchar(2048), format varchar(16) NOT NULL CHECK(format IN ('GEOJSON','3D_TILES','RASTER','OTHER')),
 enabled boolean NOT NULL, version integer NOT NULL DEFAULT 0, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 UNIQUE(estate_id,layer_code)
);
CREATE INDEX layer_estate_idx ON spatial_layer(estate_id,id);
CREATE TABLE digital_twin_asset (
 id uuid PRIMARY KEY, estate_id uuid NOT NULL, spatial_feature_id uuid, asset_code varchar(64) NOT NULL,
 asset_type varchar(24) NOT NULL CHECK(asset_type IN ('TREE_MODEL','BUILDING','ROAD','FACTORY','SENSOR_MARKER','OTHER')),
 asset_uri varchar(2048) NOT NULL, position geometry(Point,4326), orientation jsonb NOT NULL, scale jsonb NOT NULL,
 status varchar(16) NOT NULL CHECK(status IN ('ACTIVE','INACTIVE')), data_provenance varchar(16) NOT NULL CHECK(data_provenance IN ('REAL','SIMULATED','ESTIMATED','PREDICTED')),
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, UNIQUE(estate_id,asset_code),
 FOREIGN KEY(spatial_feature_id,estate_id) REFERENCES spatial_feature(id,estate_id)
);
CREATE INDEX asset_position_gist ON digital_twin_asset USING gist(position);
CREATE INDEX asset_estate_idx ON digital_twin_asset(estate_id,id);
CREATE TABLE spatial_import_job (
 id uuid PRIMARY KEY, estate_id uuid NOT NULL, source_filename varchar(200) NOT NULL, source_type varchar(16) NOT NULL CHECK(source_type IN ('GEOJSON','CSV')),
 status varchar(16) NOT NULL CHECK(status IN ('PENDING','VALIDATING','COMPLETED','FAILED')), imported_count integer NOT NULL DEFAULT 0,
 rejected_count integer NOT NULL DEFAULT 0, validation_report jsonb NOT NULL DEFAULT '{}', created_at timestamptz NOT NULL, completed_at timestamptz
);
CREATE INDEX import_estate_idx ON spatial_import_job(estate_id,created_at);
ALTER TABLE outbox_event ADD COLUMN delivery_status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(delivery_status IN ('PENDING','PUBLISHED','FAILED'));
ALTER TABLE outbox_event ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now();
UPDATE outbox_event SET delivery_status='PUBLISHED' WHERE published_at IS NOT NULL;
CREATE INDEX outbox_due_idx ON outbox_event(next_attempt_at,occurred_at) WHERE delivery_status='PENDING';
