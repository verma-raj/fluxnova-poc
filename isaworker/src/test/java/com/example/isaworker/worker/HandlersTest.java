package com.example.isaworker.worker;

import org.finos.fluxnova.bpm.client.task.ExternalTask;
import org.finos.fluxnova.bpm.client.task.ExternalTaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class HandlersTest {

    private final ExternalTaskService service = mock(ExternalTaskService.class);

    private static ExternalTask task(String topic, Integer retries, Object customerName) {
        ExternalTask task = mock(ExternalTask.class);
        when(task.getId()).thenReturn("task-1");
        when(task.getTopicName()).thenReturn(topic);
        when(task.getProcessInstanceId()).thenReturn("pi-1");
        when(task.getActivityId()).thenReturn("activity");
        when(task.getRetries()).thenReturn(retries);
        when(task.getVariable("customerName")).thenReturn(customerName);
        return task;
    }

    @Test
    void nameWorkerLogsTheTenantAndReturnsCustomerName(CapturedOutput output) {
        ExternalTask task = task("return-name", null, null);

        new NameWorker("tenant-b").execute(task, service);

        verify(service).complete(task, Map.<String, Object>of("customerName", "Rajiv Verma"));
        assertThat(output).contains("[tenant-b]").contains("Executing 'return-name' for tenant tenant-b")
                .contains("customerName").contains("Rajiv Verma");
    }

    @Test
    void addressWorkerReadsAndLogsCustomerNameAndReturnsCustomerAddress(CapturedOutput output) {
        ExternalTask task = task("return-address", null, "Rajiv Verma");

        new AddressWorker("tenant-b").execute(task, service);

        verify(service).complete(task, Map.<String, Object>of("customerAddress", "Bangalore"));
        assertThat(output).contains("customer is \"Rajiv Verma\"").contains("Bangalore");
    }

    @Test
    void addressWorkerStillCompletesButWarnsWhenTheNameIsMissing(CapturedOutput output) {
        ExternalTask task = task("return-address", null, null);

        new AddressWorker("tenant-b").execute(task, service);

        verify(service).complete(task, Map.<String, Object>of("customerAddress", "Bangalore"));
        assertThat(output).contains("variable 'customerName' is not set");
    }

    @Test
    void aFailingCompletionIsReportedToTheEngineWithOneRetryLess() {
        ExternalTask task = task("return-name", 3, null);
        doThrow(new IllegalStateException("engine said no")).when(service).complete(any(ExternalTask.class), any());

        new NameWorker("tenant-b").execute(task, service);       // must not throw into the polling thread

        verify(service).handleFailure(eq(task), eq("engine said no"), anyString(), eq(2), eq(10_000L));
    }

    @Test
    void theFirstFailureOfATaskWithoutRetryCountStartsWithThree() {
        ExternalTask task = task("return-name", null, null);
        doThrow(new IllegalStateException("boom")).when(service).complete(any(ExternalTask.class), any());

        new NameWorker("tenant-b").execute(task, service);

        verify(service).handleFailure(eq(task), eq("boom"), anyString(), eq(3), anyLong());
    }

    @Test
    void whenTheRetriesAreUsedUpTheCountIsZeroSoTheEngineRaisesAnIncident() {
        ExternalTask task = task("return-name", 1, null);
        doThrow(new IllegalStateException("boom")).when(service).complete(any(ExternalTask.class), any());

        new NameWorker("tenant-b").execute(task, service);

        verify(service).handleFailure(eq(task), eq("boom"), anyString(), eq(0), anyLong());
    }

    @Test
    void ifEvenTheFailureReportFailsNothingEscapes(CapturedOutput output) {
        ExternalTask task = task("return-name", 3, null);
        doThrow(new IllegalStateException("complete failed")).when(service).complete(any(ExternalTask.class), any());
        doThrow(new IllegalStateException("engine unreachable")).when(service)
                .handleFailure(any(ExternalTask.class), anyString(), anyString(), anyInt(), anyLong());

        new NameWorker("tenant-b").execute(task, service);

        assertThat(output).contains("the failure could not be reported either");
        verify(service, never()).handleBpmnError(any(ExternalTask.class), anyString());
    }
}
