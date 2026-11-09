package com.example.isaworker.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * What the person entered for one process instance: the state that the three workers share through {@code workerdb}.
 * <p>
 * {@code tenantId} is not in the process, it is part of the key: every tenant has its own engine and its own process
 * instance numbering, so "process instance 30" exists once in EVERY tenant. Without the tenant two tenants would overwrite
 * each other's rows in this shared database.
 */
@Entity
@Table(name = "worker_detail",
        uniqueConstraints = @UniqueConstraint(name = "uk_worker_detail_instance", columnNames = {"tenant_id", "process_instance_id"}))
@Getter
@Setter
@NoArgsConstructor
@ToString
public class WorkerDetail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The tenant (engine) the process instance belongs to, as listed in fluxnova-engine.yml. */
    @Column(name = "tenant_id", nullable = false, length = 100)
    private String tenantId;

    /** The id of the process instance in that tenant's engine. */
    @Column(name = "process_instance_id", nullable = false, length = 100)
    private String processInstanceId;

    /** Entered by the "enter_name" task. */
    @Column(name = "name", length = 500)
    private String name;

    /** Entered by the "enter_address" task. */
    @Column(name = "address", length = 2000)
    private String address;

    public WorkerDetail(String tenantId, String processInstanceId) {
        this.tenantId = tenantId;
        this.processInstanceId = processInstanceId;
    }
}
