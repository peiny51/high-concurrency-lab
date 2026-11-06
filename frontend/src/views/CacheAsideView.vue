<script setup>
import { ref } from 'vue'

const productId = ref(1)
const loading = ref(false)
const product = ref(null)
const httpStatus = ref(null)
const error = ref('')
const result = ref('No query yet.')
const newName = ref('')
const notice = ref('')

/** Queries the ordinary cache-aside endpoint and displays the reported data source or lookup error. */
async function queryProduct() {
  product.value = null
  httpStatus.value = null
  error.value = ''
  notice.value = ''
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
    if (!response.ok) {
      error.value = response.status === 404
        ? 'Product not found. Use an existing ID, or open the Cache Penetration case to test missing products.'
        : `Request failed (HTTP ${response.status}). Check the backend and Redis.`
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

/** Saves a validated name and reports invalidation; a failed response may follow a committed update. */
async function renameProduct() {
  product.value = null
  httpStatus.value = null
  error.value = ''
  notice.value = ''
  const renameProductId = productId.value
  const cleanName = newName.value.trim()
  if (!Number.isSafeInteger(renameProductId) || renameProductId <= 0) {
    error.value = 'Enter a positive integer product ID.'
    result.value = 'No request was sent.'
    return
  }
  if (cleanName.length === 0 || cleanName.length > 255) {
    error.value = 'Enter a name containing 1 to 255 characters, not just spaces.'
    result.value = 'No request was sent.'
    return
  }

  loading.value = true
  result.value = 'Saving name and invalidating cache...'
  try {
    // Encode user text so spaces, ampersands, and other characters cannot alter query parameters.
    const encodedName = encodeURIComponent(cleanName)
    const response = await fetch(`/api/product-cache/rename?productId=${renameProductId}&name=${encodedName}`, {
      method: 'POST',
    })
    httpStatus.value = response.status
    const text = await response.text()
    result.value = text
    if (!response.ok) {
      error.value = response.status === 404
        ? 'Product not found. No name was changed.'
        : response.status === 400
          ? 'Check the product ID and name. The request was rejected.'
          : 'Rename could not be confirmed. The name may already be saved; cached data may be stale.'
      return
    }
    const data = JSON.parse(text)
    if (typeof data.name !== 'string' || data.cacheInvalidated !== true) {
      error.value = 'Rename could not be confirmed. Check the response before retrying.'
      return
    }
    newName.value = data.name
    notice.value = `Product ${data.productId} renamed to "${data.name}". Its name cache was invalidated. Click Query Product to load the saved name.`
    result.value = JSON.stringify(data, null, 2)
  } catch {
    error.value = 'Rename could not be confirmed. The name may already be saved; check before retrying.'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main>
    <RouterLink to="/cache">Back to Cache Scenarios</RouterLink>
    <h1>Product Name Cache and Rename</h1>
    <p>A product name is read often and occasionally changed. Cache reads in Redis, then invalidate the old cache after saving a new name in MySQL.</p>
    <p>Only the product name is cached. Stock and purchases still use MySQL.</p>
    <form @submit.prevent="queryProduct">
      <label for="cache-product-id">Product ID</label>
      <input id="cache-product-id" v-model.number="productId" type="number" min="1" step="1" required :disabled="loading" />
      <button type="submit" :disabled="loading">{{ loading ? 'Loading...' : 'Query Product' }}</button>
    </form>

    <h2>Rename Product</h2>
    <p>Uses the Product ID above. Save a new name in MySQL, then invalidate its cached name. Stock is not changed.</p>
    <form @submit.prevent="renameProduct">
      <label for="new-product-name">New Name</label>
      <input id="new-product-name" v-model="newName" type="text" maxlength="255" required :disabled="loading" />
      <button type="submit" :disabled="loading">Rename Product</button>
    </form>

    <section aria-live="polite" aria-label="Query result">
      <p v-if="httpStatus !== null">HTTP status: {{ httpStatus }}</p>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
      <p v-if="notice" class="notice">{{ notice }}</p>
      <dl v-if="product">
        <dt>Product ID</dt><dd>{{ product.productId }}</dd>
        <dt>Product Name</dt><dd>{{ product.name }}</dd>
        <dt>Data Source</dt><dd><strong>{{ product.source === 'redis' ? 'Redis (cache hit)' : 'MySQL (cache miss)' }}</strong></dd>
      </dl>
    </section>

    <p>All cache pages share the same name cache and not-found marker. This page uses the ordinary cache lookup flow. A query on another page may already have populated this cache.</p>

    <h2>Try It</h2>
    <ol>
      <li>Use an existing product ID. If none exists, set initial stock on the Inventory page first.</li>
      <li>Query once. A missing or expired cache entry is loaded from MySQL.</li>
      <li>Query again within 60 seconds to see Redis as the source.</li>
      <li>After the cache expires, query again to see MySQL. Cache hits do not extend the expiry.</li>
      <li>Rename the product. Its cache is invalidated; the next query reloads from MySQL, and another query uses Redis.</li>
    </ol>
    <p>The source is reported by the API, not inferred from your click count. An existing cache entry can make your first click a Redis hit.</p>
    <p>Run rename and query one at a time. This basic invalidation example does not guarantee consistency during concurrent updates or cache failures.</p>
    <RouterLink to="/inventory">Go to Inventory</RouterLink>

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
dt { margin-top: 12px; font-weight: bold; }
dt:first-child { margin-top: 0; }
dd { margin: 6px 0 0; overflow-wrap: anywhere; }
.error { color: #b91c1c; }
.notice { color: #166534; }
p, li { line-height: 1.6; }
a { color: #1d4ed8; }
pre { padding: 16px; background: #f1f5f9; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
