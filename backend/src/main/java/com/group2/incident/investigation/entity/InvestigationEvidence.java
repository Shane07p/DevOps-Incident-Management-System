package com.group2.incident.investigation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "investigation_evidence")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InvestigationEvidence {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "hypothesis_id", nullable = false, updatable = false)
	private InvestigationHypothesis hypothesis;

	@Column(nullable = false, length = 100)
	private String kind;

	@Column(nullable = false, columnDefinition = "text")
	private String text;

	public InvestigationEvidence(InvestigationHypothesis hypothesis, String kind, String text) {
		this.hypothesis = hypothesis;
		this.kind = kind;
		this.text = text;
	}
}
