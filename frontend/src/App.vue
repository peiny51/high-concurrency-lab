<template>
  <header class="site-header">
    <p class="site-title">High-Concurrency Backend Lab</p>
    <nav aria-label="Main navigation">
      <RouterLink to="/">Home</RouterLink>
      <RouterLink to="/inventory">Inventory</RouterLink>
      <RouterLink to="/cache">Cache Scenarios</RouterLink>
      <RouterLink to="/rate-limit">Rate Limiting</RouterLink>
    </nav>
  </header>
  <RouterView />
</template>

<style>
body { margin: 0; color: #172033; background: #ffffff; font-family: sans-serif; }
.site-header { max-width: 720px; margin: 24px auto 0; padding: 0 20px; }
.site-title { font-size: 18px; font-weight: bold; }
nav { display: flex; gap: 20px; flex-wrap: wrap; }
nav a { color: #1d4ed8; padding: 6px 0; }
nav a.router-link-exact-active { color: #172033; font-weight: bold; text-decoration-thickness: 3px; }
a:focus-visible, button:focus-visible, input:focus-visible { outline: 3px solid #2563eb; outline-offset: 3px; }
</style>
