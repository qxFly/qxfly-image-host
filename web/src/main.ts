import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import 'element-plus/dist/index.css'
import '@/styles/main.css'
import App from '@/App.vue'
import router from '@/router'
import { applyDesktopApiBase } from '@/api/http'

const app = createApp(App)

// 全局注册图标组件，模板里可直接使用 <el-icon><Upload /></el-icon>
for (const [name, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(name, component)
}

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

// 桌面端先把接口地址问清楚（可以指向别的服务器），再挂载，避免首个请求发错地方
applyDesktopApiBase().finally(() => {
  app.mount('#app')
})
