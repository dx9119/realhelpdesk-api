package com.ukhanov.realhelpdesk.feature.knowledgedbmanager.exception;

import org.springframework.http.HttpStatus;

import com.ukhanov.realhelpdesk.core.exception.ApiException;

public class KnowledgeBaseException extends ApiException {

    public KnowledgeBaseException(String message) {
        this(message, HttpStatus.CONFLICT);
    }

    public KnowledgeBaseException(String message, Throwable cause) {
        this(message, HttpStatus.CONFLICT, cause);
    }

    public KnowledgeBaseException(String message, HttpStatus status) {
        super(message, status);
    }

    public KnowledgeBaseException(String message, HttpStatus status, Throwable cause) {
        super(message, status, cause);
    }

    public static KnowledgeBaseException notFound(String message) {
        return new KnowledgeBaseException(message, HttpStatus.NOT_FOUND);
    }

    public static KnowledgeBaseException badRequest(String message) {
        return new KnowledgeBaseException(message, HttpStatus.BAD_REQUEST);
    }

    public static KnowledgeBaseException forbidden(String message) {
        return new KnowledgeBaseException(message, HttpStatus.FORBIDDEN);
    }

    public static KnowledgeBaseException conflict(String message) {
        return new KnowledgeBaseException(message, HttpStatus.CONFLICT);
    }

    public static KnowledgeBaseException preconditionFailed(String message) {
        return new KnowledgeBaseException(message, HttpStatus.PRECONDITION_FAILED);
    }
}
