package com.genkaim.picocam.moon

/**
 * 一个被识别出来的"月亮候选"。
 *
 * 坐标全部归一化到分析帧（与预览同一 FOV）：
 *  - [x] / [y] ∈ 0..1，画面左上角为 0
 *  - [r] 为半径，相对画面**宽度**归一化（跨分辨率一致）
 *  - [luma] 为亮斑原始平均亮度 0..1，用于调制叠加透明度
 */
data class Moon(
    val x: Float,
    val y: Float,
    val r: Float,
    val luma: Float = 1f,
)
