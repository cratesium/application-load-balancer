package com.example.loadbalancer.exception;

import org.springframework.http.HttpStatus;

/**
 * The incoming request was rejected before routing: unparseable target, illegal header
 * characters, or a header set that would let the client smuggle a second request past the
 * backend's parser.
 */
public final class InvalidRequestException extends LoadBalancerException {

    public InvalidRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }
}
