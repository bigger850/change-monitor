import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { setUnauthorizedHandler } from './api/http'
import './style.css'

// 401 统一跳登录：通过注入回调避免 http 层与 router 循环依赖
setUnauthorizedHandler(() => {
  void router.push('/login')
})

createApp(App).use(router).mount('#app')
