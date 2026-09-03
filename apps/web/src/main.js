// 文件作用：前端工程代码文件，支撑售后争议系统的页面、交互、样式或构建配置。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { createApp } from "vue";
import ElementPlus from "element-plus";
import "element-plus/dist/index.css";
import "./styles.css";
import App from "./App.vue";
import router from "./router/index.js";

createApp(App).use(ElementPlus).use(router).mount("#app");
