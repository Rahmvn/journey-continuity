import { createClient } from '@supabase/supabase-js'
import { normalizeE164 } from './smsPreferences.js'

const supabaseUrl = import.meta.env.NEXT_PUBLIC_SUPABASE_URL || import.meta.env.VITE_SUPABASE_URL
const publishableKey = import.meta.env.NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY ||
  import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY
const token = new URLSearchParams(window.location.search).get('token')
let acceptingInvitation = false
let invitationAcceptedThisPage = false
const elements = {
  configuration: document.querySelector('#configuration'),
  auth: document.querySelector('#auth'),
  authForm: /** @type {HTMLFormElement} */ (document.querySelector('#auth-form')),
  message: document.querySelector('#message'),
  app: document.querySelector('#app'),
  smsPreferences: document.querySelector('#sms-preferences'),
  journeys: document.querySelector('#journeys'),
  case: document.querySelector('#case'),
  refresh: document.querySelector('#refresh'),
  signOut: document.querySelector('#sign-out'),
}

if (!supabaseUrl || !publishableKey) {
  show(elements.configuration)
} else {
  const supabase = createClient(supabaseUrl, publishableKey)
  wireViewer(supabase)
}

function wireViewer(supabase) {
  elements.authForm.addEventListener('submit', async (event) => {
    event.preventDefault()
    const email = String(new FormData(elements.authForm).get('email') ?? '').trim().toLowerCase()
    setBusy(elements.authForm, true)
    const redirectUrl = new URL(window.location.href)
    redirectUrl.hash = ''
    const { error } = await supabase.auth.signInWithOtp({
      email,
      options: { emailRedirectTo: redirectUrl.toString(), shouldCreateUser: true },
    })
    setBusy(elements.authForm, false)
    if (error) return setMessage(error.message, true)
    setMessage('Check your email and open the secure sign-in link in this browser.')
  })

  elements.refresh.addEventListener('click', () => loadViewer(supabase))
  elements.signOut.addEventListener('click', async () => {
    await supabase.auth.signOut()
    window.location.reload()
  })

  supabase.auth.onAuthStateChange((_event, session) => {
    window.setTimeout(() => renderSession(supabase, session), 0)
  })
  supabase.auth.getSession().then(({ data }) => renderSession(supabase, data.session))
}

async function renderSession(supabase, session) {
  if (!session) {
    show(elements.auth)
    hide(elements.app)
    return
  }
  hide(elements.auth)
  show(elements.app)
  if (token && !invitationAcceptedThisPage) {
    if (acceptingInvitation) return
    acceptingInvitation = true
    const { error } = await supabase.rpc('accept_trusted_contact_invitation', { p_token: token })
    acceptingInvitation = false
    if (error) {
      setMessage(`Invitation could not be accepted: ${error.message}`, true)
      return
    }
    invitationAcceptedThisPage = true
    history.replaceState({}, '', window.location.pathname)
    setMessage('Invitation accepted. This identity is now an authorized trusted contact.')
  }
  await loadViewer(supabase)
}

async function loadViewer(supabase) {
  await Promise.all([loadSmsPreferences(supabase), loadJourneys(supabase)])
}

async function loadSmsPreferences(supabase) {
  elements.smsPreferences.innerHTML = '<article class="card"><p>Loading SMS preferencesâ€¦</p></article>'
  const { data, error } = await supabase.rpc('list_trusted_contact_sms_preferences')
  if (error) {
    elements.smsPreferences.replaceChildren()
    return setMessage(`SMS preferences unavailable: ${error.message}`, true)
  }
  elements.smsPreferences.replaceChildren()
  const heading = document.createElement('article')
  heading.className = 'card compact-card'
  heading.innerHTML = `
    <p class="eyebrow">Supplementary alerts</p>
    <h2>SMS preferences</h2>
    <p>SMS may be delayed by carrier or network conditions. A message is an awareness alert, not proof of delivery or safety.</p>`
  elements.smsPreferences.append(heading)
  if (!data.length) {
    heading.insertAdjacentHTML('beforeend', '<p>No accepted trusted relationships are available for SMS configuration.</p>')
    return
  }
  for (const preference of data) {
    elements.smsPreferences.append(smsPreferenceCard(preference, supabase))
  }
}

function smsPreferenceCard(preference, supabase) {
  const article = document.createElement('article')
  article.className = 'card compact-card'
  article.innerHTML = `
    <h3>${escapeText(preference.relationship_display_name)}</h3>
    <p>Configured number: <strong>${escapeText(preference.masked_phone || 'None')}</strong></p>`
  const form = document.createElement('form')
  form.innerHTML = `
    <label>Phone number in E.164 format
      <input name="phone" type="tel" inputmode="tel" autocomplete="tel" placeholder="+2348012345678" />
    </label>
    <label class="checkbox-label">
      <input name="enabled" type="checkbox" ${preference.sms_enabled ? 'checked' : ''} />
      Enable SMS verification alerts
    </label>
    <p class="field-help">Leave the number blank to keep the configured number. Only you can provide this consent.</p>
    <button type="submit">Save SMS preference</button>`
  form.addEventListener('submit', async (event) => {
    event.preventDefault()
    const values = new FormData(form)
    const enabled = values.get('enabled') === 'on'
    let phone = null
    try {
      const phoneValue = String(values.get('phone') ?? '')
      phone = phoneValue.trim() ? normalizeE164(phoneValue) : null
    } catch (error) {
      return setMessage(error.message, true)
    }
    setBusy(form, true)
    const { error } = await supabase.rpc('set_trusted_contact_sms_preference', {
      p_relationship_id: preference.relationship_id,
      p_phone_e164: phone,
      p_sms_enabled: enabled,
    })
    setBusy(form, false)
    if (error) return setMessage(`SMS preference was not saved: ${error.message}`, true)
    setMessage(enabled
      ? 'SMS verification alerts enabled. Provider acceptance will not prove handset delivery or reading.'
      : 'SMS alerts disabled for future notifications.')
    await loadSmsPreferences(supabase)
  })
  article.append(form)
  return article
}

async function loadJourneys(supabase) {
  elements.case.replaceChildren()
  elements.journeys.innerHTML = '<article class="card"><p>Loading authorized Journeys…</p></article>'
  const { data, error } = await supabase.rpc('list_trusted_journeys')
  if (error) {
    elements.journeys.replaceChildren()
    return setMessage(error.message, true)
  }
  elements.journeys.replaceChildren()
  if (!data.length) {
    elements.journeys.innerHTML = '<article class="card"><h2>No authorized Journeys</h2><p>An accepted relationship does not imply access to an old Journey. Access is provisioned explicitly per Journey.</p></article>'
    return
  }
  for (const journey of data) {
    const { data: snapshot, error: snapshotError } = await supabase.rpc('get_trusted_journey_snapshot', {
      p_journey_id: journey.journey_id,
    })
    if (snapshotError) continue
    elements.journeys.append(journeyCard(snapshot, () => loadCase(supabase, snapshot.available_case_id)))
  }
}

function journeyCard(snapshot, openCase) {
  const article = document.createElement('article')
  article.className = 'card'
  const healthy = snapshot.monitoring_phase === 'EVIDENCE_FRESH'
  article.innerHTML = `
    <p class="eyebrow">${escapeText(snapshot.monitoring_phase)}</p>
    <h2>${escapeText(snapshot.destination)}</h2>
    <dl>
      ${detail('Journey lifecycle', snapshot.journey_status)}
      ${detail('Started', formatTime(snapshot.started_at))}
      ${detail('Expected arrival', formatTime(snapshot.expected_arrival_at))}
      ${detail('Last cloud contact', formatTime(snapshot.last_cloud_contact_at))}
    </dl>
    <div class="${healthy ? 'evidence healthy' : 'evidence uncertain'}">
      <strong>${healthy ? 'Monitoring evidence is fresh.' : 'Current whereabouts are unknown.'}</strong>
      <p>${healthy ? 'Precise location is private and is not returned in this response.' : 'Open the verification case for the immutable evidence known when contact was lost.'}</p>
    </div>`
  if (snapshot.available_case_id) {
    const button = document.createElement('button')
    button.textContent = snapshot.open_case_id ? 'Open verification packet' : 'View recently resolved case'
    button.addEventListener('click', openCase)
    article.append(button)
  }
  return article
}

async function loadCase(supabase, caseId) {
  const { data: verificationCase, error } = await supabase.rpc('get_verification_case', { p_case_id: caseId })
  if (error) return setMessage(error.message, true)
  elements.case.replaceChildren(caseCard(verificationCase, supabase))
  elements.case.scrollIntoView({ behavior: 'smooth' })
}

function caseCard(verificationCase, supabase) {
  const article = document.createElement('article')
  article.className = 'card case-card'
  const location = verificationCase.last_verified_device_location
  article.innerHTML = `
    <p class="eyebrow">Verification case • ${escapeText(verificationCase.status)}</p>
    <h2>Current whereabouts are unknown</h2>
    <p>This packet is an immutable snapshot of what the cloud knew when verification began. Delayed evidence is not appended here.</p>
    <dl>
      ${detail('Case opened', formatTime(verificationCase.opened_at))}
      ${detail('Last cloud contact', formatTime(verificationCase.last_cloud_contact_at))}
      ${detail('Resolution', verificationCase.resolution_reason || 'Not resolved')}
    </dl>
    <section class="evidence device">
      <p class="provenance">DEVICE VERIFIED</p>
      <h3>Last verified device location</h3>
      ${location ? `<dl>
        ${detail('Observation time', formatTime(location.event_time))}
        ${detail('Coordinates', `${location.latitude}, ${location.longitude}`)}
        ${detail('Reported accuracy', `${location.accuracy_meters} m`)}
        ${detail('Battery', location.battery_percent == null ? 'Unknown' : `${location.battery_percent}%`)}
        ${detail('Charging', location.charging == null ? 'Unknown' : location.charging ? 'Yes' : 'No')}
        ${detail('Connectivity', location.connectivity_state || 'Unknown')}
      </dl>` : '<p>No verified location had reached the cloud when this case opened.</p>'}
    </section>
    <section>
      <h3>Recent verified movement at case opening</h3>
      <ol>${verificationCase.recent_location_snapshot.map((point) => `<li>${formatTime(point.event_time)} — ${point.latitude}, ${point.longitude} (±${point.accuracy_meters} m)</li>`).join('') || '<li>No cloud-known points.</li>'}</ol>
    </section>
    <section class="evidence system">
      <p class="provenance">SYSTEM DERIVED</p>
      <h3>Verification history</h3>
      <ul>${verificationCase.verification_history.map((event) => `<li>${formatTime(event.occurred_at)} — ${escapeText(event.event_type)}</li>`).join('') || '<li>No transition history.</li>'}</ul>
    </section>
    <section class="evidence human">
      <p class="provenance">TRUSTED CONTACT REPORTED</p>
      <h3>Contact reports</h3>
      <ul>${verificationCase.trusted_contact_reports.map((report) => `<li><strong>${escapeText(report.report_type)}</strong> — ${escapeText(report.note)}<br><small>Reported contact time: ${formatTime(report.contact_time)}; stored: ${formatTime(report.created_at)}</small></li>`).join('') || '<li>No trusted-contact reports.</li>'}</ul>
    </section>`

  const form = document.createElement('form')
  form.className = 'report-form'
  form.innerHTML = `
    <h3>Add a trusted-contact report</h3>
    <p>This does not alter device evidence or resolve the verification case.</p>
    <label>Report type<select name="reportType" required>
      <option value="SPOKE_WITH_TRAVELLER">Spoke with traveller</option>
      <option value="RECEIVED_MESSAGE_FROM_TRAVELLER">Received message from traveller</option>
      <option value="UPDATE_FROM_OTHER_PERSON">Update from another person</option>
      <option value="OTHER">Other</option>
    </select></label>
    <label>Contact time (optional)<input name="contactTime" type="datetime-local" /></label>
    <label>Note<textarea name="note" required minlength="1" maxlength="1000"></textarea></label>
    <button type="submit">Submit report</button>`
  form.addEventListener('submit', async (event) => {
    event.preventDefault()
    const values = new FormData(form)
    setBusy(form, true)
    const contactTime = String(values.get('contactTime') ?? '')
    const { error } = await supabase.rpc('submit_verification_case_report', {
      p_case_id: verificationCase.id,
      p_report_type: String(values.get('reportType') ?? ''),
      p_contact_time: contactTime ? new Date(contactTime).toISOString() : null,
      p_note: String(values.get('note') ?? ''),
    })
    setBusy(form, false)
    if (error) return setMessage(error.message, true)
    setMessage('Report stored as trusted_contact_reported. Device evidence was not changed.')
    await loadCase(supabase, verificationCase.id)
  })
  article.append(form)
  return article
}

function detail(label, value) {
  return `<div><dt>${escapeText(label)}</dt><dd>${escapeText(value ?? 'Unknown')}</dd></div>`
}

function formatTime(value) {
  return value ? new Date(value).toLocaleString() : 'Unknown'
}

function escapeText(value) {
  const node = document.createElement('span')
  node.textContent = String(value)
  return node.innerHTML
}

function setMessage(message, error = false) {
  elements.message.textContent = message
  elements.message.classList.toggle('error', error)
  show(elements.message)
}

function setBusy(form, busy) {
  for (const element of form.elements) element.disabled = busy
}

function show(element) { element.classList.remove('hidden') }
function hide(element) { element.classList.add('hidden') }
