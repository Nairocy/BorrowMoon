package com.genkaim.picocam.moon

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 帧间跟踪：把 10Hz 的检测结果变成稳定的目标列表。
 *
 * - 最近邻匹配（代价 = 归一化距离 + 半径差的一半）
 * - EMA 平滑（α = 0.35）
 * - 命中 2 帧才点亮，丢失 5 帧才熄灭 → 抑制单帧噪声造成的闪烁
 */
class MoonTracker {

    private class Track(var m: Moon, var hits: Int, var misses: Int)

    private var tracks = ArrayList<Track>()

    @Synchronized
    fun update(dets: List<Moon>): List<Moon> {
        val used = BooleanArray(dets.size)
        val next = ArrayList<Track>(dets.size + tracks.size)

        for (t in tracks) {
            var best = -1
            var bestCost = 0.14f
            for (i in dets.indices) {
                if (used[i]) continue
                val d = dets[i]
                val dist = hypot(d.x - t.m.x, d.y - t.m.y)
                val rd = abs(d.r - t.m.r) / maxOf(t.m.r, 0.0005f)
                val cost = dist + rd * 0.35f
                if (cost < bestCost) {
                    bestCost = cost
                    best = i
                }
            }
            if (best >= 0) {
                used[best] = true
                val d = dets[best]
                val a = 0.35f
                t.m = Moon(
                    x = t.m.x + (d.x - t.m.x) * a,
                    y = t.m.y + (d.y - t.m.y) * a,
                    r = t.m.r + (d.r - t.m.r) * a,
                    luma = t.m.luma + (d.luma - t.m.luma) * a,
                )
                t.hits++
                t.misses = 0
            } else {
                t.misses++
            }
            if (t.misses <= 5) next.add(t)
        }

        for (i in dets.indices) {
            if (!used[i]) next.add(Track(dets[i], 1, 0))
        }

        tracks = next
        return tracks
            .filter { it.hits >= 2 && it.misses <= 5 }
            .map { it.m }
            .sortedByDescending { it.r }
            .take(4)
    }

    @Synchronized
    fun reset() {
        tracks = ArrayList()
    }
}
