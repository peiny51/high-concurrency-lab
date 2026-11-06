<script setup>
import { onMounted, ref } from 'vue'

const demoProducts = ref([])
const productIdsText = ref('')
const loading = ref(false)
const error = ref('')
const rows = ref([])
const result = ref('No query yet.')
const protection = ref(null)
const protectionError = ref('')
const batchSummary = ref(null)

/** Loads and validates fixture products, then initializes the editable comma-separated ID list. */
async function loadDemoProducts() {
  loading.value = true
  error.value = ''
  rows.value = []
  batchSummary.value = null
  result.value = 'Loading demo products...'
  try {
    const response = await fetch('/api/cache-avalanche/products')
    const text = await response.text()
    result.value = text
    if (!response.ok) {
      throw new Error(`Could not load demo products (HTTP ${response.status}).`)
    }
    const data = JSON.parse(text)
    if (!Array.isArray(data) || !data.every(product => Number.isSafeInteger(product.productId) && product.productId > 0 && typeof product.name === 'string')) {
      throw new Error('The API returned an unexpected product list.')
    }
    demoProducts.value = data
    productIdsText.value = data.map(product => product.productId).join(', ')
    result.value = JSON.stringify(data, null, 2)
  } catch (exception) {
    demoProducts.value = []
    error.value = exception.message || 'Could not load demo products.'
  } finally {
    loading.value = false
  }
}

/** Refreshes instance-wide metrics with a three-second browser timeout; clears stale metrics on failure. */
async function refreshProtection() {
  protectionError.value = ''
  try {
    const response = await fetch('/api/cache-avalanche/protection', { signal: AbortSignal.timeout(3000) })
    if (!response.ok) {
      throw new Error(`Could not load protection metrics (HTTP ${response.status}).`)
    }
    const data = await response.json()
    const fields = ['maxConcurrentRebuilds', 'maxWaitMillis', 'activeRebuilds', 'peakRebuilds', 'admittedRebuilds', 'rejectedRebuilds', 'databaseLoads']
    if (!fields.every(field => Number.isFinite(data[field]) && data[field] >= 0) || data.maxConcurrentRebuilds < 1) {
      throw new Error('Unexpected protection metrics.')
    }
    protection.value = data
  } catch (exception) {
    protection.value = null
    protectionError.value = exception.message || 'Could not load protection metrics.'
  }
}

/** Checks response order, metadata, and TTL ranges; throws if the batch cannot be safely displayed. */
function validateBatch(data, productIds) {
  if (!Array.isArray(data) || data.length !== productIds.length) {
    throw new Error('The API returned an unexpected batch response.')
  }
  for (const [index, row] of data.entries()) {
    if (row.productId !== productIds[index] || typeof row.found !== 'boolean' || (row.source !== 'mysql' && row.source !== 'redis')) {
      throw new Error('The API returned unexpected product metadata.')
    }
    if (row.found && (typeof row.name !== 'string' || !Number.isFinite(row.remainingTtlMillis) || row.remainingTtlMillis <= 0)) {
      throw new Error('The API returned an invalid cached name or expiry.')
    }
    if (row.found && row.source === 'mysql' && (!Number.isInteger(row.assignedTtlSeconds) || row.assignedTtlSeconds < 60 || row.assignedTtlSeconds > 90)) {
      throw new Error('The API returned an invalid assigned expiry.')
    }
  }
}

/** Sends requestCount concurrent batches and classifies each HTTP or transport outcome independently. */
async function queryProducts(requestCount = 1) {
  error.value = ''
  rows.value = []
  batchSummary.value = null
  const productIds = productIdsText.value.split(',').map(value => Number(value.trim()))
  if (productIds.length > 10 || !productIds.every(id => Number.isSafeInteger(id) && id > 0) || new Set(productIds).size !== productIds.length) {
    error.value = 'Enter 1 to 10 different positive integer product IDs, separated by commas.'
    result.value = 'No request was sent.'
    return
  }
  loading.value = true
  result.value = 'Querying products...'
  try {
    // Requests start together; the browser timeout does not guarantee server-side cancellation.
    const requests = Array.from({ length: requestCount }, async () => {
      const response = await fetch(`/api/cache-avalanche?productIds=${productIds.join(',')}`, {
        signal: AbortSignal.timeout(8000),
      })
      const body = await response.json()
      if (response.ok) {
        validateBatch(body, productIds)
      }
      return { httpStatus: response.status, body }
    })
    // Retain every outcome even when one batch times out or has an invalid response body.
    const settled = await Promise.allSettled(requests)
    const totals = { requested: requestCount, completed: 0, capacityRejected: 0, otherHttpFailures: 0, requestFailures: 0 }
    const outcomes = []
    for (const [index, item] of settled.entries()) {
      if (item.status === 'rejected') {
        totals.requestFailures += 1
        outcomes.push({ batch: index + 1, outcome: 'Network, timeout, or response format failure' })
        continue
      }
      const response = item.value
      let outcome
      if (response.httpStatus === 200) {
        totals.completed += 1
        outcome = 'Completed'
        // The table is a sample of the first successful batch, not an aggregate of all batches.
        if (rows.value.length === 0) {
          rows.value = response.body
        }
      // Count only the explicit admission error code as capacity rejection, not every HTTP 503.
      } else if (response.httpStatus === 503 && response.body.code === 'ORIGIN_CAPACITY_EXCEEDED') {
        totals.capacityRejected += 1
        outcome = 'Rejected: rebuild capacity is busy'
      } else {
        totals.otherHttpFailures += 1
        outcome = 'Other HTTP failure'
      }
      outcomes.push({ batch: index + 1, outcome, ...response })
    }
    batchSummary.value = { ...totals, outcomes }
    result.value = JSON.stringify(outcomes, null, 2)
  } finally {
    loading.value = false
    await refreshProtection()
  }
}

// Initialize fixtures before metrics; this lifecycle callback runs when the page is mounted.
onMounted(async () => {
  await loadDemoProducts()
  await refreshProtection()
})
</script>

<template>
  <main>
    <RouterLink to="/cache">Back to Cache Scenarios</RouterLink>
    <h1>Cache Avalanche Protection</h1>
    <p>Randomized 60 to 90-second expiry spreads cache misses. If many products still need rebuilding, a bounded number of rebuilds may reach MySQL at once; excess work waits briefly or receives HTTP 503.</p>
    <h2>Demo Products</h2>
    <ul><li v-for="product in demoProducts" :key="product.productId">{{ product.productId }} — {{ product.name }}</li></ul>
    <p v-if="!loading && !demoProducts.length">No demo products found. Prepare the demo data, then reload the list.</p>
    <button type="button" :disabled="loading" @click="loadDemoProducts">Reload Demo Products</button>
    <form @submit.prevent="queryProducts(1)">
      <label for="avalanche-product-ids">Product IDs</label>
      <input id="avalanche-product-ids" v-model="productIdsText" type="text" required :disabled="loading" />
      <button type="submit" :disabled="loading">Query Products</button>
      <button type="button" :disabled="loading" @click="queryProducts(5)">Run 5 Concurrent Batches</button>
    </form>
    <p v-if="loading" role="status">Loading...</p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <section aria-label="Origin protection metrics">
      <h2>Origin Protection</h2>
      <button type="button" :disabled="loading" @click="refreshProtection">Refresh Protection Metrics</button>
      <p v-if="protectionError" role="alert" class="error">{{ protectionError }}</p>
      <dl v-if="protection">
        <dt>Concurrent Rebuild Limit</dt><dd>{{ protection.maxConcurrentRebuilds }}</dd>
        <dt>Maximum Admission Wait</dt><dd>{{ protection.maxWaitMillis }} ms</dd>
        <dt>Active Rebuilds</dt><dd>{{ protection.activeRebuilds }}</dd>
        <dt>Peak Active Rebuilds</dt><dd>{{ protection.peakRebuilds }}</dd>
        <dt>Admitted Rebuild Sections</dt><dd>{{ protection.admittedRebuilds }}</dd>
        <dt>Capacity Rejections</dt><dd>{{ protection.rejectedRebuilds }}</dd>
        <dt>Database Load Attempts</dt><dd>{{ protection.databaseLoads }}</dd>
      </dl>
      <p>These are instance-wide snapshots for this case since startup, not per-batch SQL counts. They may include other users. Cache hits bypass admission; the demo product list and other scenarios are outside this limit.</p>
    </section>
    <section v-if="batchSummary" aria-live="polite" aria-label="Batch outcomes">
      <h2>Batch Outcomes</h2>
      <p>{{ batchSummary.requested }} requested; {{ batchSummary.completed }} completed; {{ batchSummary.capacityRejected }} capacity rejected; {{ batchSummary.otherHttpFailures }} other HTTP failures; {{ batchSummary.requestFailures }} network, timeout, or format failures.</p>
      <ul><li v-for="outcome in batchSummary.outcomes" :key="outcome.batch">Batch {{ outcome.batch }}: {{ outcome.httpStatus === undefined ? 'No usable HTTP result' : `HTTP ${outcome.httpStatus}` }} — {{ outcome.outcome }}</li></ul>
    </section>
    <section v-if="rows.length" aria-live="polite" aria-label="Batch query results">
      <h2>Sample Batch Rows</h2>
      <p>Rows from the first successfully returned batch. Inspect all batch responses below.</p>
      <div class="table-scroll">
        <table>
          <thead><tr><th scope="col">Product ID</th><th scope="col">Name / Result</th><th scope="col">Source</th><th scope="col">TTL Assigned This Query</th><th scope="col">Remaining TTL</th></tr></thead>
          <tbody>
            <tr v-for="row in rows" :key="row.productId">
              <td>{{ row.productId }}</td>
              <td>{{ row.found ? row.name : 'Product not found' }}</td>
              <td>{{ row.source }}</td>
              <td>{{ row.assignedTtlSeconds === undefined ? '—' : `${row.assignedTtlSeconds} s` }}</td>
              <td>{{ row.found ? `${(row.remainingTtlMillis / 1000).toFixed(1)} s` : 'No entry' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p>TTL values are snapshots reported by Redis when queried, not a live countdown. A dash means this query did not write a cache entry.</p>
    </section>
    <h2>Try It</h2>
    <ol>
      <li>Query the demo products. Missing entries are loaded from MySQL and cached for 60 to 90 seconds each.</li>
      <li>Query again. Cache hits use Redis, show less time remaining, and do not renew the expiry.</li>
      <li>Wait until some entries expire, then query again. Expired entries use MySQL and receive a new randomized expiry; unexpired entries still use Redis.</li>
      <li>Run concurrent batches while entries are missing. Rebuilds share the instance limit across products. On a fast local database all batches may complete without rejection; the controlled concurrency tests verify saturation behavior.</li>
    </ol>
    <p>Random TTLs can repeat. This reduces synchronized expiry; it does not guarantee evenly spaced expirations or protection against a Redis outage. Five products demonstrate the policy, not large-scale load capacity.</p>
    <p>On a miss, request a rebuild slot and recheck Redis before loading MySQL. Release the slot after completion or failure. If capacity stays busy beyond the configured wait, return HTTP 503 with code ORIGIN_CAPACITY_EXCEEDED and Retry-After: 1. This page never retries automatically.</p>
    <p>The limit applies only to this instance and query path, not the whole database or all application replicas. The wait budget does not cancel a slow database call. The 8-second browser timeout also does not guarantee server-side cancellation. A per-product distributed lock, stale-data fallback, and cluster-wide quotas are not implemented here.</p>
    <p>This case uses separate name caches. Renaming invalidates the corresponding entry, but existing page results need another query to refresh. Missing products are not cached. Concurrent old-value refill is still possible.</p>
    <p>A batch contains at most 10 different IDs and processes them sequentially. Missing products appear as individual not-found rows in an HTTP 200 batch response. A failed or rejected batch can leave earlier entries cached. A completed batch can contain not-found rows.</p>
    <h2>Batch Responses</h2>
    <pre>{{ result }}</pre>
  </main>
</template>

<style scoped>
main { max-width: 920px; margin: 48px auto; padding: 0 20px; }
form { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin-top: 24px; }
input, button { padding: 8px 12px; font: inherit; }
input { width: 240px; max-width: 100%; }
button { cursor: pointer; }
button:disabled { cursor: wait; }
dt { margin-top: 10px; font-weight: bold; }
dd { margin: 4px 0; }
.table-scroll { overflow-x: auto; }
table { border-collapse: collapse; width: 100%; }
th, td { padding: 12px; border: 1px solid #cbd5e1; text-align: left; }
th { background: #f1f5f9; }
.error { color: #b91c1c; }
p, li { line-height: 1.6; }
a { color: #1d4ed8; }
pre { padding: 16px; background: #f1f5f9; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
