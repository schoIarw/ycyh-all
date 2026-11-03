package com.ycyh.oversight.v9;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = {V9Controller.class, V9AuthorityController.class})
public class V9Errors {
  @ExceptionHandler({
    IllegalArgumentException.class,
    MissingServletRequestParameterException.class,
    MethodArgumentTypeMismatchException.class,
    HttpMessageNotReadableException.class
  })
  public ObjectNode invalid(Exception e) {
    return V9Json.fail(e instanceof IllegalArgumentException ? e.getMessage() : "invalid.request");
  }

  @ExceptionHandler(Exception.class)
  public ObjectNode failed(Exception e) {
    org.slf4j.LoggerFactory.getLogger(V9Errors.class).error("V9 operation failed", e);
    return V9Json.fail("operation.failed");
  }
}
