package com.taskmgmt.repository;

import com.taskmgmt.entity.TaskPriorityPrediction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TaskPriorityPredictionRepository extends JpaRepository<TaskPriorityPrediction, Long> {
    Optional<TaskPriorityPrediction> findTopByTaskIdOrderByCreatedAtDesc(UUID taskId);
}