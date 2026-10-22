package com.hui.huiaicodemother.langgraph4j;

import com.hui.huiaicodemother.langgraph4j.model.QualityResult;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkflowStreamingTest extends WorkflowTestSupport {
    @Test
    void successfulStreamHasOneStartOneCompletionAndQualityProgress() {
        var events = workflow.executeWorkflowEvents("site", appId).collectList().block(Duration.ofSeconds(10));
        assertNotNull(events);
        assertEquals("workflow_start", events.getFirst().event());
        assertEquals("workflow_completed", events.getLast().event());
        assertEquals(1, events.stream().filter(e -> "workflow_completed".equals(e.event())).count());
        assertTrue(events.stream().anyMatch(e -> "code_quality_check".equals(e.data().get("node"))));
        assertFalse(events.stream().anyMatch(e -> "workflow_error".equals(e.event())));
    }

    @Test
    void failedStreamSendsOneSafeTerminalErrorAndNeverCompletion() {
        when(ai.generateHtmlCodeStream(anyString())).thenReturn(Flux.error(new IllegalStateException("private-model-key-and-url")));
        var events = workflow.executeWorkflowEvents("site", appId).collectList().block(Duration.ofSeconds(10));
        assertEquals("workflow_error", events.getLast().event());
        assertEquals(1, events.stream().filter(e -> "workflow_error".equals(e.event())).count());
        assertFalse(events.toString().contains("private-model-key-and-url"));
        assertFalse(events.stream().anyMatch(e -> "workflow_completed".equals(e.event())));
    }

    @Test
    void retryExhaustionIsAnErrorEvent() {
        when(quality.checkCodeQuality(anyString())).thenReturn(QualityResult.builder().isValid(false).build());
        var events = workflow.executeWorkflowEvents("site", appId).collectList().block(Duration.ofSeconds(10));
        assertEquals("workflow_error", events.getLast().event());
        verify(ai, times(3)).generateHtmlCodeStream(anyString());
    }

    @Test
    void cancellingTheSubscriptionInterruptsWorkerAndStopsFollowingNodes() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        when(planner.planImageCollection(anyString())).thenAnswer(invocation -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stopped.countDown();
                throw new CancellationException();
            }
            return new com.hui.huiaicodemother.langgraph4j.model.ImageCollectionPlan();
        });
        var subscription = workflow.executeWorkflowEvents("site", appId).subscribe();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            subscription.dispose();
            assertTrue(stopped.await(5, TimeUnit.SECONDS));
            verify(ai, never()).generateHtmlCodeStream(anyString());
            verifyNoInteractions(quality, builder);
        } finally { subscription.dispose(); }
    }

    @Test
    void reusingAColdPublisherAllocatesASeparateApplicationIdPerSubscription() {
        var publisher = workflow.executeWorkflowEvents("site");
        var first = publisher.blockLast(Duration.ofSeconds(10));
        var second = publisher.blockLast(Duration.ofSeconds(10));
        assertEquals("workflow_completed", first.event());
        assertEquals("workflow_completed", second.event());
        assertNotEquals(first.data().get("appId"), second.data().get("appId"));
    }

    @Test
    void rawFluxServiceFramesRemainCompatibleWithSseConsumers() {
        List<String> frames = workflow.executeWorkflowWithFlux("site", appId).collectList().block(Duration.ofSeconds(10));
        assertTrue(frames.getFirst().startsWith("event: workflow_start\ndata: {"));
        assertTrue(frames.getLast().startsWith("event: workflow_completed\ndata: {"));
        assertTrue(frames.stream().allMatch(frame -> frame.endsWith("\n\n")));
    }
}
