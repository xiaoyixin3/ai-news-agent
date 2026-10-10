package cn.zaozhi.controller;

import cn.zaozhi.Json;
import cn.zaozhi.Settings;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import com.fasterxml.jackson.databind.node.ObjectNode;

@RestControllerAdvice
public class ErrorAdvice {
    private final Settings settings;
    public ErrorAdvice(Settings settings) { this.settings = settings; }
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ObjectNode> api(ApiException e) { return ResponseEntity.status(e.status).body(Json.object("error", settings.redact(e.getMessage()))); }
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ObjectNode> input(Exception e) {
        return ResponseEntity.badRequest().body(Json.object("error", e instanceof HttpMessageNotReadableException ? "请求 JSON 格式不正确" : settings.redact(e.getMessage())));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ObjectNode> other(Exception e) { return ResponseEntity.internalServerError().body(Json.object("error", "服务异常，请查看运行记录或稍后重试")); }
}
