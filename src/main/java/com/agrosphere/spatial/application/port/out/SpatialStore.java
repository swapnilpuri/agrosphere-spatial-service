package com.agrosphere.spatial.application.port.out;
import java.util.*;
import com.agrosphere.spatial.application.Requests;
public interface SpatialStore {
    Map<String,Object> feature(UUID id);
    Map<String,Object> saveFeature(UUID id,Requests.Feature request);
    Map<String,Object> list(UUID estate,String kind,int page,int size);
    Map<String,Object> block(UUID id,boolean history);
    Map<String,Object> query(Requests.Query query,String operation);
    Map<String,Object> bbox(UUID estate);
    Map<String,Object> metadata(UUID id,String kind);
    Map<String,Object> layer(UUID id,Requests.Layer request);
    Map<String,Object> asset(Requests.Asset request);
    Map<String,Object> export(UUID estate,int page,int size);
    void reference(UUID estate,UUID id,String type);
    Map<String,Object> transform(Requests.Transform request);
}
