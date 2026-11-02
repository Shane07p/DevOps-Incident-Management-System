package com.group2.incident.incident.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.group2.incident.audit.service.AuditService;
import com.group2.incident.auth.entity.AppUser;
import com.group2.incident.auth.entity.Role;
import com.group2.incident.auth.repository.AppUserRepository;
import com.group2.incident.catalog.entity.Severity;
import com.group2.incident.catalog.service.OnCallService;
import com.group2.incident.catalog.service.ServiceCatalogService;
import com.group2.incident.incident.dto.TimelineEntryResponse;
import com.group2.incident.incident.entity.Incident;
import com.group2.incident.incident.entity.IncidentStatus;
import com.group2.incident.incident.entity.TimelineEntry;
import com.group2.incident.incident.repository.IncidentRepository;
import com.group2.incident.incident.repository.TimelineEntryRepository;
import com.group2.incident.notification.service.NotificationService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CommentConcurrencyTest {

	private static final Instant NOW = Instant.parse("2026-10-20T08:30:00Z");

	@Mock
	private IncidentRepository incidents;

	@Mock
	private TimelineEntryRepository timelineEntries;

	@Mock
	private ServiceCatalogService serviceCatalogService;

	@Mock
	private OnCallService onCallService;

	@Mock
	private AppUserRepository users;

	@Mock
	private AuditService auditService;

	@Mock
	private EscalationService escalationService;

	@Mock
	private NotificationService notificationService;

	private IncidentService service;
	private Incident incident;
	private AppUser firstAuthor;
	private AppUser secondAuthor;
	private ConcurrentLinkedQueue<TimelineEntry> savedEntries;

	@BeforeEach
	void setUp() {
		service = new IncidentService(
				incidents,
				timelineEntries,
				serviceCatalogService,
				onCallService,
				users,
				auditService,
				escalationService,
				notificationService,
				Clock.fixed(NOW, ZoneOffset.UTC)
		);
		firstAuthor = new AppUser("engineer", "Dev Engineer", "{noop}password", Role.ENGINEER, NOW);
		secondAuthor = new AppUser("admin", "Dev Admin", "{noop}password", Role.ADMIN, NOW);
		incident = new Incident(
				"Concurrent comments",
				"Test incident",
				null,
				Severity.SEV1,
				IncidentStatus.OPEN,
				firstAuthor,
				NOW,
				60,
				480
		);
		when(incidents.findById(7L)).thenReturn(Optional.of(incident));
		when(users.findById(2L)).thenReturn(Optional.of(firstAuthor));
		when(users.findById(3L)).thenReturn(Optional.of(secondAuthor));

		AtomicLong ids = new AtomicLong();
		savedEntries = new ConcurrentLinkedQueue<>();
		when(timelineEntries.save(any(TimelineEntry.class))).thenAnswer(invocation -> {
			TimelineEntry submitted = invocation.getArgument(0);
			savedEntries.add(submitted);
			TimelineEntry persisted = mock(TimelineEntry.class);
			when(persisted.getId()).thenReturn(ids.incrementAndGet());
			when(persisted.getEventType()).thenReturn(submitted.getEventType());
			when(persisted.getActor()).thenReturn(submitted.getActor());
			when(persisted.getDetails()).thenReturn(submitted.getDetails());
			when(persisted.getRecordedAt()).thenReturn(submitted.getRecordedAt());
			return persisted;
		});
	}

	@Test
	void twoConcurrentCommentsCreateSeparateTimelineEntriesWithoutSavingIncident() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);

		try {
			Future<TimelineEntryResponse> first = executor.submit(() -> submitComment(ready, start, 2L, "First"));
			Future<TimelineEntryResponse> second = executor.submit(() -> submitComment(ready, start, 3L, "Second"));

			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			TimelineEntryResponse firstResponse = first.get(5, TimeUnit.SECONDS);
			TimelineEntryResponse secondResponse = second.get(5, TimeUnit.SECONDS);

			assertThat(List.of(firstResponse.id(), secondResponse.id())).containsExactlyInAnyOrder(1L, 2L);
			assertThat(savedEntries)
					.extracting(TimelineEntry::getDetails)
					.containsExactlyInAnyOrder("First", "Second");
			assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
			verify(timelineEntries, times(2)).save(any(TimelineEntry.class));
			verify(incidents, never()).save(any(Incident.class));
		}
		finally {
			executor.shutdownNow();
		}
	}

	private TimelineEntryResponse submitComment(
			CountDownLatch ready, CountDownLatch start, Long authorId, String comment) throws InterruptedException {
		ready.countDown();
		if (!start.await(5, TimeUnit.SECONDS)) {
			throw new IllegalStateException("Timed out waiting to start concurrent comment submissions");
		}
		return service.addComment(7L, comment, authorId);
	}
}
