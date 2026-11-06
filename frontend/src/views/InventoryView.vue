<script setup>
import { ref } from 'vue'

const productId = ref(1)
const initialStock = ref(10)
const quantity = ref(1)
const loading = ref(false)
const result = ref('No query yet.')

/** Validates and resets the shared demo stock; an unconfirmed response must not be retried blindly. */
async function resetStock() {
  if (!Number.isSafeInteger(initialStock.value) || initialStock.value < 0 || initialStock.value > 2147483647) {
    result.value = 'Enter an integer stock between 0 and 2147483647.'
    return
  }

  loading.value = true
  result.value = 'Resetting stock...'

  try {
    const response = await fetch(`/api/overselling/reset?stock=${initialStock.value}`, {
      method: 'POST',
    })
    const product = await response.json()
    result.value = `HTTP ${response.status}${response.ok ? '' : ' (Reset failed)'}\n${JSON.stringify(product, null, 2)}`
    if (response.ok) {
      productId.value = product.id
    }
  } catch {
    result.value = 'Reset could not be confirmed. Query stock before retrying.'
  } finally {
    loading.value = false
  }
}

/** Submits one quantity-based purchase and displays the backend result without automatic retry. */
async function purchaseOnce() {
  if (!Number.isSafeInteger(productId.value) || productId.value <= 0) {
    result.value = 'Enter a positive integer product ID.'
    return
  }
  if (!Number.isSafeInteger(quantity.value) || quantity.value <= 0 || quantity.value > 2147483647) {
    result.value = 'Enter an integer quantity between 1 and 2147483647.'
    return
  }

  loading.value = true
  result.value = 'Purchasing...'

  try {
    const response = await fetch(`/api/overselling/purchase-safe?productId=${productId.value}&quantity=${quantity.value}`, {
      method: 'POST',
    })
    const purchase = await response.json()
    result.value = `HTTP ${response.status}${response.ok ? '' : ' (Request failed)'}\n${JSON.stringify(purchase, null, 2)}`
  } catch {
    result.value = 'Purchase could not be confirmed. Query stock before retrying.'
  } finally {
    loading.value = false
  }
}

/** Runs ten purchases against captured inputs and compares confirmed units with observed stock change. */
async function runConcurrentPurchases() {
  // Capture inputs so edits during the run cannot change which product or quantity is tested.
  const experimentProductId = productId.value
  const experimentQuantity = quantity.value
  if (!Number.isSafeInteger(experimentProductId) || experimentProductId <= 0) {
    result.value = 'Enter a positive integer product ID.'
    return
  }
  if (!Number.isSafeInteger(experimentQuantity) || experimentQuantity <= 0 || experimentQuantity > 2147483647) {
    result.value = 'Enter an integer quantity between 1 and 2147483647.'
    return
  }

  loading.value = true
  result.value = 'Reading stock before the experiment...'

  /** Returns validated stock for the captured experiment ID; rejects unusable responses. */
  async function readStock() {
    const response = await fetch(`/api/overselling/stock?productId=${experimentProductId}`)
    if (!response.ok) {
      throw new Error(`Stock query returned HTTP ${response.status}.`)
    }
    const product = await response.json()
    if (!Number.isSafeInteger(product.stock) || product.stock < 0) {
      throw new Error('Stock query returned an invalid stock value.')
    }
    return product.stock
  }

  try {
    const stockBefore = await readStock()
    const requestCount = 10
    result.value = `Sending ${requestCount} purchase requests...`

    // Each async callback starts a request; allSettled keeps failures from hiding other outcomes.
    const requests = Array.from({ length: requestCount }, async () => {
      const response = await fetch(`/api/overselling/purchase-safe?productId=${experimentProductId}&quantity=${experimentQuantity}`, {
        method: 'POST',
      })
      if (!response.ok) {
        throw new Error(`Purchase returned HTTP ${response.status}.`)
      }
      const purchase = await response.json()
      if (typeof purchase.success !== 'boolean') {
        throw new Error('Purchase returned an invalid success value.')
      }
      return purchase.success
    })

    const outcomes = await Promise.allSettled(requests)
    const successfulPurchases = outcomes.filter((outcome) => outcome.status === 'fulfilled' && outcome.value === true).length
    const rejectedPurchases = outcomes.filter((outcome) => outcome.status === 'fulfilled' && outcome.value === false).length
    const requestErrors = outcomes.filter((outcome) => outcome.status === 'rejected').length
    const report = {
      productId: experimentProductId,
      quantityPerRequest: experimentQuantity,
      requestCount,
      stockBefore,
      successfulPurchases,
      rejectedPurchases,
      requestErrors,
      successfulUnits: successfulPurchases * experimentQuantity,
      stockAfter: null,
      stockDecrease: null,
      comparison: 'Waiting for the final stock query.',
    }
    result.value = JSON.stringify(report, null, 2)

    try {
      report.stockAfter = await readStock()
      report.stockDecrease = stockBefore - report.stockAfter
      // Transport failure does not prove the purchase failed; the server may have committed it.
      report.comparison = requestErrors > 0
        ? 'Inconclusive: some purchase outcomes are unknown.'
        : report.successfulUnits === report.stockDecrease
          ? 'Counts match in this run; this does not prove concurrency safety.'
          : 'Mismatch detected: successful units differ from the stock decrease.'
    } catch {
      report.comparison = 'Inconclusive: final stock query failed. Purchase counts are preserved.'
    }
    result.value = JSON.stringify(report, null, 2)
  } catch {
    result.value = 'Initial stock query failed. No purchase requests were sent.'
  } finally {
    loading.value = false
  }
}

/** Displays the current stock response, preserving non-JSON error bodies for inspection. */
async function queryStock() {
  if (!Number.isSafeInteger(productId.value) || productId.value <= 0) {
    result.value = 'Enter a positive integer product ID.'
    return
  }

  loading.value = true
  result.value = 'Loading...'

  try {
    const response = await fetch(`/api/overselling/stock?productId=${productId.value}`)
    const body = await response.text()
    let formattedBody = body
    try {
      formattedBody = JSON.stringify(JSON.parse(body), null, 2)
    } catch {
      // Preserve non-JSON error responses for inspection.
    }
    result.value = `HTTP ${response.status}${response.ok ? '' : ' (Query failed)'}\n${formattedBody}`
  } catch {
    result.value = 'Request failed. Check that the frontend development server and backend are running.'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main>
    <h1>Safe Inventory Purchase</h1>
    <h2>Set Initial Stock</h2>
    <p>This resets the shared demo product, regardless of the lookup ID below.</p>
    <form @submit.prevent="resetStock">
      <label for="initial-stock">Initial Stock</label>
      <input id="initial-stock" v-model.number="initialStock" type="number" min="0" max="2147483647" step="1" required />
      <button type="submit" :disabled="loading">Set Stock</button>
    </form>
    <h2>Query Stock</h2>
    <p>Enter a product ID to view inventory data from the backend.</p>
    <form @submit.prevent="queryStock">
      <label for="product-id">Product ID</label>
      <input id="product-id" v-model.number="productId" type="number" min="1" step="1" required />
      <button type="submit" :disabled="loading">{{ loading ? 'Loading...' : 'Query Stock' }}</button>
    </form>
    <h2>Buy Products</h2>
    <p>Uses the Product ID above. Stock must cover the full quantity; partial purchases are not allowed. Query stock after purchasing.</p>
    <form @submit.prevent="purchaseOnce">
      <label for="quantity">Quantity</label>
      <input id="quantity" v-model.number="quantity" type="number" min="1" max="2147483647" step="1" required :disabled="loading" />
      <button type="submit" :disabled="loading">Buy</button>
    </form>
    <h2>Verify Concurrent Purchases</h2>
    <p>Send 10 requests, each buying the Quantity above. Try stock 5 and quantity 2: expect 2 successful requests and 1 unit left. Do not reset stock or buy from other pages during verification.</p>
    <button type="button" :disabled="loading" @click="runConcurrentPurchases">Run Concurrent Purchases</button>
    <h2>How Stock Is Updated</h2>
    <p>MySQL checks availability and deducts the full quantity in one update. One updated row means success; zero means insufficient stock or a missing product.</p>
    <pre>UPDATE products
SET stock = stock - :quantity
WHERE id = :productId AND stock &gt;= :quantity;</pre>
    <h2>Response</h2>
    <pre aria-live="polite">{{ result }}</pre>
  </main>
</template>

<style scoped>
main { max-width: 720px; margin: 48px auto; padding: 0 20px; font-family: sans-serif; }
form { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; }
input, button { padding: 8px 12px; font: inherit; }
input { width: 100px; }
button { cursor: pointer; }
button:disabled { cursor: wait; }
pre { padding: 16px; background: #f1f5f9; color: #172033; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
