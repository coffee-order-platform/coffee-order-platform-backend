package com.coffeeorder.global.exception;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 모든 예외를 {status, code, message} 응답으로 바꾼다.
 * message 는 항상 ErrorCode 의 고정 문구이고, 상세 내용은 로그에만 남긴다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final int MAX_LOG_LENGTH = 200;

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        log.warn("업무 예외: {}", e.getErrorCode());
        return toResponse(e.getErrorCode(), HttpHeaders.EMPTY);
    }

    /** JSON 깨짐, 값 검증 실패, 숫자 자리에 문자, 필수 파라미터 누락 */
    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HandlerMethodValidationException.class,
            ConstraintViolationException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class
    })
    public ResponseEntity<ErrorResponse> handleInvalidRequest(Exception e) {
        log.warn("잘못된 요청 {}: {}", e.getClass().getSimpleName(), clean(e.getMessage()));
        return toResponse(ErrorCode.INVALID_REQUEST, HttpHeaders.EMPTY);
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(Exception e) {
        log.warn("없는 주소 {}", clean(e.getMessage()));
        return toResponse(ErrorCode.NOT_FOUND, HttpHeaders.EMPTY);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        log.warn("지원하지 않는 요청 방식 {}", clean(e.getMessage()));
        return toResponse(ErrorCode.METHOD_NOT_ALLOWED, e.getHeaders()); // Allow 헤더 보존
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        log.warn("지원하지 않는 요청 형식 {}", clean(e.getMessage()));
        return toResponse(ErrorCode.UNSUPPORTED_MEDIA_TYPE, e.getHeaders());
    }

    /** Accept 가 JSON 을 받지 않는 요청. 응답 본문은 그래도 JSON 으로 쓴다(toResponse). */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ErrorResponse> handleNotAcceptable(HttpMediaTypeNotAcceptableException e) {
        log.warn("지원하지 않는 응답 형식 {}", clean(e.getMessage()));
        return toResponse(ErrorCode.NOT_ACCEPTABLE, HttpHeaders.EMPTY);
    }

    /** 마지막 안전망. 내부 메시지는 응답에 넣지 않는다. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("예상하지 못한 오류", e);
        return toResponse(ErrorCode.INTERNAL_SERVER_ERROR, HttpHeaders.EMPTY);
    }

    /** Content-Type 을 JSON 으로 고정해서 Accept 헤더와 상관없이 항상 JSON 본문을 쓴다. */
    private ResponseEntity<ErrorResponse> toResponse(ErrorCode errorCode, HttpHeaders headers) {
        return ResponseEntity.status(errorCode.getStatus())
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of(errorCode));
    }

    /** 사용자 입력이 섞인 메시지가 로그에서 줄을 위조하지 못하게 한 줄로 만들고 길이를 제한한다. */
    static String clean(String message) {
        if (message == null) {
            return "";
        }
        String oneLine = message.replaceAll("[\\r\\n\\t]+", " ");
        return oneLine.length() > MAX_LOG_LENGTH ? oneLine.substring(0, MAX_LOG_LENGTH) + "..." : oneLine;
    }
}
