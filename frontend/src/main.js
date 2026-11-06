import { createApp } from 'vue'
import App from './App.vue'
import router from './router'

// Install routing before mounting the root component into the HTML app container.
createApp(App).use(router).mount('#app')
