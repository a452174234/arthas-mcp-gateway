import { createApp } from 'vue'
import App from './App.vue'
import router from './router'

// 004 portal 入口：挂载 Vue SPA（浏览器访问网关根加载，research.md R1）。
createApp(App).use(router).mount('#app')
