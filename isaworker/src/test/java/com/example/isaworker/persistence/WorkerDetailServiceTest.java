package com.example.isaworker.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The entity, its mapping and the three database operations of the workers, on an in-memory database. */
@DataJpaTest
@Import(WorkerDetailService.class)
class WorkerDetailServiceTest {

    @Autowired
    private WorkerDetailService service;
    @Autowired
    private WorkerDetailRepository repository;

    @Test
    void savingTheNameCreatesTheRowOfTheProcessInstance() {
        WorkerDetail saved = service.saveName("tenant-a", "30", "Rajiv Verma");

        assertThat(saved.getId()).isNotNull();
        WorkerDetail found = service.find("tenant-a", "30");
        assertThat(found.getName()).isEqualTo("Rajiv Verma");
        assertThat(found.getAddress()).isNull();
        assertThat(found.getProcessInstanceId()).isEqualTo("30");
    }

    @Test
    void savingTheAddressUpdatesTheSameRow() {
        Long id = service.saveName("tenant-a", "30", "Rajiv Verma").getId();

        service.saveAddress("tenant-a", "30", "Bangalore");

        assertThat(repository.count()).isEqualTo(1);
        WorkerDetail found = service.find("tenant-a", "30");
        assertThat(found.getId()).isEqualTo(id);
        assertThat(found.getName()).isEqualTo("Rajiv Verma");
        assertThat(found.getAddress()).isEqualTo("Bangalore");
    }

    @Test
    void aTaskDeliveredTwiceUpdatesTheRowInsteadOfFailingOnTheUniqueKey() {
        service.saveName("tenant-a", "30", "First");
        service.saveName("tenant-a", "30", "Second");

        assertThat(repository.count()).isEqualTo(1);
        assertThat(service.find("tenant-a", "30").getName()).isEqualTo("Second");
    }

    @Test
    void theSameProcessInstanceIdInTwoTenantsAreTwoRows() {
        // Every tenant's engine numbers its process instances on its own: "30" exists in each of them.
        service.saveName("tenant-a", "30", "Rajiv Verma");
        service.saveName("tenant-b", "30", "Asha Rao");
        service.saveAddress("tenant-b", "30", "Mysuru");

        assertThat(repository.count()).isEqualTo(2);
        assertThat(service.find("tenant-a", "30").getName()).isEqualTo("Rajiv Verma");
        assertThat(service.find("tenant-a", "30").getAddress()).isNull();
        assertThat(service.find("tenant-b", "30").getName()).isEqualTo("Asha Rao");
        assertThat(service.find("tenant-b", "30").getAddress()).isEqualTo("Mysuru");
    }

    @Test
    void anAddressWithoutAnEarlierNameIsRefused() {
        assertThatThrownBy(() -> service.saveAddress("tenant-a", "31", "Bangalore"))
                .isInstanceOf(WorkerDetailNotFoundException.class)
                .hasMessageContaining("no row for process instance 31");
        assertThat(repository.count()).isZero();
    }

    @Test
    void findingAnUnknownInstanceIsAnErrorNotNull() {
        assertThatThrownBy(() -> service.find("tenant-a", "nope")).isInstanceOf(WorkerDetailNotFoundException.class);
        service.saveName("tenant-b", "nope", "x");
        assertThatThrownBy(() -> service.find("tenant-a", "nope")).isInstanceOf(WorkerDetailNotFoundException.class);
    }

    @Test
    void theDatabaseItselfRefusesASecondRowForTheSameInstance() {
        repository.saveAndFlush(new WorkerDetail("tenant-a", "30"));

        assertThatThrownBy(() -> repository.saveAndFlush(new WorkerDetail("tenant-a", "30")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
