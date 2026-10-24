package com.agrosphere.spatial.adapter.in.web;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import com.agrosphere.spatial.domain.SpatialException;
@RestControllerAdvice
public class ApiErrors {
    private ProblemDetail problem(int status,String detail){var p=ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status),detail);p.setProperty("correlationId",CorrelationFilter.current().toString());return p;}
    @ExceptionHandler(SpatialException.class) ProblemDetail spatial(SpatialException e){return problem(e.status(),e.getMessage());}
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class) ProblemDetail denied(Exception e){return problem(403,"Estate access denied");}
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class) ProblemDetail conflict(Exception e){return problem(409,"Duplicate active assignment, code or invalid spatial relationship");}
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class}) ProblemDetail invalid(Exception e){return problem(400,"Invalid JSON request or parameter type");}
    @ExceptionHandler(Exception.class) ProblemDetail unknown(Exception e){if(e instanceof org.springframework.web.ErrorResponse response)return problem(response.getStatusCode().value(),response.getStatusCode().toString());org.slf4j.LoggerFactory.getLogger(ApiErrors.class).error("Request failed correlationId={} reason={}",CorrelationFilter.current(),e.getClass().getSimpleName());return problem(500,"Unexpected service error");}
}
