package com.example.isaworker.worker;

import com.example.isaworker.gui.DialogService;
import com.example.isaworker.persistence.WorkerDetail;
import com.example.isaworker.persistence.WorkerDetailNotFoundException;
import com.example.isaworker.persistence.WorkerDetailService;
import org.finos.fluxnova.bpm.client.task.ExternalTask;
import org.finos.fluxnova.bpm.client.task.ExternalTaskService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The three interactive workers, with the dialogs, the database and the engine replaced by mocks. */
class InteractiveWorkersTest {

    private static final long RETRY_MILLIS = 30_000;

    private final WorkerDetailService details = mock(WorkerDetailService.class);
    private final DialogService dialogs = mock(DialogService.class);
    private final ExternalTaskService engine = mock(ExternalTaskService.class);

    private static ExternalTask task(String topic, Integer retries) {
        ExternalTask task = mock(ExternalTask.class);
        when(task.getId()).thenReturn("task-7");
        when(task.getTopicName()).thenReturn(topic);
        when(task.getProcessInstanceId()).thenReturn("42");
        when(task.getRetries()).thenReturn(retries);
        return task;
    }

    private static WorkerDetail row(String name, String address) {
        WorkerDetail detail = new WorkerDetail("tenant-b", "42");
        detail.setName(name);
        detail.setAddress(address);
        return detail;
    }

    // --- Worker 1: enter_name ------------------------------------------------------------------------------------------

    @Test
    void enterName_savesTheNameToWorkerdbAndCompletesTheTaskWithoutAnyProcessVariable() {
        ExternalTask task = task("enter-name-topic", null);
        when(dialogs.askText("Please enter your name:", task, "name")).thenReturn(Optional.of("Rajiv Verma"));

        new EnterNameWorker("tenant-b", details, dialogs, RETRY_MILLIS, false).execute(task, engine);

        verify(details).saveName("tenant-b", "42", "Rajiv Verma");
        verify(engine).complete(task, Map.of());                 // the name is business data: it stays in workerdb
        verify(engine, never()).handleFailure(any(ExternalTask.class), anyString(), anyString(), anyInt(), anyLong());
    }

    @Test
    void whenSwitchedOnTheWorkersAlsoSetTheProcessVariables() {
        // isaworker.workers.set-process-variables=true: the values then ALSO live in the engine's database.
        ExternalTask nameTask = task("enter-name-topic", null);
        when(dialogs.askText("Please enter your name:", nameTask, "name")).thenReturn(Optional.of("Rajiv Verma"));
        new EnterNameWorker("tenant-b", details, dialogs, RETRY_MILLIS, true).execute(nameTask, engine);
        verify(engine).complete(nameTask, Map.<String, Object>of("name", "Rajiv Verma"));

        ExternalTask addressTask = task("enter-address-topic", null);
        when(details.find("tenant-b", "42")).thenReturn(row("Rajiv Verma", null));
        when(dialogs.askText("Please enter your address:", addressTask, "address")).thenReturn(Optional.of("Bangalore"));
        new EnterAddressWorker("tenant-b", details, dialogs, RETRY_MILLIS, true).execute(addressTask, engine);
        verify(engine).complete(addressTask, Map.<String, Object>of("address", "Bangalore"));
    }

    @Test
    void enterName_aCancelledDialogWritesNothingAndIsReportedToTheEngineAsAFailure() {
        ExternalTask task = task("enter-name-topic", 3);
        when(dialogs.askText(anyString(), any(ExternalTask.class), anyString())).thenReturn(Optional.empty());

        new EnterNameWorker("tenant-b", details, dialogs, RETRY_MILLIS, false).execute(task, engine);

        verify(details, never()).saveName(anyString(), anyString(), anyString());
        verify(engine, never()).complete(any(ExternalTask.class), any());
        // One retry less, the configured pause, a readable message and no stack trace for a plain cancel.
        verify(engine).handleFailure(eq(task), eq("The name dialog was cancelled or closed without an answer"),
                argThat(errorDetails -> !errorDetails.contains("\tat ")), eq(2), eq(RETRY_MILLIS));
    }

    @Test
    void enterName_whenTheLastRetryIsUsedTheCountIsZeroSoTheEngineRaisesAnIncident() {
        ExternalTask task = task("enter-name-topic", 1);
        when(dialogs.askText(anyString(), any(ExternalTask.class), anyString())).thenReturn(Optional.empty());

        new EnterNameWorker("tenant-b", details, dialogs, RETRY_MILLIS, false).execute(task, engine);

        verify(engine).handleFailure(eq(task), anyString(), anyString(), eq(0), eq(RETRY_MILLIS));
    }

    @Test
    void enterName_noDisplayIsAFailureWithTheReasonNotACrash() {
        ExternalTask task = task("enter-name-topic", null);
        when(dialogs.askText(anyString(), any(ExternalTask.class), anyString()))
                .thenThrow(new IllegalStateException("No display is available"));

        new EnterNameWorker("tenant-b", details, dialogs, RETRY_MILLIS, false).execute(task, engine);

        verify(engine).handleFailure(eq(task), eq("No display is available"), anyString(), eq(3), eq(RETRY_MILLIS));
    }

    @Test
    void enterName_aDatabaseErrorIsAFailureAndTheTaskIsNotCompleted() {
        ExternalTask task = task("enter-name-topic", 3);
        when(dialogs.askText(anyString(), any(ExternalTask.class), anyString())).thenReturn(Optional.of("Rajiv Verma"));
        doThrow(new IllegalStateException("connection refused")).when(details).saveName(anyString(), anyString(), anyString());

        new EnterNameWorker("tenant-b", details, dialogs, RETRY_MILLIS, false).execute(task, engine);

        verify(engine, never()).complete(any(ExternalTask.class), any());
        verify(engine).handleFailure(eq(task), eq("connection refused"), anyString(), eq(2), eq(RETRY_MILLIS));
    }

    // --- Worker 2: enter_address ---------------------------------------------------------------------------------------

    @Test
    void enterAddress_updatesTheRowAndCompletesTheTaskWithoutAnyProcessVariable() {
        ExternalTask task = task("enter-address-topic", null);
        when(details.find("tenant-b", "42")).thenReturn(row("Rajiv Verma", null));
        when(dialogs.askText("Please enter your address:", task, "address")).thenReturn(Optional.of("Bangalore"));

        new EnterAddressWorker("tenant-b", details, dialogs, RETRY_MILLIS, false).execute(task, engine);

        verify(details).saveAddress("tenant-b", "42", "Bangalore");
        verify(engine).complete(task, Map.of());                 // the address is business data: it stays in workerdb
    }

    @Test
    void enterAddress_aCancelledDialogIsReportedAsAFailure() {
        ExternalTask task = task("enter-address-topic", 3);
        when(details.find("tenant-b", "42")).thenReturn(row("Rajiv Verma", null));
        when(dialogs.askText(anyString(), any(ExternalTask.class), anyString())).thenReturn(Optional.empty());

        new EnterAddressWorker("tenant-b", details, dialogs, RETRY_MILLIS, false).execute(task, engine);

        verify(details, never()).saveAddress(anyString(), anyString(), anyString());
        verify(engine).handleFailure(eq(task), eq("The address dialog was cancelled or closed without an answer"), anyString(),
                eq(2), eq(RETRY_MILLIS));
    }

    @Test
    void enterAddress_withoutARowTheDialogIsNotEvenShown() {
        ExternalTask task = task("enter-address-topic", null);
        when(details.find("tenant-b", "42")).thenThrow(new WorkerDetailNotFoundException("tenant-b", "42"));

        new EnterAddressWorker("tenant-b", details, dialogs, RETRY_MILLIS, false).execute(task, engine);

        verify(dialogs, never()).askText(anyString(), any(ExternalTask.class), anyString());
        verify(engine).handleFailure(eq(task), argThat(message -> message.contains("no row for process instance 42")),
                anyString(), eq(3), eq(RETRY_MILLIS));
    }

    // --- Worker 3: show_details ----------------------------------------------------------------------------------------

    @Test
    void showDetails_readsTheRowShowsItReadOnlyAndCompletesTheTask() {
        ExternalTask task = task("show-details-topic", null);
        when(details.find("tenant-b", "42")).thenReturn(row("Rajiv Verma", "Bangalore"));

        new ShowDetailsWorker("tenant-b", details, dialogs, RETRY_MILLIS).execute(task, engine);

        verify(dialogs).showReadOnly("Worker details", "Name: Rajiv Verma\nAddress: Bangalore", task);
        verify(engine).complete(task, Map.of());
    }

    @Test
    void showDetails_aMissingRowIsAFailure() {
        ExternalTask task = task("show-details-topic", 3);
        when(details.find("tenant-b", "42")).thenThrow(new WorkerDetailNotFoundException("tenant-b", "42"));

        new ShowDetailsWorker("tenant-b", details, dialogs, RETRY_MILLIS).execute(task, engine);

        verify(dialogs, never()).showReadOnly(anyString(), anyString(), any(ExternalTask.class));
        verify(engine, never()).complete(any(ExternalTask.class), any());
        verify(engine).handleFailure(eq(task), argThat(message -> message.contains("no row for process instance 42")),
                anyString(), eq(2), eq(RETRY_MILLIS));
    }

    @Test
    void showDetails_aDialogThatCannotBeShownIsAFailureAndTheTaskStaysOpen() {
        ExternalTask task = task("show-details-topic", null);
        when(details.find("tenant-b", "42")).thenReturn(row("Rajiv Verma", "Bangalore"));
        doThrow(new IllegalStateException("No display is available")).when(dialogs)
                .showReadOnly(anyString(), anyString(), any(ExternalTask.class));

        new ShowDetailsWorker("tenant-b", details, dialogs, RETRY_MILLIS).execute(task, engine);

        verify(engine, never()).complete(any(ExternalTask.class), any());
        verify(engine).handleFailure(eq(task), eq("No display is available"), anyString(), eq(3), eq(RETRY_MILLIS));
    }
}
