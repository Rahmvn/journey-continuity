import { describe, expect, it } from 'vitest'
import { maskE164, normalizeE164 } from './smsPreferences.js'

describe('SMS phone helpers', () => {
  it('normalizes an explicitly international Nigerian number', () => {
    expect(normalizeE164('+234 (801) 234-5678')).toBe('+2348012345678')
  })

  it.each(['08012345678', '+02348012345678', '+234-ABC-5678', ''])(
    'rejects invalid E.164 input %s',
    (value) => expect(() => normalizeE164(value)).toThrow(/E\.164/),
  )

  it('shows only a masked configured number', () => {
    expect(maskE164('+2348012345678')).toBe('+234******5678')
  })
})
