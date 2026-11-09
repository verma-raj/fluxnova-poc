package com.example.isaworker.persistence;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reads and writes of the three workers. Every method is one short transaction: a connection is held for the length of a
 * query, never while a dialog is open.
 * <p>
 * A task can be delivered twice (a lock that ran out, a failed completion that the engine retries), so writing the name is
 * an upsert: the second delivery updates the row instead of tripping over the unique key.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerDetailService {

    private final WorkerDetailRepository repository;

    /** "enter_name": creates the row of the process instance, or updates the name if the row exists already. */
    @Transactional
    public WorkerDetail saveName(String tenantId, String processInstanceId, String name) {
        WorkerDetail detail = repository.findByTenantIdAndProcessInstanceId(tenantId, processInstanceId)
                .orElseGet(() -> new WorkerDetail(tenantId, processInstanceId));
        detail.setName(name);
        WorkerDetail saved = repository.save(detail);
        log.debug("[{}] Saved the name of process instance {} (row {})", tenantId, processInstanceId, saved.getId());
        return saved;
    }

    /**
     * "enter_address": updates the row of the process instance with the address.
     *
     * @throws WorkerDetailNotFoundException if there is no row, i.e. the name step did not run for this instance
     */
    @Transactional
    public WorkerDetail saveAddress(String tenantId, String processInstanceId, String address) {
        WorkerDetail detail = find(tenantId, processInstanceId);
        detail.setAddress(address);
        WorkerDetail saved = repository.save(detail);
        log.debug("[{}] Saved the address of process instance {} (row {})", tenantId, processInstanceId, saved.getId());
        return saved;
    }

    /**
     * "show_details": the row of the process instance.
     *
     * @throws WorkerDetailNotFoundException if there is none
     */
    @Transactional(readOnly = true)
    public WorkerDetail find(String tenantId, String processInstanceId) {
        return repository.findByTenantIdAndProcessInstanceId(tenantId, processInstanceId)
                .orElseThrow(() -> new WorkerDetailNotFoundException(tenantId, processInstanceId));
    }
}
