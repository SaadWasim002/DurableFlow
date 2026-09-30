package com.durableflow.api;

import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.durableflow.workflow.WorkflowExceptions.Conflict;
import com.durableflow.workflow.WorkflowExceptions.InvalidWorkflow;
import com.durableflow.workflow.WorkflowExceptions.NotFound;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidWorkflow.class)
    public ProblemDetail invalidWorkflow(InvalidWorkflow exception) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid workflow", exception.getMessage());
    }

    @ExceptionHandler(NotFound.class)
    public ProblemDetail notFound(NotFound exception) {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", exception.getMessage());
    }

    @ExceptionHandler(Conflict.class)
    public ProblemDetail conflict(Conflict exception) {
        return problem(HttpStatus.CONFLICT, "Workflow version conflict", exception.getMessage());
    }

    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail persistenceFailure(DataAccessException exception) {
        log.error("Database operation failed", exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Database operation failed",
                "The database operation could not be completed.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted().collect(Collectors.joining("; "));
        return handleExceptionInternal(exception, problem(HttpStatus.BAD_REQUEST, "Invalid request", detail),
                headers, status, request);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
