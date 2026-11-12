<script setup>
import { computed, ref } from 'vue'

const storageKey = 'high-concurrency-lab:last-kafka-purchase'
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const productId = ref(1)
const quantity = ref(1)
const requestId = ref(crypto.randomUUID())
const lookupId = ref('')
const pendingRequest = ref(null)
const receipt = ref(null)
const purchaseResult = ref(null)
const submitting = ref(false)
const querying = ref(false)
const submissionRejected = ref(false)
const submissionMessage = ref('No request submitted.')
const queryMessage = ref('')

// A new intent is allowed only before submission, after input rejection, or after its own completed result.
const canPrepareNew = computed(() => !pendingRequest.value || submissionRejected.value ||
  (purchaseResult.value !== null && purchaseResult.value.requestId === pendingRequest.value.requestId))

/** Restores the last attempted intent for read-only follow-up; never republishes it on refresh or navigation. */
function restoreRequest() {
  try {
    const stored = localStorage.getItem(storageKey)
    if (!stored) return
    const saved = JSON.parse(stored)
    if (!saved || typeof saved.requestId !== 'string' || !uuidPattern.test(saved.requestId) ||
      !Number.isSafeInteger(saved.productId) || saved.productId <= 0 ||
      !Number.isSafeInteger(saved.quantity) || saved.quantity <= 0 || saved.quantity > 2147483647) {
      throw new Error('Invalid saved request')
    }
    pendingRequest.value = saved
    requestId.value = saved.requestId
    productId.value = saved.productId
    quantity.value = saved.quantity
    lookupId.value = saved.requestId
    submissionMessage.value = 'Previous request restored. Submission and stock deduction are not confirmed here. Check its result before requesting another deduction.'
  } catch {
    submissionMessage.value = 'Saved request could not be restored. No stock deduction request was sent by loading this page.'
  }
}

/** Saves the intent before one POST; an uncertain response keeps the same ID locked for result lookup. */
async function submitPurchase() {
  if (submitting.value || pendingRequest.value) return
  if (!Number.isSafeInteger(productId.value) || productId.value <= 0 ||
    !Number.isSafeInteger(quantity.value) || quantity.value <= 0 || quantity.value > 2147483647) {
    submissionMessage.value = 'Enter a positive safe integer product ID and an integer quantity between 1 and 2147483647. No request was sent.'
    return
  }
  const intent = { requestId: requestId.value, productId: productId.value, quantity: quantity.value }
  try {
    localStorage.setItem(storageKey, JSON.stringify(intent))
  } catch {
    submissionMessage.value = 'No request was sent: the browser could not save the request ID for follow-up.'
    return
  }
  pendingRequest.value = intent
  lookupId.value = intent.requestId
  submissionRejected.value = false
  receipt.value = null
  purchaseResult.value = null
  queryMessage.value = ''
  submitting.value = true
  submissionMessage.value = 'Submitting one stock deduction request...'
  try {
    const response = await fetch('/api/purchase-requests', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(intent),
      signal: AbortSignal.timeout(8000),
    })
    if (response.status === 400) {
      submissionRejected.value = true
      submissionMessage.value = 'Input rejected (HTTP 400). Prepare a new request to correct the stock deduction details.'
      return
    }
    if (response.status !== 202) throw new Error('Submission not confirmed')
    const body = await response.json()
    if (!body || body.requestId !== intent.requestId || body.state !== 'SUBMITTED') {
      throw new Error('Unexpected submission receipt')
    }
    receipt.value = body
    submissionMessage.value = 'Accepted by Kafka (HTTP 202). This does not confirm that stock was deducted. Check the result below.'
  } catch {
    submissionMessage.value = 'Submission could not be confirmed. It may have been received. Keep this request ID and check its result before requesting another deduction.'
  } finally {
    submitting.value = false
  }
}

/** Performs one uncached result GET; separates completed business rejection, absence, and lookup failure. */
async function checkResult() {
  if (querying.value || submitting.value) return
  const requestedId = lookupId.value.trim().toLowerCase()
  if (!uuidPattern.test(requestedId)) {
    purchaseResult.value = null
    queryMessage.value = 'Enter a complete UUID request ID. No lookup was sent.'
    return
  }
  querying.value = true
  purchaseResult.value = null
  queryMessage.value = 'Checking the completed result...'
  try {
    const response = await fetch(`/api/purchase-results/${encodeURIComponent(requestedId)}`, {
      cache: 'no-store',
      signal: AbortSignal.timeout(8000),
    })
    const body = await response.json()
    if (response.status === 404 && body && body.code === 'PURCHASE_RESULT_NOT_AVAILABLE') {
      queryMessage.value = 'No completed result is available (HTTP 404). The request may be unprocessed or unknown. This does not mean the stock deduction was rejected. Check again later using the same ID.'
      return
    }
    if (response.status !== 200 || !body || body.requestId !== requestedId ||
      !Number.isSafeInteger(body.productId) || body.productId <= 0 ||
      !Number.isSafeInteger(body.quantity) || body.quantity <= 0 ||
      typeof body.success !== 'boolean' || typeof body.processedAt !== 'string' ||
      Number.isNaN(Date.parse(body.processedAt))) {
      throw new Error('No usable completed result')
    }
    purchaseResult.value = body
    queryMessage.value = ''
  } catch {
    queryMessage.value = 'Result lookup failed or returned unusable data. This does not confirm whether stock was deducted. Keep the request ID and check again.'
  } finally {
    querying.value = false
  }
}

/** Clears only the browser's last intent and generates an ID for an explicitly requested new stock deduction. */
function prepareNewRequest() {
  if (submitting.value || querying.value || !canPrepareNew.value) return
  try {
    localStorage.removeItem(storageKey)
  } catch {
    submissionMessage.value = 'Could not clear the saved request. The current request is preserved.'
    return
  }
  requestId.value = crypto.randomUUID()
  pendingRequest.value = null
  receipt.value = null
  purchaseResult.value = null
  lookupId.value = ''
  submissionRejected.value = false
  submissionMessage.value = 'New stock deduction request prepared. Review the product and quantity before submitting.'
  queryMessage.value = ''
}

restoreRequest()
</script>

<template>
  <main>
    <RouterLink to="/">Back to Home</RouterLink>
    <h1>Kafka Stock Deduction</h1>
    <p>Submit a stock deduction request, then check its processing result. Kafka acceptance does not confirm that stock was deducted.</p>
    <p>This scenario processes inventory deductions only. It does not create orders or process payments.</p>
    <p>Temporary processing errors are retried. Unresolved or invalid requests are copied to a failure topic for review;
      they are not recorded as completed stock deductions. A missing result does not identify the cause.</p>
    <ol class="flow" aria-label="Stock deduction processing flow">
      <li>Submit request</li>
      <li>Kafka delivers it</li>
      <li>Consumer deducts stock</li>
      <li>Read the MySQL result</li>
    </ol>

    <section aria-labelledby="purchase-heading">
      <h2 id="purchase-heading">1. Request a Stock Deduction</h2>
      <p>Use an existing product ID. <RouterLink to="/inventory">Open Inventory</RouterLink> to prepare or inspect stock.</p>
      <form @submit.prevent="submitPurchase">
        <div class="fields">
          <label for="kafka-product-id">Product ID
            <input id="kafka-product-id" v-model.number="productId" type="number" min="1" step="1" required
              :disabled="submitting || pendingRequest !== null" />
          </label>
          <label for="kafka-quantity">Quantity
            <input id="kafka-quantity" v-model.number="quantity" type="number" min="1" max="2147483647" step="1" required
              :disabled="submitting || pendingRequest !== null" />
          </label>
        </div>
        <label for="kafka-request-id">This Request ID</label>
        <input id="kafka-request-id" class="request-id" :value="requestId" type="text" readonly />
        <p class="hint">Saved in this browser before submission. Refreshing never sends another deduction request.</p>
        <div class="actions">
          <button type="submit" :disabled="submitting || querying || pendingRequest !== null">
            {{ submitting ? 'Submitting...' : 'Submit Deduction Request' }}
          </button>
          <button type="button" :disabled="submitting || querying || !canPrepareNew" @click="prepareNewRequest">
            Prepare New Request
          </button>
        </div>
      </form>
      <p aria-live="polite" role="status">{{ submissionMessage }}</p>
      <p v-if="pendingRequest && !canPrepareNew" class="hint">Check this request's completed result before preparing another deduction.</p>
      <details v-if="receipt">
        <summary>Submission receipt</summary>
        <pre>{{ JSON.stringify(receipt, null, 2) }}</pre>
      </details>
    </section>

    <section aria-labelledby="result-heading">
      <h2 id="result-heading">2. Check the Processing Result</h2>
      <p>Use this request's ID or paste an earlier request ID. Checking a result never submits a deduction request or changes stock.</p>
      <form @submit.prevent="checkResult">
        <label for="kafka-lookup-id">Request ID to Look Up</label>
        <input id="kafka-lookup-id" v-model="lookupId" class="request-id" type="text" required
          :disabled="querying || submitting" autocomplete="off" />
        <button type="submit" :disabled="querying || submitting">{{ querying ? 'Checking...' : 'Check Result' }}</button>
      </form>
      <div aria-live="polite">
        <p v-if="queryMessage" role="status">{{ queryMessage }}</p>
        <div v-if="purchaseResult" class="outcome" :class="{ rejected: !purchaseResult.success }">
          <h3>{{ purchaseResult.success ? 'Stock deduction completed' : 'Stock deduction rejected' }}</h3>
          <p>{{ purchaseResult.success ? 'The full quantity was deducted and the result was saved.' : 'The product was missing or stock was insufficient. No units were deducted.' }}</p>
          <dl>
            <dt>Request ID</dt><dd>{{ purchaseResult.requestId }}</dd>
            <dt>Product ID</dt><dd>{{ purchaseResult.productId }}</dd>
            <dt>Quantity</dt><dd>{{ purchaseResult.quantity }}</dd>
            <dt>Processed At (UTC)</dt><dd>{{ purchaseResult.processedAt }}</dd>
          </dl>
          <details>
            <summary>Completed result response</summary>
            <pre>{{ JSON.stringify(purchaseResult, null, 2) }}</pre>
          </details>
        </div>
      </div>
    </section>
  </main>
</template>

<style scoped>
main { max-width: 920px; margin: 48px auto; padding: 0 20px; }
p, li { line-height: 1.6; }
a { color: #1d4ed8; }
.flow { display: flex; flex-wrap: wrap; gap: 12px 40px; padding: 16px 16px 16px 36px; background: #f1f5f9; border-radius: 8px; }
section { margin: 24px 0; padding: 24px; border: 1px solid #cbd5e1; border-radius: 8px; }
h2 { margin-top: 0; }
label { display: block; font-weight: bold; margin: 12px 0 8px; }
input, button { box-sizing: border-box; padding: 10px 12px; font: inherit; }
input { display: block; width: 160px; margin-top: 8px; border: 1px solid #94a3b8; border-radius: 4px; }
.request-id { width: 100%; max-width: 540px; font-family: monospace; }
.fields, .actions { display: flex; flex-wrap: wrap; gap: 12px; }
.actions { margin: 16px 0; }
button { margin-top: 12px; cursor: pointer; }
button:disabled { cursor: default; }
.hint { color: #475569; font-size: 14px; }
.outcome { margin-top: 20px; padding: 20px; background: #f0fdf4; border-left: 4px solid #15803d; }
.outcome.rejected { background: #fff7ed; border-color: #c2410c; }
.outcome h3 { margin-top: 0; }
dt { margin-top: 12px; font-weight: bold; }
dd { margin: 4px 0; overflow-wrap: anywhere; }
details { margin-top: 16px; }
summary { cursor: pointer; }
pre { padding: 16px; background: #f1f5f9; overflow: auto; }
@media (max-width: 600px) { section { padding: 16px; } .actions button { width: 100%; } }
</style>
