package com.agrosphere.spatial.adapter.in.web;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import com.agrosphere.spatial.application.*;
@RestController @RequestMapping("/api/v1/spatial")
public class SpatialController {
    private final SpatialService service;private final GeoJsonImports imports;
    public SpatialController(SpatialService service,GeoJsonImports imports){this.service=service;this.imports=imports;}
    @PostMapping("/features") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> createFeature(@RequestBody Requests.Feature body){return service.feature(null,body);}
    @GetMapping("/features/{id}") public Map<String,Object> feature(@PathVariable UUID id){return service.feature(id);}
    @PutMapping("/features/{id}") public Map<String,Object> updateFeature(@PathVariable UUID id,@RequestBody Requests.Feature body){return service.feature(id,body);}
    @GetMapping("/estates/{estateId}/features") public Map<String,Object> features(@PathVariable UUID estateId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return service.list(estateId,"features",page,size);}
    @GetMapping("/estates/{estateId}/boundaries") public Map<String,Object> boundaries(@PathVariable UUID estateId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return service.list(estateId,"boundaries",page,size);}
    @GetMapping("/blocks/{blockId}/geometry") public Map<String,Object> block(@PathVariable UUID blockId){return service.block(blockId,false);}
    @GetMapping("/blocks/{blockId}/history") public Map<String,Object> history(@PathVariable UUID blockId){return service.block(blockId,true);}
    @PostMapping("/query/point") public Map<String,Object> point(@RequestBody Requests.Query body){return service.query(body,"point");}
    @PostMapping("/query/intersects") public Map<String,Object> intersects(@RequestBody Requests.Query body){return service.query(body,"intersects");}
    @PostMapping("/query/within") public Map<String,Object> within(@RequestBody Requests.Query body){return service.query(body,"within");}
    @GetMapping("/estates/{estateId}/bbox") public Map<String,Object> bbox(@PathVariable UUID estateId){return service.bbox(estateId);}
    @PostMapping("/layers") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> createLayer(@RequestBody Requests.Layer body){return service.layer(null,body);}
    @GetMapping("/layers/{id}") public Map<String,Object> layer(@PathVariable UUID id){return service.metadata(id,"layers");}
    @PutMapping("/layers/{id}") public Map<String,Object> updateLayer(@PathVariable UUID id,@RequestBody Requests.Layer body){return service.layer(id,body);}
    @GetMapping("/estates/{estateId}/layers") public Map<String,Object> layers(@PathVariable UUID estateId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return service.list(estateId,"layers",page,size);}
    @PostMapping("/assets") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> createAsset(@RequestBody Requests.Asset body){return service.asset(body);}
    @GetMapping("/assets/{id}") public Map<String,Object> asset(@PathVariable UUID id){return service.metadata(id,"assets");}
    @GetMapping("/estates/{estateId}/assets") public Map<String,Object> assets(@PathVariable UUID estateId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return service.list(estateId,"assets",page,size);}
    @PostMapping("/imports/geojson") @ResponseStatus(HttpStatus.ACCEPTED) public Map<String,Object> importGeoJson(@RequestBody Requests.Import body){return imports.start(body);}
    @GetMapping("/imports/{jobId}") public Map<String,Object> importJob(@PathVariable UUID jobId){return service.metadata(jobId,"imports");}
    @GetMapping(value="/estates/{estateId}/export/geojson",produces="application/geo+json") public Map<String,Object> export(@PathVariable UUID estateId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="100") int size){return service.export(estateId,page,size);}
    @PostMapping("/transform") public Map<String,Object> transform(@RequestBody Requests.Transform body){return service.transform(body);}
}
