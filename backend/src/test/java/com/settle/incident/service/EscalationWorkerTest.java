package com.settle.incident.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.settle.common.tenancy.OrgContext;
import com.settle.incident.entity.EscalationTaskState;
import com.settle.incident.repository.EscalationTaskRepository;
import com.settle.incident.repository.OverdueTask;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

/** One pass of the worker: each task in its own org, and a lost race or a failure never stops the rest (D48). */
class EscalationWorkerTest {

	private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

	@Test
	void eachTaskRunsInItsOwnOrgAndOneFailureDoesNotStopTheRest() {
		EscalationTaskRepository tasks = mock(EscalationTaskRepository.class);
		EscalationService escalations = mock(EscalationService.class);
		when(tasks.findOverdueTasks(EscalationTaskState.PENDING, NOW)).thenReturn(List.of(
				new OverdueTask(1L, 10L), new OverdueTask(2L, 20L), new OverdueTask(3L, 30L), new OverdueTask(4L, 40L)));
		Map<Long, Long> orgOfTask = new HashMap<>();
		when(escalations.escalateTaskInNewTransaction(anyLong(), eq(NOW))).thenAnswer(inv -> {
			long taskId = inv.getArgument(0);
			orgOfTask.put(taskId, OrgContext.current());
			return switch ((int) taskId) {
				case 1 -> true;
				case 2 -> throw new OptimisticLockingFailureException("acknowledged meanwhile");
				case 3 -> throw new IllegalStateException("database went away");
				default -> false;
			};
		});

		int processed = new EscalationWorker(tasks, escalations, Clock.fixed(NOW, ZoneOffset.UTC)).runOnce(NOW);

		assertThat(processed).isEqualTo(1);
		assertThat(orgOfTask).containsExactlyInAnyOrderEntriesOf(Map.of(1L, 10L, 2L, 20L, 3L, 30L, 4L, 40L));
	}

	@Test
	void theScheduledPassUsesTheClock() {
		EscalationTaskRepository tasks = mock(EscalationTaskRepository.class);
		EscalationService escalations = mock(EscalationService.class);
		when(tasks.findOverdueTasks(EscalationTaskState.PENDING, NOW)).thenReturn(List.of(new OverdueTask(1L, 10L)));
		when(escalations.escalateTaskInNewTransaction(1L, NOW)).thenReturn(true);

		new EscalationWorker(tasks, escalations, Clock.fixed(NOW, ZoneOffset.UTC)).checkOverdueEscalations();

		verify(escalations).escalateTaskInNewTransaction(eq(1L), any());
	}
}
