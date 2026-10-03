# 共用设计基础

从现有 `design-system` 原样迁移基础色板，保持包名和公开字段一致。手机的 `design-system` 通过 `api` 导出这些定义，TV 直接依赖此模块。

本模块依赖 Compose 图形、文本及动画核心类型，不包含手机导航、触摸组件、模糊、Cupertino 或 Miuix。TV 的 `TvTheme` 将共用色板映射到 Compose for TV 的颜色角色；按钮、焦点、卡片尺寸和播放操作由 TV 适配。

已共享颜色、Material 基础字阶、间距、容器语义与圆角基础、纯动画曲线。手机与 TV 分别装配自己的 Typography 和形状容器；手机主题解析器、Miuix 几何及交互组件仍留在 design-system。迁移保留原有公开包名和字段；电视字阶可以独立调整字号和行高。共用设计规范不要求各端使用相同的组件实现或相同尺寸。
