package com.agrosphere.spatial.application.port.out;
import java.util.*;
public interface EventOutbox {void append(String type,UUID estate,UUID reference,Integer geometryVersion,Map<String,Object> payload);}
