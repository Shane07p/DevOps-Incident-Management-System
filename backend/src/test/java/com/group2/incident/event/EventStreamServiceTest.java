package com.group2.incident.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.group2.incident.event.service.EventStreamService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class EventStreamServiceTest {

	@Test
	void heartbeatSendsPingAndRemovesADeadEmitter() throws Exception {
		var streams = new EventStreamService();
		var live = mock(SseEmitter.class);
		var dead = mock(SseEmitter.class);
		streams.subscribe(1L, 1L, live);
		streams.subscribe(1L, 2L, dead);
		doThrow(new IOException("Disconnected")).when(dead).send(any(SseEmitter.SseEventBuilder.class));
		streams.heartbeat();
		assertThat(streams.getSubscriberCount(1L)).isEqualTo(1);
		verify(dead).complete();
		var sent = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
		verify(live, org.mockito.Mockito.times(2)).send(sent.capture());
		assertThat(sent.getValue().build()).anyMatch(d -> d.getData().toString().contains(": ping"));
	}

	@Test
	void sixthStreamCompletesOldestWithoutEvictingOtherUsersOrOrgs() {
		var streams = new EventStreamService();
		List<SseEmitter> firstUser = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			var emitter = mock(SseEmitter.class);
			firstUser.add(emitter);
			streams.subscribe(1L, 7L, emitter);
		}
		var otherUser = mock(SseEmitter.class);
		var otherOrg = mock(SseEmitter.class);
		streams.subscribe(1L, 8L, otherUser);
		streams.subscribe(2L, 7L, otherOrg);
		streams.subscribe(1L, 7L, mock(SseEmitter.class));
		verify(firstUser.getFirst()).complete();
		for (var emitter : firstUser.subList(1, 5)) verify(emitter, never()).complete();
		verify(otherUser, never()).complete();
		verify(otherOrg, never()).complete();
		assertThat(streams.getSubscriberCount(1L)).isEqualTo(6);
		assertThat(streams.getSubscriberCount(2L)).isEqualTo(1);
	}

	@Test
	void streamTimeoutIsFiniteAndCompletionRemovesRegistration() {
		var streams = new EventStreamService();
		SseEmitter finite = streams.subscribe(1L, 7L);
		assertThat(finite.getTimeout()).isEqualTo(30 * 60 * 1000L);
		var emitter = mock(SseEmitter.class);
		streams.subscribe(2L, 7L, emitter);
		var callback = ArgumentCaptor.forClass(Runnable.class);
		verify(emitter).onCompletion(callback.capture());
		callback.getValue().run();
		assertThat(streams.getSubscriberCount(2L)).isZero();
	}
}
