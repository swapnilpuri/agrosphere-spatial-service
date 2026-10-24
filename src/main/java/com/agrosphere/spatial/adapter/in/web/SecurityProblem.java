package com.agrosphere.spatial.adapter.in.web;
import jakarta.servlet.http.*;
import java.io.IOException;
public final class SecurityProblem {
    private SecurityProblem() {}
    public static void write(HttpServletRequest request,HttpServletResponse response,int status) throws IOException {
        response.setStatus(status); response.setContentType("application/problem+json");
        if(status==401) response.setHeader("WWW-Authenticate","Bearer");
        String title=status==401?"Unauthorized":"Forbidden";
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\""+title+"\",\"status\":"+status+",\"detail\":\""+title+"\",\"correlationId\":\""+CorrelationFilter.current()+"\"}");
    }
}
