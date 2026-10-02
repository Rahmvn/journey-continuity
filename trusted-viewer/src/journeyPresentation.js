/** @param {{ journey_status: string, monitoring_phase: string, ended_at?: string | null }} snapshot */
export function journeyPresentation(snapshot) {
  if (snapshot.journey_status === 'CANCELLED') {
    return {
      lifecycle: 'Monitoring stopped',
      stoppedAt: snapshot.ended_at ?? null,
      healthy: false,
      headline: 'The Traveller stopped monitoring.',
      explanation: 'This does not confirm arrival, safety, or current whereabouts.',
    }
  }
  const healthy = snapshot.journey_status === 'ACTIVE' &&
    snapshot.monitoring_phase === 'EVIDENCE_FRESH'
  return {
    lifecycle: snapshot.journey_status,
    stoppedAt: null,
    healthy,
    headline: healthy ? 'Monitoring evidence is fresh.' : 'Current whereabouts are unknown.',
    explanation: healthy
      ? 'Precise location is private and is not returned in this response.'
      : 'Open the verification case for the immutable evidence known when contact was lost.',
  }
}
