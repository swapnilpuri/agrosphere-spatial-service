package com.agrosphere.spatial.adapter.out.messaging;
import java.util.*;
import io.opentelemetry.context.*;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
final class TraceTransport {
    private TraceTransport() {}
    static Map<String,String> capture() {
        Map<String,String> headers=new HashMap<>();
        W3CTraceContextPropagator.getInstance().inject(Context.current(),headers,Map::put);
        return headers;
    }
    static Scope restore(String parent,String state) {
        Map<String,String> headers=new HashMap<>();
        if(parent!=null)headers.put("traceparent",parent);if(state!=null)headers.put("tracestate",state);
        return W3CTraceContextPropagator.getInstance().extract(Context.root(),headers,new TextMapGetter<Map<String,String>>() {
            public Iterable<String> keys(Map<String,String> carrier){return carrier.keySet();}
            public String get(Map<String,String> carrier,String key){return carrier.get(key);}
        }).makeCurrent();
    }
}
