package com.example.isaworker.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Spring Data JPA access to {@code worker_detail}. */
public interface WorkerDetailRepository extends JpaRepository<WorkerDetail, Long> {

    /** The row of one process instance of one tenant. */
    Optional<WorkerDetail> findByTenantIdAndProcessInstanceId(String tenantId, String processInstanceId);
}
