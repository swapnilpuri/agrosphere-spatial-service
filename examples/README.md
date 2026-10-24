# Synthetic demonstration fixtures

All records are SIMULATED. Coordinates near 80E/6N are plausible WGS84 coordinates, not an operating plantation or survey. The estate and first division/block UUIDs match contracts 0.2.0 fixtures. Other block snapshots use the same v2 contracts. No Estate Service database is required.

Replay synthetic-estate-events.json through the estate RabbitMQ v2 routing keys, then POST synthetic-estate.geojson inside {estateId, sourceFilename, geojson} to /api/v1/spatial/imports/geojson. The 13 boundaries comprise one estate, two aligned divisions and ten nonoverlapping blocks. Import is atomic; repeat import returns a FAILED job rather than overwriting boundaries.

POST each synthetic-assets.json item to /api/v1/spatial/assets. There are two road markers, a latex collection facility (FACTORY), three sensor markers and one illustrative tree model. synthetic-roads.geojson contains the representative access-road centerlines for GIS clients. Asset URLs use the reserved .invalid domain and are metadata placeholders; no actual 3D asset or surveyed tree locations are claimed.

Areas are intentionally not fabricated in the input: PostGIS computes spheroidal hectares during import. The integration suite verifies area and exports target/api-contract-samples/export.json with computed areas, bounding boxes and centroids.
