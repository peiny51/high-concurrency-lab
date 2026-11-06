<script setup>
import { ref } from 'vue'

const productId = ref(1)
const loading = ref(false)
const error = ref('')
const summary = ref(null)
const responses = ref([])

/** Starts requestCount lookups concurrently and summarizes every result without automatic retries. */
async function runQueries(requestCount) {
  summary.value = null
  responses.value = []
  error.value = ''
  const requestedProductId = productId.value
  if (!Number.isSafeInteger(requestedProductId) || requestedProductId <= 0) {
    error.value = 'Enter a positive integer product ID. No request was sent.'
    return
  }

  loading.value = true
  try {
    // Start all lookups before awaiting completion to exercise simultaneous cache misses.
    const requests = Array.from({ length: requestCount }, async () => {
      const response = await fetch(`/api/cache-breakdown?productId=${requestedProductId}`)
      const body = await response.json()
      const validLookup = (response.status === 200 && body.found === true && typeof body.name === 'string')
        || (response.status === 404 && body.found === false)
      if (validLookup && ((body.source !== 'mysql' && body.source !== 'redis') || typeof body.waited !== 'boolean')) {
        throw new Error('The API returned unexpected lookup metadata.')
      }
      return { httpStatus: response.status, body, validLookup }
    })

    // A failed request must not discard successful or confirmed not-found responses.
    const settled = await Promise.allSettled(requests)
    const totals = { requested: requestCount, found: 0, notFound: 0, failed: 0, mysql: 0, redis: 0, waited: 0 }
    const results = []
    
    for (const item of settled) {
      if (item.status === 'rejected') {
        totals.failed += 1
        results.push({ error: 'No usable response. Check the network, backend, and response format.' })
        continue
      }
      const response = item.value
      results.push({ httpStatus: response.httpStatus, body: response.body })
      if (!response.validLookup) {
        totals.failed += 1
        continue
      }
      if (response.body.found) {
        totals.found += 1
      } else {
        totals.notFound += 1
      }
      totals[response.body.source] += 1
      if (response.body.waited) {
        totals.waited += 1
      }
    }
    summary.value = totals
    responses.value = results
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main>
    <RouterLink to="/cache">Back to Cache Scenarios</RouterLink>
    <h1>Cache Breakdown Protection</h1>
    <p>When a popular product's cache is empty, concurrent requests can all reach MySQL before anyone refills Redis. A
      Redis lock coordinates the refill.</p>
    <form @submit.prevent="runQueries(1)">
      <label for="breakdown-product-id">Product ID</label>
      <input id="breakdown-product-id" v-model.number="productId" type="number" min="1" step="1" required
        :disabled="loading" />
      <button type="submit" :disabled="loading">Query Once</button>
      <button type="button" :disabled="loading" @click="runQueries(10)">Run 10 Concurrent Queries</button>
    </form>
    <p v-if="loading" role="status">Waiting for query results...</p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>

    <section v-if="summary" aria-live="polite" aria-label="Query summary">
      <h2>Query Results</h2>
      <dl>
        <dt>Requests</dt>
        <dd>{{ summary.requested }}</dd>
        <dt>Found (HTTP 200)</dt>
        <dd>{{ summary.found }}</dd>
        <dt>Not Found (HTTP 404)</dt>
        <dd>{{ summary.notFound }}</dd>
        <dt>Failed or Unavailable</dt>
        <dd>{{ summary.failed }}</dd>
        <dt>MySQL Responses</dt>
        <dd>{{ summary.mysql }}</dd>
        <dt>Redis Responses</dt>
        <dd>{{ summary.redis }}</dd>
        <dt>Requests That Waited for the Lock</dt>
        <dd>{{ summary.waited }}</dd>
      </dl>
      <p>Source and wait counts come from the API. A Redis response does not necessarily mean it waited. SQL logs are
        needed to verify the actual number of database queries.</p>
    </section>

    <h2>Try It</h2>
    <ol>
      <li>Use an existing product ID from the Inventory page.</li>
      <li>Run 10 Concurrent Queries with an empty cache. Under normal operation, expect one MySQL response and nine
        Redis responses.</li>
      <li>Repeat within 60 seconds: all responses should come from Redis.</li>
      <li>Wait at least 60 seconds after the MySQL response, then run again to observe a new refill. Cache hits do not
        renew the expiry.</li>
    </ol>
    <p>During the lock experiment, use this page without querying the ordinary cache pages concurrently. Their lookup
      flow does not acquire the rebuild lock.</p>
    <h2>How It Works</h2>
    <p>Check Redis first. On a miss, try to acquire the product's rebuild lock. The holder checks the cache again before
      querying MySQL and caching the result. Other requests wait briefly and retry.</p>
    <p>The lock expires after 10 seconds and is released only if its owner token still matches. Contenders retry about
      every 40 ms for up to about 2 seconds before HTTP 503. This wait budget does not cancel a slow database or Redis
      operation.</p>
    <p>All cache pages share the same name cache and not-found marker. This page uses a separate service with
      lock-protected refill. Names are cached for 60 seconds. Renaming on the Cache Aside page invalidates the shared
      name and not-found marker. A later query reloads the saved name. Existing page results are snapshots; query again
      to refresh them. Stock is never cached here. Missing products use a 10-second not-found marker.</p>
    <p>Renaming does not release another request's rebuild lock. A concurrent query can still repopulate an old name
      after invalidation; this case does not provide strict read/write consistency.</p>
    <p>This reduces duplicate database reads, not a strict exactly-once guarantee. A query that outlives the lock lease
      or Redis failover can allow another rebuild. Waiting also occupies server request threads.</p>

    <section v-if="responses.length">
      <h2>API Responses</h2>
      <ol>
        <li v-for="(response, index) in responses" :key="index">
          <pre>{{ JSON.stringify(response, null, 2) }}</pre>
        </li>
      </ol>
    </section>
  </main>
</template>

<style scoped>
main {
  max-width: 720px;
  margin: 48px auto;
  padding: 0 20px;
}

form {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}

input,
button {
  padding: 8px 12px;
  font: inherit;
}

input {
  width: 100px;
}

button {
  cursor: pointer;
}

button:disabled {
  cursor: wait;
}

section {
  margin: 24px 0;
}

dl {
  padding: 20px;
  background: #f0fdf4;
  border: 1px solid #86efac;
  border-radius: 8px;
}

dt {
  margin-top: 12px;
  font-weight: bold;
}

dt:first-child {
  margin-top: 0;
}

dd {
  margin: 6px 0 0;
}

.error {
  color: #b91c1c;
}

p,
li {
  line-height: 1.6;
}

a {
  color: #1d4ed8;
}

pre {
  padding: 16px;
  background: #f1f5f9;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
