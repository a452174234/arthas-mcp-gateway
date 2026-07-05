package com.arthas.gateway.admin.backend;

import com.arthas.gateway.admin.backend.exception.BackendAdminException;
import com.arthas.gateway.admin.backend.exception.BackendConflictException;
import com.arthas.gateway.admin.backend.exception.BackendNotFoundException;
import com.arthas.gateway.admin.task.exception.TaskNotCompletedException;
import com.arthas.gateway.admin.task.exception.TaskNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 004 管理面异常 → HTTP 映射（admin-api-contract §3 错误体格式，admin-invariants INV-ERR-1）。
 *
 * <p>全局 {@code @RestControllerAdvice}（覆盖 /admin/backends 与 /admin/tasks）。
 * 错误显式传播、结构化（状态码 + error + reason），不静默成功（宪法原则五）。
 */
@RestControllerAdvice
public class AdminExceptionHandler {

    @ExceptionHandler(BackendNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(BackendNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", e.getMessage(),
                "reason", "backend_not_found",
                "name", e.getName(),
                "available", e.getAvailable()));
    }

    @ExceptionHandler(BackendConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(BackendConflictException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", e.getMessage(),
                "reason", e.getReason()));
    }

    @ExceptionHandler(BackendAdminException.class)
    public ResponseEntity<Map<String, Object>> adminError(BackendAdminException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", e.getMessage(),
                "reason", "admin_io_error"));
    }

    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<Map<String, Object>> taskNotFound(TaskNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", e.getMessage(),
                "reason", "task_not_found",
                "taskId", e.getTaskId()));
    }

    @ExceptionHandler(TaskNotCompletedException.class)
    public ResponseEntity<Map<String, Object>> taskNotCompleted(TaskNotCompletedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", e.getMessage(),
                "reason", "task_not_completed",
                "taskId", e.getTaskId(),
                "status", e.getStatus()));
    }
}
