<script setup>
import { ref } from 'vue'

const productId = ref(9999)
const loading = ref(false)
const product = ref(null)
const missingProduct = ref(null)
const httpStatus = ref(null)
const error = ref('')
const result = ref('No query yet.')

/** Displays existing products or confirmed 404 results, including the backend-reported cache source. */
async function queryProduct() {
  product.value = null
  missingProduct.value = null
  httpStatus.value = null
  error.value = ''
  if (!Number.isSafeInteger(productId.value) || productId.value <= 0) {
    error.value = 'Enter a positive integer product ID.'
    result.value = 'No request was sent.'
    return
  }

  loading.value = true
  result.value = 'Loading...'
  try {
    const response = await fetch(`/api/product-cache?productId=${productId.value}`)
    httpStatus.value = response.status
    const text = await response.text()
    result.value = text
    if (response.status === 404) {
      const data = JSON.parse(text)
      if (data.found === false && (data.source === 'mysql' || data.source === 'redis')) {
        missingProduct.value = data
        error.value = 'Product not found.'
        result.value = JSON.stringify(data, null, 2)
      } else {
        error.value = 'The API returned an unexpected not-found response.'
      }
      return
    }
    if (!response.ok) {
      error.value = `Request failed (HTTP ${response.status}). Check the backend and Redis.`
      return
    }

    const data = JSON.parse(text)
    if (typeof data.name !== 'string' || (data.source !== 'mysql' && data.source !== 'redis')) {
      error.value = 'The API returned an unexpected product response.'
      return
    }
    product.value = data
    result.value = JSON.stringify(data, null, 2)
  } catch {
    error.value = 'Query could not be completed. Check the frontend, backend, and response format.'
    if (httpStatus.value === null) {
      result.value = 'No HTTP response was received.'
    }
  } finally {
    loading.value = false
  }
}

</script>

<template>
  <main>
    <RouterLink to="/cache">Back to Cache Scenarios</RouterLink>
    <h1>Cache Penetration Protection</h1>
    <p>Old links or repeated invalid IDs can cause unnecessary database lookups. Briefly cache a confirmed not-found result to protect MySQL.</p>
    <form @submit.prevent="queryProduct">
      <label for="missing-product-id">Product ID</label>
      <input id="missing-product-id" v-model.number="productId" type="number" min="1" step="1" required :disabled="loading" />
      <button type="submit" :disabled="loading">{{ loading ? 'Loading...' : 'Query Product' }}</button>
    </form>

    <section aria-live="polite" aria-label="Query result">
      <p v-if="httpStatus !== null">HTTP status: {{ httpStatus }}</p>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
      <dl v-if="missingProduct" class="missing-result">
        <dt>Product ID</dt><dd>{{ missingProduct.productId }}</dd>
        <dt>Result</dt><dd>Product not found</dd>
        <dt>Data Source</dt><dd><strong>{{ missingProduct.source === 'redis' ? 'Redis (cached not found)' : 'MySQL (not found)' }}</strong></dd>
        <dt>What Happened</dt>
        <dd v-if="missingProduct.source === 'mysql'">MySQL confirmed this product does not exist. The backend cached this result for 10 seconds.</dd>
        <dd v-else>Redis remembered that this product does not exist. This request skipped MySQL. The original 10-second expiry is not extended.</dd>
      </dl>
      <dl v-if="product">
        <dt>Product ID</dt><dd>{{ product.productId }}</dd>
        <dt>Product Name</dt><dd>{{ product.name }}</dd>
        <dt>Data Source</dt><dd>{{ product.source === 'redis' ? 'Redis (cache hit)' : 'MySQL (cache miss)' }}</dd>
        <dt>Result</dt><dd>This product exists. Choose a different ID to test not-found caching.</dd>
      </dl>
    </section>

    <p>All cache pages share the same name cache and not-found marker. This page uses the ordinary cache lookup flow. A query on another page may already have populated this cache.</p>

    <h2>Try It</h2>
    <ol>
      <li>Enter a positive product ID that does not exist. The default 9999 is only a suggestion.</li>
      <li>Query once: MySQL confirms the missing product and the API returns HTTP 404.</li>
      <li>Repeat within 10 seconds: Redis returns the cached not-found result, still with HTTP 404.</li>
      <li>Wait at least 10 seconds after the first not-found query, then query again. MySQL checks again.</li>
    </ol>
    <p>The source comes from the API. If a marker already exists, the first click can be a Redis hit.</p>
    <p>The 10-second expiry balances fewer database lookups with discovering newly created products. Repeated hits do not extend it. A creation flow should also clear its product's missing marker.</p>
    <p>This protects repeated lookups of the same missing ID. It does not stop requests for many different IDs or merge the first simultaneous cache misses.</p>

    <h2>API Response</h2>
    <pre aria-live="polite">{{ result }}</pre>
  </main>
</template>

<style scoped>
main { max-width: 720px; margin: 48px auto; padding: 0 20px; }
form { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; }
input, button { padding: 8px 12px; font: inherit; }
input { width: 100px; }
input[type="text"] { width: 260px; max-width: 100%; }
button { cursor: pointer; }
button:disabled { cursor: wait; }
section { margin: 24px 0; }
dl { padding: 20px; background: #f0fdf4; border: 1px solid #86efac; border-radius: 8px; }
.missing-result { background: #fffbeb; border-color: #fcd34d; }
dt { margin-top: 12px; font-weight: bold; }
dt:first-child { margin-top: 0; }
dd { margin: 6px 0 0; overflow-wrap: anywhere; }
.error { color: #b91c1c; }
.notice { color: #166534; }
p, li { line-height: 1.6; }
a { color: #1d4ed8; }
pre { padding: 16px; background: #f1f5f9; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
