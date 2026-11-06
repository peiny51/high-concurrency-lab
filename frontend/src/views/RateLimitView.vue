<script setup>
import { ref } from 'vue'

const productId = ref(1)
const loading = ref(false)
const error = ref('')
const summary = ref(null)
const outcomes = ref([])

/** Queries one product and classifies the actual HTTP response; throws for unusable lookup or quota data. */
async function requestProduct(requestedProductId) {
  const response = await fetch(`/api/rate-limit/product?productId=${requestedProductId}`, {
    signal: AbortSignal.timeout(8000),
  })
  const text = await response.text()
  let body
  try {
    body = JSON.parse(text)
  } catch {
    // Keep non-JSON HTTP errors visible, but never display a malformed success as a valid lookup.
    if (response.ok || response.status === 429) {
      throw new Error('The API returned an unusable response format.')
    }
    body = text
  }
  let outcome = 'Other HTTP failure'
  let retryAfterSeconds = null
  if (response.status === 200 || response.status === 404) {
    const expectedFound = response.status === 200
    if (!body || body.productId !== requestedProductId || body.found !== expectedFound ||
      (body.source !== 'mysql' && body.source !== 'redis') ||
      (expectedFound && typeof body.name !== 'string')) {
      throw new Error('The API returned unexpected product data.')
    }
    outcome = expectedFound ? 'Found' : 'Not found'
  } else if (response.status === 429) {
    const retryHeader = response.headers.get('Retry-After')
    retryAfterSeconds = Number(retryHeader)
    if (!body || body.code !== 'PRODUCT_QUERY_RATE_LIMITED' ||
      !Number.isFinite(body.retryAfterMillis) || body.retryAfterMillis <= 0 ||
      retryHeader === null || !Number.isSafeInteger(retryAfterSeconds) || retryAfterSeconds < 1) {
      throw new Error('The API returned unexpected rate-limit data.')
    }
    outcome = 'Rate limited'
  }
  return { httpStatus: response.status, outcome, retryAfterSeconds, body }
}

/** Starts one or ten read-only queries and reports every outcome without resetting quota or retrying. */
async function runQueries(requestCount) {
  error.value = ''
  summary.value = null
  outcomes.value = []
  // Capture the ID once so every request in this run uses the same input.
  const requestedProductId = productId.value
  if (!Number.isSafeInteger(requestedProductId) || requestedProductId <= 0) {
    error.value = 'Enter a positive safe integer product ID. No request was sent.'
    return
  }
  loading.value = true
  try {
    // Start requests before waiting; one timeout must not hide the other HTTP responses.
    const requests = Array.from({ length: requestCount }, () => requestProduct(requestedProductId))
    const settled = await Promise.allSettled(requests)
    const totals = { requested: requestCount, found: 0, notFound: 0, limited: 0, otherHttpFailures: 0, requestFailures: 0, retryAfterSeconds: 0 }
    const results = []
    for (const [index, item] of settled.entries()) {
      if (item.status === 'rejected') {
        totals.requestFailures += 1
        results.push({ request: index + 1, outcome: 'Network, timeout, or response format failure', message: item.reason.message })
        continue
      }
      const response = item.value
      if (response.outcome === 'Found') {
        totals.found += 1
      } else if (response.outcome === 'Not found') {
        totals.notFound += 1
      } else if (response.outcome === 'Rate limited') {
        totals.limited += 1
        // Show the largest server-provided delay as a snapshot, not a live or guaranteed countdown.
        totals.retryAfterSeconds = Math.max(totals.retryAfterSeconds, response.retryAfterSeconds)
      } else {
        totals.otherHttpFailures += 1
      }
      results.push({ request: index + 1, ...response })
    }
    outcomes.value = results
    summary.value = totals
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main>
    <RouterLink to="/">Back to Home</RouterLink>
    <h1>Product Query Rate Limiting</h1>
    <p>Try a single lookup or send ten concurrent requests. This endpoint checks a shared Redis quota before reading the
      product name.</p>
    <section aria-label="Rate limit policy">
      <h2>How the Window Works</h2>
      <p>The current backend example allows 5 requests per 10-second window, starting with the first admitted request.
        All clients and product IDs share this quota. This page does not fetch policy configuration; backend settings
        are authoritative.</p>
      <p>Completed requests do not return quota. Rejected requests do not extend the window. This rate limit does not
        use the avalanche case's two-slot semaphore.</p>
    </section>
    <form @submit.prevent="runQueries(1)">
      <label for="rate-limit-product-id">Product ID</label>
      <input id="rate-limit-product-id" v-model.number="productId" type="number" min="1" step="1" required
        :disabled="loading" />
      <button type="submit" :disabled="loading">Query Once</button>
      <button type="button" :disabled="loading" @click="runQueries(10)">Send 10 Concurrent Requests</button>
    </form>
    <p v-if="loading" role="status">Waiting for all query results...</p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <section v-if="summary" aria-live="polite" aria-label="Query summary">
      <h2>This Run</h2>
      <dl>
        <dt>Requests</dt>
        <dd>{{ summary.requested }}</dd>
        <dt>Found (HTTP 200)</dt>
        <dd>{{ summary.found }}</dd>
        <dt>Not Found (HTTP 404)</dt>
        <dd>{{ summary.notFound }}</dd>
        <dt>Rate Limited (HTTP 429)</dt>
        <dd>{{ summary.limited }}</dd>
        <dt>Other HTTP Failures</dt>
        <dd>{{ summary.otherHttpFailures }}</dd>
        <dt>Network, Timeout, or Format Failures</dt>
        <dd>{{ summary.requestFailures }}</dd>
      </dl>
      <p v-if="summary.limited">The largest Retry-After received was {{ summary.retryAfterSeconds }} seconds. This is a
        response-time snapshot, not a live countdown or a guarantee of admission. Wait, then query manually; this page
        never retries automatically.</p>
      <div class="table-scroll">
        <table>
          <thead>
            <tr>
              <th scope="col">Request</th>
              <th scope="col">HTTP</th>
              <th scope="col">Outcome</th>
              <th scope="col">Retry-After</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in outcomes" :key="row.request">
              <td>{{ row.request }}</td>
              <td>{{ row.httpStatus === undefined ? 'No usable result' : row.httpStatus }}</td>
              <td>{{ row.outcome }}</td>
              <td>{{ row.retryAfterSeconds ? `${row.retryAfterSeconds} s` : '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
    <h2>Try It</h2>
    <ol>
      <li>Use an existing product ID. Query once to inspect its name and reported data source.</li>
      <li>Wait at least 10 seconds after your last run, then send ten concurrent requests. With an unused window and no
        other traffic, expect five admitted lookups and five HTTP 429 responses.</li>
      <li>Query again before that window ends to observe rejection. After expiry, query again to observe recovery.</li>
    </ol>
    <p>Missing products return HTTP 404 and still consume admission quota. Other users can consume the shared quota, and
      requests crossing a window boundary can produce different totals. These counts describe HTTP outcomes, not SQL
      queries.</p>
    <p>The browser gives each request an 8-second timeout. A failed or cancelled request may already have consumed quota
      on the server. There is no quota reset control. Existing scenario endpoints are outside this rate limit.</p>
    <section v-if="outcomes.length">
      <h2>Responses</h2>
      <pre>{{ JSON.stringify(outcomes, null, 2) }}</pre>
    </section>
  </main>
</template>

<style scoped>
main {
  max-width: 920px;
  margin: 48px auto;
  padding: 0 20px;
}

form {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin: 24px 0;
}

input,
button {
  padding: 8px 12px;
  font: inherit;
}

input {
  width: 120px;
}

button {
  cursor: pointer;
}

button:disabled {
  cursor: wait;
}

dt {
  margin-top: 10px;
  font-weight: bold;
}

dd {
  margin: 4px 0;
}

.table-scroll {
  overflow-x: auto;
}

table {
  width: 100%;
  border-collapse: collapse;
}

th,
td {
  padding: 10px;
  border: 1px solid #cbd5e1;
  text-align: left;
}

th,
pre {
  background: #f1f5f9;
}

pre {
  padding: 16px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

p,
li {
  line-height: 1.6;
}

a {
  color: #1d4ed8;
}

.error {
  color: #b91c1c;
}
</style>
