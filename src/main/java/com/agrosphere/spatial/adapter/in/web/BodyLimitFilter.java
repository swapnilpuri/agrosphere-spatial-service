package com.agrosphere.spatial.adapter.in.web;
import java.io.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
@Component @Order(Ordered.HIGHEST_PRECEDENCE+1)
public class BodyLimitFilter extends OncePerRequestFilter {
    private final int max;
    public BodyLimitFilter(@Value("${spatial.limits.body-bytes:2097152}") int max){if(max<1||max>16777216)throw new IllegalArgumentException("Invalid body limit");this.max=max;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException{
        if(!java.util.Set.of("POST","PUT","PATCH").contains(request.getMethod())){chain.doFilter(request,response);return;}
        if(request.getContentLengthLong()>max){reject(response);return;}
        byte[] body=request.getInputStream().readNBytes(max+1);if(body.length>max){reject(response);return;}
        chain.doFilter(new HttpServletRequestWrapper(request){
            @Override public ServletInputStream getInputStream(){var input=new ByteArrayInputStream(body);return new ServletInputStream(){public int read(){return input.read();}public boolean isFinished(){return input.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){throw new UnsupportedOperationException();}};}
            @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8));}
        },response);
    }
    private void reject(HttpServletResponse response)throws IOException{response.setStatus(413);response.setContentType("application/problem+json");response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Payload Too Large\",\"status\":413,\"detail\":\"Request exceeds configured byte limit\",\"correlationId\":\""+CorrelationFilter.current()+"\"}");}
}
