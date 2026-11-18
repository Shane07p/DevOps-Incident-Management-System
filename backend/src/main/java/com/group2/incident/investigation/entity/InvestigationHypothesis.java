package com.group2.incident.investigation.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "investigation_hypothesis")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InvestigationHypothesis {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "investigation_id", nullable = false, updatable = false)
	private Investigation investigation;

	@Column(nullable = false)
	private int rank;

	@Column(nullable = false, length = 500)
	private String title;

	@Column(nullable = false)
	private double confidence;

	@Column(columnDefinition = "text")
	private String reasoning;

	@OneToMany(mappedBy = "hypothesis", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
	private List<InvestigationEvidence> evidence = new ArrayList<>();

	public InvestigationHypothesis(Investigation investigation, int rank, String title,
			double confidence, String reasoning) {
		this.investigation = investigation;
		this.rank = rank;
		this.title = title;
		this.confidence = confidence;
		this.reasoning = reasoning;
	}

	public void addEvidence(InvestigationEvidence ev) {
		evidence.add(ev);
	}
}
