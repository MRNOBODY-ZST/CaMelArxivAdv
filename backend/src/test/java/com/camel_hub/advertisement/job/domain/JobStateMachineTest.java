package com.camel_hub.advertisement.job.domain;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class JobStateMachineTest {

	private final JobStateMachine stateMachine = new JobStateMachine();

	@Test
	void exposesOnlyLegalUserActionsForEachState() {
		Map<JobStatus, Set<JobAction>> expected = Map.of(
				JobStatus.PENDING, Set.of(JobAction.CANCEL),
				JobStatus.QUEUED, Set.of(JobAction.PAUSE, JobAction.CANCEL),
				JobStatus.RUNNING, Set.of(JobAction.PAUSE, JobAction.CANCEL),
				JobStatus.PAUSED, Set.of(JobAction.RESUME, JobAction.CANCEL),
				JobStatus.SUCCEEDED, Set.of(),
				JobStatus.PARTIALLY_SUCCEEDED, Set.of(JobAction.RETRY),
				JobStatus.FAILED, Set.of(JobAction.RETRY),
				JobStatus.CANCELED, Set.of(JobAction.RETRY));

		expected.forEach((status, actions) -> assertThat(stateMachine.allowedActions(status))
				.as(status.name()).containsExactlyInAnyOrderElementsOf(actions));
	}

	@Test
	void mapsControlActionsToDeterministicStates() {
		assertThat(stateMachine.transition(JobStatus.RUNNING, JobAction.PAUSE))
				.isEqualTo(JobStatus.PAUSED);
		assertThat(stateMachine.transition(JobStatus.PAUSED, JobAction.RESUME))
				.isEqualTo(JobStatus.QUEUED);
		assertThat(stateMachine.transition(JobStatus.QUEUED, JobAction.CANCEL))
				.isEqualTo(JobStatus.CANCELED);
		assertThat(stateMachine.transition(JobStatus.FAILED, JobAction.RETRY))
				.isEqualTo(JobStatus.PENDING);
	}

	@Test
	void rejectsIllegalTransitionsInsteadOfSilentlyMutatingState() {
		assertThatIllegalStateException()
				.isThrownBy(() -> stateMachine.transition(JobStatus.SUCCEEDED, JobAction.RETRY))
				.withMessageContaining("SUCCEEDED")
				.withMessageContaining("RETRY");
		assertThatIllegalStateException()
				.isThrownBy(() -> stateMachine.transition(JobStatus.PENDING, JobAction.PAUSE));
	}

	@Test
	void identifiesEveryTerminalState() {
		assertThat(JobStatus.SUCCEEDED.isTerminal()).isTrue();
		assertThat(JobStatus.PARTIALLY_SUCCEEDED.isTerminal()).isTrue();
		assertThat(JobStatus.FAILED.isTerminal()).isTrue();
		assertThat(JobStatus.CANCELED.isTerminal()).isTrue();
		assertThat(JobStatus.RUNNING.isTerminal()).isFalse();
	}
}
