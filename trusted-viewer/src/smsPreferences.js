export function normalizeE164(value) {
  const normalized = String(value ?? '').trim().replace(/[\s().-]/g, '')
  if (!/^\+[1-9]\d{7,14}$/.test(normalized)) {
    throw new Error('Phone number must use E.164 format, for example +2348012345678.')
  }
  return normalized
}

export function maskE164(value) {
  const normalized = normalizeE164(value)
  const hiddenLength = Math.max(normalized.length - 8, 3)
  return `${normalized.slice(0, 4)}${'*'.repeat(hiddenLength)}${normalized.slice(-4)}`
}
