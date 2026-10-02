import { describe, expect, it } from 'vitest'
import { journeyPresentation } from './journeyPresentation.js'

describe('trusted Journey terminal presentation', () => {
  it('distinguishes intentional monitoring stop from completion or safety', () => {
    const result = journeyPresentation({
      journey_status: 'CANCELLED',
      monitoring_phase: 'CLOSED',
      ended_at: '2026-10-02T12:00:00Z',
    })
    expect(result.lifecycle).toBe('Monitoring stopped')
    expect(result.stoppedAt).toBe('2026-10-02T12:00:00Z')
    expect(result.healthy).toBe(false)
    expect(result.explanation).toContain('does not confirm arrival, safety')
  })

  it('does not present a completed Journey as active evidence', () => {
    const result = journeyPresentation({
      journey_status: 'COMPLETED',
      monitoring_phase: 'EVIDENCE_FRESH',
    })
    expect(result.healthy).toBe(false)
    expect(result.lifecycle).toBe('COMPLETED')
    expect(result.stoppedAt).toBeNull()
  })
})
