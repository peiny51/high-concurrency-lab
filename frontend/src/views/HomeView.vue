<template>
  <main>
    <h1>Choose a Scenario</h1>
    <p>Use each page to run a small case and inspect real API results.</p>
    <section>
      <h2>Safe Inventory Purchase</h2>
      <p>Set stock, buy a quantity, and verify deductions with 10 concurrent requests.</p>
      <RouterLink to="/inventory">Open Inventory</RouterLink>
    </section>
    <section>
      <h2>Cache Scenarios</h2>
      <p>Explore product name caching, missing-product caching, coordinated cache rebuilding, and randomized cache expiration.</p>
      <RouterLink to="/cache">Open Cache Scenarios</RouterLink>
    </section>
    <section>
      <h2>Request Rate Limiting</h2>
      <p>Query a product, send concurrent requests, and observe HTTP 429 and window recovery.</p>
      <RouterLink to="/rate-limit">Open Rate Limiting</RouterLink>
    </section>
    <section>
      <h2>Kafka Stock Deduction</h2>
      <p>Submit an asynchronous stock deduction request and inspect its processing result. No orders or payments are created.</p>
      <RouterLink to="/kafka">Open Kafka Stock Deduction</RouterLink>
    </section>
  </main>
</template>

<style scoped>
main { max-width: 720px; margin: 48px auto; padding: 0 20px; }
section { margin: 24px 0; padding: 20px; border: 1px solid #cbd5e1; border-radius: 8px; }
a { color: #1d4ed8; }
p { line-height: 1.6; }
</style>
