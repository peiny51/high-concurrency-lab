<template>
  <main>
    <h1>Cache Scenarios</h1>
    <p>Choose one case. Each page has its own explanation, controls, and API results.</p>
    <section>
      <h2>Product Name Cache and Rename</h2>
      <p>Read a name through Redis and MySQL. Rename the product and observe cache invalidation.</p>
      <RouterLink to="/cache/aside">Open Cache Aside</RouterLink>
    </section>
    <section>
      <h2>Cache Penetration Protection</h2>
      <p>Query a missing product and observe short-lived not-found caching.</p>
      <RouterLink to="/cache/penetration">Open Cache Penetration</RouterLink>
    </section>
    <section>
      <h2>Cache Breakdown Protection</h2>
      <p>Coordinate concurrent requests with a Redis lock when a popular product's cache is empty.</p>
      <RouterLink to="/cache/breakdown">Open Cache Breakdown</RouterLink>
    </section>
    <section>
      <h2>Cache Avalanche Protection</h2>
      <p>Spread product cache expiration times with randomized TTLs, and inspect actual Redis expiry values.</p>
      <RouterLink to="/cache/avalanche">Open Cache Avalanche</RouterLink>
    </section>
    <RouterLink to="/">Back to Home</RouterLink>
  </main>
</template>

<style scoped>
main { max-width: 720px; margin: 48px auto; padding: 0 20px; }
section { margin: 24px 0; padding: 20px; border: 1px solid #cbd5e1; border-radius: 8px; }
a { color: #1d4ed8; }
p { line-height: 1.6; }
</style>
