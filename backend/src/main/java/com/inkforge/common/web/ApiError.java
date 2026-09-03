package com.inkforge.common.web;

/** Uniform error body for REST responses. */
public record ApiError(int status, String error, String message) {
}
