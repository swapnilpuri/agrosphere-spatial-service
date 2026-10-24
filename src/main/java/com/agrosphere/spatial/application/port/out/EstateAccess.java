package com.agrosphere.spatial.application.port.out;
import java.util.UUID;
public interface EstateAccess {String actor();void read(UUID estateId);void write(UUID estateId);}
