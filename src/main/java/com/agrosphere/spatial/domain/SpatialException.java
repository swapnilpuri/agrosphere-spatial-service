package com.agrosphere.spatial.domain;
public class SpatialException extends RuntimeException {
    private final int status;
    public SpatialException(int status,String message){super(message);this.status=status;}
    public int status(){return status;}
    public static SpatialException invalid(String message){return new SpatialException(400,message);}
}
