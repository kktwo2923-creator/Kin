package com.ktwo.cts.data

enum class Mode(val key: String, val label: String) {
    FOLLOW     ("follow",      "跟随全局"),
    AUTO       ("auto",        "智能"),
    POWERSAVE  ("powersave",   "省电"),
    BALANCE    ("balance",     "均衡"),
    PERFORMANCE("performance", "性能"),
    FAST       ("fast",        "风驰"),
    GAME_HEAVY ("game_heavy",  "风驰游戏"),
    GAME_LITE  ("game_lite",   "普通游戏"),
    VIDEO      ("video",       "视频"),
    SOCIAL     ("social",      "社交"),
    READING    ("reading",     "阅读"),
    MUSIC      ("music",        "音乐"),
    ;
    companion object {
        fun fromKey(k: String?): Mode = entries.firstOrNull { it.key == k } ?: FOLLOW
        /** App 配 follow 时,实际下发的全局模式集合(给 UI 用) */
        val DOWNSTREAM = listOf(POWERSAVE, BALANCE, PERFORMANCE, FAST)
        /** 应用画像可选的全部模式(App 画像选择器用,不含 follow 外的全集) */
        val PROFILES = listOf(FOLLOW, POWERSAVE, BALANCE, PERFORMANCE, FAST,
                              GAME_HEAVY, GAME_LITE, VIDEO, SOCIAL, READING, MUSIC)
    }
}
