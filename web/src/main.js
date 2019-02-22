import 'babel-polyfill'
import Vue from 'vue'
Vue.config.debug = true;
import App from './App.vue'
import Element from 'element-ui'
import 'element-ui/lib/theme-chalk/index.css'
Vue.use(Element)

import MintUI from 'mint-ui'
import 'mint-ui/lib/style.css'
Vue.use(MintUI)

// 引用路由
import VueRouter from 'vue-router'
// 光引用不成，还得使用

Vue.use(VueRouter)
// 引用路由配置文件
import routes from './config/routes'
// 使用配置文件规则
const router = new VueRouter({
  routes
})

router.beforeEach((to, from, next) => {
    //NProgress.start();
    if (to.path == '/login') {
        //sessionStorage.removeItem('user');
    }
    if(to.path == "/visitor") {
        next();
    } else {
        let user = JSON.parse(sessionStorage.getItem('user'));

        if (!user && to.path != '/login' && to.path != '/visotor' ) {
            next({path: '/login'})
        } else {
            if(from.path.indexOf(to.path) == -1) {
                next();
            } else {
                next(false);
            }
        }
    }
})
new Vue({
  router,
  el: '#app',
  render: h => h(App)
})
